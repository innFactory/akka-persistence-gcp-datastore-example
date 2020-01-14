package sample.cqrs

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.datastore.journal.read.DatastoreScaladslReadJournal
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.TimeBasedUUID
import akka.persistence.typed.PersistenceId
import akka.stream.KillSwitches
import akka.stream.SharedKillSwitch
import akka.stream.scaladsl.RestartSource
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.datastax.driver.core.PreparedStatement
import com.datastax.driver.core.Row
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * General purpose event processor infrastructure. Not specific to the ShoppingCart domain.
 */
object EventProcessor {

  case object Ping extends CborSerializable

  def entityKey(eventProcessorId: String): EntityTypeKey[Ping.type] = EntityTypeKey[Ping.type](eventProcessorId)

  def init[Event](
      system: ActorSystem[_],
      settings: EventProcessorSettings,
      eventProcessorStream: String => EventProcessorStream[Event]): Unit = {
    val eventProcessorEntityKey = entityKey(settings.id)

    ClusterSharding(system).init(Entity(eventProcessorEntityKey)(entityContext =>
      EventProcessor(eventProcessorStream(entityContext.entityId), None)).withRole("read-model"))

    KeepAlive.init(system, eventProcessorEntityKey)
  }

  def apply(eventProcessorStream: EventProcessorStream[_], id: Option[String]): Behavior[Ping.type] = {


    Behaviors.setup { context =>
      val killSwitch = KillSwitches.shared("eventProcessorSwitch")

      eventProcessorStream match {
        case tag: ShoppingCartEventProcessorStream => eventProcessorStream.runQueryStream(killSwitch)
        case persistence: ShoppingCartPersistenceIdEventProcessorStream =>  eventProcessorStream.runQueryStreamForPersistenceId(killSwitch, id.getOrElse(""))
        case ids: PersistenceIdsEventProcessorStream => eventProcessorStream.runQueryStreamForPersistenceIds(killSwitch)
      }

      Behaviors
        .receiveMessage[Ping.type] { ping =>
          Behaviors.same
        }
        .receiveSignal {
          case (_, PostStop) =>
            killSwitch.shutdown()
            Behaviors.same
        }

    }
  }

}

abstract class EventProcessorStream[Event: ClassTag](
    system: ActorSystem[_],
    executionContext: ExecutionContext,
    eventProcessorId: String,
    tag: String) {

  protected val log: Logger = LoggerFactory.getLogger(getClass)
  implicit val sys: ActorSystem[_] = system
  implicit val ec: ExecutionContext = executionContext

  private val query =
    PersistenceQuery(system.toClassic).readJournalFor[DatastoreScaladslReadJournal]("gcp-datastore-query")

  protected def processEvent(event: Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done]
  protected def processPersistenceId(persistenceId: String): Future[Done]

  def runQueryStream(killSwitch: SharedKillSwitch): Unit = {
    RestartSource
      .withBackoff(minBackoff = 500.millis, maxBackoff = 20.seconds, randomFactor = 0.1) { () =>
        Source.futureSource {
          readOffset().map { offset =>
            log.info("Starting stream for tag [{}] from offset [{}]", tag, offset)
            processEventsByTag(offset)
            // groupedWithin can be used here to improve performance by reducing number of offset writes,
            // with the trade-off of possibility of more duplicate events when stream is restarted
          }
        }
      }
      .via(killSwitch.flow)
      .runWith(Sink.ignore)
  }

  def runQueryStreamForPersistenceId(killSwitch: SharedKillSwitch, persistenceId: String): Unit = {
    RestartSource
      .withBackoff(minBackoff = 500.millis, maxBackoff = 20.seconds, randomFactor = 0.1) { () =>
        Source.futureSource {
          readPersistenceId(persistenceId).map { persistenceID =>
            log.info("Starting stream for persistenceId [{}] ", tag, persistenceID)
            processEventsByPersistenceId(persistenceID)
            // groupedWithin can be used here to improve performance by reducing number of offset writes,
            // with the trade-off of possibility of more duplicate events when stream is restarted
          }
        }
      }
      .via(killSwitch.flow)
      .runWith(Sink.ignore)
  }

  def runQueryStreamForPersistenceIds(killSwitch: SharedKillSwitch): Unit = {
    RestartSource
      .withBackoff(minBackoff = 500.millis, maxBackoff = 20.seconds, randomFactor = 0.1) { () =>
        Source.futureSource {
          Future("").map { a =>
            log.info("Starting stream for persistenceIds")
            processPersistenceIds()
            // groupedWithin can be used here to improve performance by reducing number of offset writes,
            // with the trade-off of possibility of more duplicate events when stream is restarted
          }
        }
      }
      .via(killSwitch.flow)
      .runWith(Sink.ignore)
  }

  private def processEventsByTag(offset: Offset): Source[Offset, NotUsed] = {
    query.eventsByTag(tag, offset).mapAsync(1) { eventEnvelope =>
      eventEnvelope.event match {
        case event: Event =>
          processEvent(event, PersistenceId.ofUniqueId(eventEnvelope.persistenceId), eventEnvelope.sequenceNr).map(_ =>
            eventEnvelope.offset)
        case other =>
          Future.failed(new IllegalArgumentException(s"Unexpected event [${other.getClass.getName}]"))
      }
    }
  }

  private def processEventsByPersistenceId(persistenceId: String): Source[Long, NotUsed] = {
    query.eventsByPersistenceId(persistenceId, -1, 2).mapAsync(1) { eventEnvelope =>
      eventEnvelope.event match {
        case event: Event =>
          processEvent(event, PersistenceId.ofUniqueId(eventEnvelope.persistenceId), eventEnvelope.sequenceNr).map(_ =>
            eventEnvelope.sequenceNr)
        case other =>
          Future.failed(new IllegalArgumentException(s"Unexpected event [${other.getClass.getName}]"))
      }
    }
  }

  private def processPersistenceIds(): Source[String, NotUsed] = {
    query.persistenceIds().mapAsync(1) { id =>
          println(id)
          processPersistenceId(id).map(_ => id)
      }
  }

  private def readOffset(): Future[Offset] = {
   Future(NoOffset)
  }

  private def readPersistenceId(persistenceId: String): Future[String] = {
    Future(persistenceId)
  }

  private def extractOffset(maybeRow: Option[Row]): Offset = {
    maybeRow match {
      case Some(row) =>
        val uuid = row.getUUID("timeUuidOffset")
        if (uuid == null) {
          NoOffset
        } else {
          TimeBasedUUID(uuid)
        }
      case None => NoOffset
    }
  }



}
