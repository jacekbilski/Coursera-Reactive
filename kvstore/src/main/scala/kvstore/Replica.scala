package kvstore

import akka.actor._
import kvstore.Arbiter._
import scala.collection.immutable.Queue
import akka.actor.SupervisorStrategy.Restart
import scala.annotation.tailrec
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.Some
import akka.actor.OneForOneStrategy
import java.util.Date

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor with ActorLogging {
  import Replica._
  import Replicator._
  import Persistence._
  import context.dispatcher

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]
  // key -> (id, where to send confirmation about operation success/failure (can be null), is persisted (are we NOT waiting for persistence), set of replicators, from which we're still waiting for a reply, when timeout is due)
  var acks = Map.empty[String, (Long, ActorRef, Boolean, Set[ActorRef], Long)]

  var expectedSeq = 0L
  override val supervisorStrategy = OneForOneStrategy() {
    case _: Exception => Restart
  }
  val persistence = context.actorOf(PersistenceProxy.props(persistenceProps))
  val persistTimeout = 100.milliseconds

  override def preStart() {
    arbiter ! Join
  }

  def receive = {
    case JoinedPrimary =>
      context.setReceiveTimeout(persistTimeout)
      context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for the leader role. */
  def leader: Receive = {
    case Insert(key, value, id) =>
      kv = kv.updated(key, value)
      checkForTimeouts
      initAck(key, id, sender, waitForPersistence = true)
      replicateOperation(Insert(key, value, id))
      persistence ! Persist(key, Some(value), id)
    case Remove(key, id) =>
      kv = kv - key
      checkForTimeouts
      initAck(key, id, sender, waitForPersistence = true)
      replicateOperation(Remove(key, id))
      persistence ! Persist(key, None, id)
    case Get(key, id) =>
      sender ! new GetResult(key, kv.get(key), id)
      checkForTimeouts
    case Replicated(key, id) =>
      acks = acks.updated(key, (acks(key)._1, acks(key)._2, acks(key)._3, acks(key)._4 - sender, acks(key)._5))
      sendAckIfPossible(key)
      checkForTimeouts
    case Persisted(key, id) =>
      acks = acks.updated(key, (acks(key)._1, acks(key)._2, true, acks(key)._4, acks(key)._5))
      sendAckIfPossible(key)
      checkForTimeouts
    case Replicas(replicas) =>
      processNewReplicasSet(replicas)
      checkForTimeouts
    case ReceiveTimeout =>
      checkForTimeouts
  }

  private def sendAckIfPossible(key: String) = {
    if (acks(key)._3 && acks(key)._4.isEmpty) {
      if (acks(key)._2 != null)
        acks(key)._2 ! OperationAck(acks(key)._1)
      acks = acks - key
    }
  }

  private def replicateOperation(operation: Operation) = {
    operation match {
      case Insert(key, value, id) =>
        for {
          replicator <- replicators
        } yield {
          replicator ! Replicate(key, Some(value), id)
        }
      case Remove(key, id) =>
        for {
          replicator <- replicators
        } yield {
          replicator ! Replicate(key, None, id)
        }
      case Get(_, _) =>
    }
  }

  private def initAck(key: String, id: Long, sendAckTo: ActorRef, waitForPersistence: Boolean) = {
    acks = acks.updated(key, (id, sendAckTo, !waitForPersistence, replicators, new Date().getTime + 1000))
  }

  private def checkForTimeouts = {
    val now = new Date().getTime
    var keysToRemove = Set.empty[String]
    for {
      (k, v) <- acks
      if v._5 < now
    } yield {
      keysToRemove = keysToRemove + k
      v._2 ! OperationFailed(v._1)
    }
    for {
      k <- keysToRemove
    } yield {
      acks = acks - k
    }
  }

  private def processNewReplicasSet(replicas: Set[ActorRef]) = {
    val replicasToDrop = secondaries.keySet -- replicas
    for {
      key <- acks.keySet
    } yield {
      val v = acks(key)
      acks = acks.updated(key, (v._1, v._2, v._3, v._4 -- replicasToDrop, v._5))
      sendAckIfPossible(key)
    }
    for {
      replica <- replicasToDrop
    } yield {
      secondaries(replica) ! PoisonPill
      secondaries - replica
    }

    val newReplicas = replicas - self -- secondaries.keySet
    for {
      replica <- newReplicas
    } yield {
      val replicator = context.actorOf(Replicator.props(replica))
      replicators = replicators + replicator
      secondaries = secondaries.updated(replica, replicator)
      for {
        (k, v) <- kv
      } yield {
        if (acks.contains(k)) {
          val av = acks(k)
          replicator ! Insert(k, v, av._1)
          acks = acks.updated(k, (av._1, av._2, av._3, av._4 + replica, av._5))
        } else {
          replicator ! Insert(k, v, -1L)  // I don't care about the id, I'm not interested in reply anyway
        }
      }
    }
  }

  /* TODO Behavior for the replica role. */
  val replica: Receive = {
    case Get(key, id) =>
      sender ! new GetResult(key, kv.get(key), id)
    case Snapshot(key, valueOption, seq) =>
      if (seq < expectedSeq)
        sender ! SnapshotAck(key, seq)
      if (seq == expectedSeq) {
        valueOption match {
          case None => kv = kv - key
          case Some(value) => kv = kv.updated(key, value)
        }
        val persist = new Persist(key, valueOption, seq)
        persistence ! persist
        context.setReceiveTimeout(persistTimeout)
        context.become(replicaWaitingForPersistence(persist, sender))
      }
  }

  def replicaWaitingForPersistence(persist: Persist, replicator: ActorRef): Receive = {
    case Get(key, id) =>
      sender ! new GetResult(key, kv.get(key), id)
    case Persisted(key, id) =>
      if (id == persist.id) {
        context.setReceiveTimeout(Duration.Undefined)
        replicator ! SnapshotAck(key, id)
        expectedSeq = id + 1
        context.become(replica, discardOld = true)
      } else {
        log.warning(s"Got Persisted message for different (key, id), got ($key, $id), expected (?, ${persist.id}})")
      }
    case ReceiveTimeout =>
      context.setReceiveTimeout(persistTimeout)
      persistence ! persist
  }
}
