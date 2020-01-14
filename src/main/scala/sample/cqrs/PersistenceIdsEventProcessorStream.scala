package sample.cqrs

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId

import scala.concurrent.{ExecutionContext, Future}

class PersistenceIdsEventProcessorStream(
    system: ActorSystem[_],
    executionContext: ExecutionContext,
    eventProcessorId: String,
    persistenceId: String)
    extends EventProcessorStream[ShoppingCart.Event](system, executionContext, eventProcessorId, persistenceId) {

  def processPersistenceId(persistenceId: String): Future[Done] = {
    log.info("EventProcessor consumed {}", persistenceId)
    system.eventStream ! EventStream.Publish(persistenceId)
    Future.successful(Done)
  }

  def processEvent(event: ShoppingCart.Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done] = {
    Future.successful(Done)
  }
}
