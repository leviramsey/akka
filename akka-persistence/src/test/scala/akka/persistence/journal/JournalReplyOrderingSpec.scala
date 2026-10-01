/*
 * Copyright (C) 2025 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal

import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Random
import scala.util.Success
import scala.util.Try
import scala.util.control.NoStackTrace

import akka.Done
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.pattern.BackoffOpts
import akka.pattern.BackoffSupervisor
import akka.pattern.RetrySettings
import akka.pattern.ask
import akka.pattern.retry
import akka.persistence.PersistentActor
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterEach
import akka.testkit.TestProbe

object JournalReplyOrderingSpec {
  def config(specName: String, numResequencers: Int) =
    ConfigFactory.parseString(s"""
        |akka.persistence.journal.controlled-in-mem.write-reply-ordering-groups = $numResequencers
        """.stripMargin).withFallback(ControlledInmemJournal.config(specName))

  private def supervisedProps(pid: String, expected: Int, modulo: Int, probe: ActorRef): Props = {
    BackoffSupervisor.props(
      BackoffOpts
        .onStop(
          childProps = persistentActorProps(pid, expected, modulo, probe),
          childName = pid,
          minBackoff = 10.milli,
          maxBackoff = 10.millis,
          randomFactor = 0.0)
        .withReplyWhileStopped(BackingOff))
  }

  private def persistentActorProps(pid: String, expected: Int, modulo: Int, onSpawn: ActorRef) =
    Props(new PersistentActor with ActorLogging {
      override def preStart(): Unit = {
        log.info("Starting actor for {}", persistenceId)
        if ((AsyncWriteJournal.hashForResequencing(self) % modulo) != expected) {
          // effectively a "lane departure warning", since we can't change our UID
          // the only thing we can do is stop.  `PersistentActor.aroundPreStart()`
          // will have requested a recovery permit, but the permitter is watching us
          // so there will be no permit leak if we stop here before processing
          // the granted permit
          super.preStart() // pro-forma: PersistentActor doesn't override preStart()
          context.stop(self) // supervisor will restart
        } else {
          onSpawn ! self
          super.preStart()
        }
      }

      var pending: Promise[Done] = null

      override def postStop(): Unit = {
        if (pending ne null) {
          pending.failure(new RuntimeException("persist failed"))
        }
        super.postStop()
      }

      override val persistenceId: String = pid

      override def receiveRecover = {
        case _ => // nop, no meaningful state
      }

      override def receiveCommand = {
        case "cull" =>
          context.parent ! PoisonPill
          context.stop(self)

        case "get-persistence-id" =>
          sender() ! persistenceId

        case (s: String, p: Promise[Done @unchecked]) =>
          pending = p
          if ((lastSequenceNr % 3) == 0) {
            // handler will be called twice in this case
            var promise = p
            // alternating persists of 2 then 1
            persistAll(Seq(s, s"$s again")) { _ =>
              if (promise ne null) {
                promise.success(Done)
                pending = null
                promise = null
              }
            }
          } else {
            persist(s) { _ =>
              p.success(Done)
              pending = null
            }
          }
      }
    })

  val oneSuccessUnit = List(Success(()))
  val twoSuccessUnit = oneSuccessUnit.head :: oneSuccessUnit

  case object BackingOff extends Exception with NoStackTrace
}

abstract class JournalReplyOrderingSpec(specName: String, numResequencers: Int)
    extends AkkaSpec(JournalReplyOrderingSpec.config(specName, numResequencers))
    with ImplicitSender
    with BeforeAndAfterEach {
  import JournalReplyOrderingSpec._

  require(numResequencers > 0, "must have a positive number of resequencers")

  import system.dispatcher

  def journalProbe = ControlledInmemJournal.getProbe(specName)

  val successfulSpawnProbe = new TestProbe(system)

  final def persistentActorCongruentWith(pid: String, n: Int): ActorRef = {
    require(n < numResequencers && n >= 0)

    system.actorOf(supervisedProps(pid, n, numResequencers, successfulSpawnProbe.ref))
  }

  // It doesn't particularly matter which group has multiple...
  val groupWithTwo = Random.nextInt(numResequencers)
  log.info("Chose group {} to have two resequencers", groupWithTwo)

  // using the same actors as much as we can
  val groups = (0 until numResequencers).iterator.map { group =>
    if (group == groupWithTwo) {
      Seq(persistentActorCongruentWith(s"$group-a", group), persistentActorCongruentWith(s"$group-b", group))
    } else Seq(persistentActorCongruentWith(group.toString, group))
  }.toSeq

  successfulSpawnProbe.receiveN(1 + numResequencers)

  // also synchronizes on all the actors being spawned
  val pidToGroup = Future
    .sequence(groups.iterator.zipWithIndex.flatMap {
      case (group, idx) =>
        group.iterator.map { actor =>
          resolvePid(actor).map { pid =>
            pid -> (idx -> actor)
          }(ExecutionContext.parasitic)
        }
    }.toSeq)
    .futureValue
    .toMap

  def resolvePid(actor: ActorRef): Future[String] = {
    val retrySettings = RetrySettings(100).withDelayFunction(_ => Some(10.millis))

    implicit val timeout: Timeout = 1.second
    retry(retrySettings) { () =>
      (actor ? "get-persistence-id").flatMap {
        case s: String  => Future.successful(s)
        case BackingOff => Future.failed(BackingOff)
        case _          => Future.failed(new RuntimeException("Unexpected reply"))
      }(ExecutionContext.parasitic)
    }
  }

  override def afterEach(): Unit = {
    implicit val timeout: Timeout = 1.second

    ControlledInmemJournal
      .drain(specName)
      .map {
        case 0 => Done
        case n =>
          log.warning("found {} incomplete promises", n)
          // get confirmation that all the persistent actors have restarted after the failures
          successfulSpawnProbe.receiveN(n).toSet.size shouldBe n
          Done
      }(system.dispatchers.lookup("akka.actor.default-blocking-io-dispatcher"))
      .futureValue

    while (journalProbe.msgAvailable) {
      journalProbe.expectMsgType[Any]
    }
    journalProbe.expectNoMessage(30.millis)
  }

  case class State(
      asksByPid: Map[String, (Int, Future[Done])],
      inOrder: Seq[(String, Int, Promise[Seq[Try[Unit]]])],
      attemptsByGroup: Map[Int, Seq[(String, Int, Promise[Seq[Try[Unit]]])]]) {
    def waiting(group: Int): Boolean =
      attemptsByGroup.get(group) match {
        case Some(attempts) if attempts.size > 1 =>
          // is there an attempt which is unfinished before an attempt which is finished?
          attempts
            .foldLeft(Option.empty[Boolean]) { (result, attempt) =>
              def promise = attempt._3
              result match {
                case None =>
                  // look for the earliest unfinished
                  if (!promise.isCompleted) SomeFalse else None

                case Some(false) =>
                  // have seen an unfinished, now looking for a finished
                  if (promise.isCompleted) SomeTrue else SomeFalse

                case Some(true) => SomeTrue
              }
            }
            .getOrElse(false)

        case _ => false
      }

    def success(n: Int): Option[(String, Boolean)] =
      if (n < inOrder.length) {
        val (pid, count, promise) = inOrder(n)

        if (asksByPid(pid)._2.isCompleted) {
          // no point in completing the promise if the future is already completed
          assert(promise.isCompleted, "ask should not have completed before persistence operation")
          None
        } else {
          val wasWaiting = waiting(pidToGroup(pid)._1)
          promise.success(if (count == 1) oneSuccessUnit else twoSuccessUnit)
          Some(pid -> wasWaiting)
        }
      } else None

    def failure(n: Int): Option[(String, Boolean)] =
      if (n < inOrder.length) {
        val (pid, count, promise) = inOrder(n)

        if (asksByPid(pid)._2.isCompleted) {
          assert(promise.isCompleted, "ask should not have completed before persistence operation")
          None
        } else {
          val wasWaiting = waiting(pidToGroup(pid)._1)
          promise.failure(new RuntimeException("journal go boom") with NoStackTrace)
          Some(pid -> wasWaiting)
        }
      } else None

    def asksInGroupByPid(pid: String): Option[Map[String, Future[Done]]] =
      asksByPid.get(pid).flatMap {
        case (group, _) =>
          attemptsByGroup.get(group).map { attempts =>
            attempts.iterator.flatMap {
              case (p, _, _) =>
                asksByPid.get(p).map {
                  case (_, ask) => p -> ask
                }
            }.toMap
          }
      }

    def completedAsk(pid: String): State = copy(asksByPid = asksByPid.removed(pid))
    def completedAsks(pids: Seq[String]): State = copy(asksByPid = asksByPid.removedAll(pids))
  }

  val SomeTrue = Some(true)
  val SomeFalse = Some(false)

  s"A journal with $numResequencers write-reply ordering group(s)" must {
    def performAsks(): Map[String, (Int, Future[Done])] =
      pidToGroup.iterator.map {
        case (pid, (groupId, actor)) =>
          // could do an actual ask here, but since the completions wait until we decide
          // what to do with the attempt (depends on how many resequencing groups there are)
          // we'd need to adjust the timeout
          val p = Promise[Done]()
          actor ! ("persist" -> p)
          pid -> (groupId -> p.future)
      }.toMap

    def receiveAttempts(expected: Int): Vector[(String, Int, Promise[Seq[Try[Unit]]])] =
      journalProbe
        .receiveN(expected)
        .map {
          case ControlledInmemJournal.WriteMessagesAttempt(messages, promise) =>
            messages shouldNot be(empty)
            assert(messages.size < 3, "only one or two events should be persisted")
            val pid = messages.head.persistenceId
            (pid, messages.size, promise)

          case _ => fail("unexpected message on journal probe")
        }
        .toVector

    def setup(): State = {
      val asks = performAsks()
      val attempts = receiveAttempts(1 + numResequencers)

      State(asks, attempts, attempts.groupBy {
        case (pid, _, _) => pidToGroup(pid)._1
      })
    }

    (0 to numResequencers).toList.permutations.foreach { ordering =>
      s"properly order write replies (ordering $ordering) (all success)" in {
        val state = setup()

        state.attemptsByGroup.foreach {
          case (`groupWithTwo`, attempts) => attempts.size shouldBe 2
          case (_, attempts)              => attempts.size shouldBe 1
        }

        val finalState = ordering.foldLeft(state) { (state, toComplete) =>
          val (pidCompleting, wasWaiting) = state.success(toComplete).get

          state.asksInGroupByPid(pidCompleting) match {
            case None                         => fail("No pending asks for pid that we just completed?")
            case Some(asks) if asks.size == 1 =>
              // only one in group, should definitely be completed
              asks(pidCompleting).futureValue shouldBe Done
              state.completedAsk(pidCompleting)

            case Some(asks) if asks.size == 2 =>
              val group = pidToGroup(pidCompleting)._1
              val attempts = state.attemptsByGroup(group)
              attempts.size shouldBe 2

              if (attempts.head._1 == pidCompleting) {
                asks(pidCompleting).futureValue shouldBe Done
                if (wasWaiting) {
                  val waitingPid = attempts(1)._1
                  asks(waitingPid).futureValue shouldBe Done
                  state.completedAsks(Seq(pidCompleting, waitingPid))
                } else state.completedAsk(pidCompleting)
              } else {
                // second attempt from the group to reach the journal was completed
                // if the first attempt from the group were completed, this would be a group of one
                // thus: this is blocked behind the first attempt
                val (firstPid, _, firstPromise) = attempts.head
                firstPromise.isCompleted shouldBe false

                a[TimeoutException] shouldBe thrownBy { Await.ready(asks(pidCompleting), 10.millis) }
                a[TimeoutException] shouldBe thrownBy { Await.ready(asks(firstPid), 10.millis) }
                state
              }

            case _ => fail("unexpected asks for group")
          }
        }

        finalState.asksByPid shouldBe empty
      }

      s"properly order write replies (ordering $ordering) (first fails)" in {
        val state = setup()

        state.attemptsByGroup.foreach {
          case (`groupWithTwo`, attempts) => attempts.size shouldBe 2
          case (_, attempts)              => attempts.size shouldBe 1
        }

        val pidToFail = state.attemptsByGroup(groupWithTwo).head._1

        val finalState = ordering.foldLeft(state) { (state, toComplete) =>
          val pidCompleting = state.inOrder(toComplete)._1
          val group = pidToGroup(pidCompleting)._1
          val (_, wasWaiting) =
            (if (pidCompleting == pidToFail) state.failure(toComplete) else state.success(toComplete)).get

          state.asksInGroupByPid(pidCompleting) match {
            case None => fail("No pending asks for the pid we just completed?")
            case Some(asks) if asks.size == 1 =>
              asks(pidCompleting).futureValue shouldBe Done
              state.completedAsk(pidCompleting)

            case Some(asks) if asks.size == 2 =>
              val attempts = state.attemptsByGroup(group)
              attempts.size shouldBe 2

              if (attempts.head._1 == pidCompleting) {
                (the[RuntimeException] thrownBy { asks(pidCompleting).futureValue }).getMessage should include(
                  "persist failed")
                if (wasWaiting) {
                  val waitingPid = attempts(1)._1
                  asks(waitingPid).futureValue shouldBe Done
                  state.completedAsks(Seq(pidCompleting, waitingPid))
                } else state.completedAsk(pidCompleting)
              } else {
                val (firstPid, _, firstPromise) = attempts.head
                firstPromise.isCompleted shouldBe false

                a[TimeoutException] shouldBe thrownBy { Await.ready(asks(pidCompleting), 10.millis) }
                a[TimeoutException] shouldBe thrownBy { Await.ready(asks(firstPid), 10.millis) }
                state
              }

            case _ => fail("unexpected asks for group")
          }
        }

        finalState.asksByPid shouldBe empty
        // An actor should have failed and been restarted
        successfulSpawnProbe.expectMsgType[ActorRef]
      }
    }
  }
}

class JournalReplyOrderingOneGroupSpec extends JournalReplyOrderingSpec("one", 1)
class JournalReplyOrderingTwoGroupsSpec extends JournalReplyOrderingSpec("two", 2)
