package sample.cqrs

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId

import scala.concurrent.{ ExecutionContext, Future }

class ShoppingCartPersistenceIdEventProcessorStream(
  system: ActorSystem[_],
  executionContext: ExecutionContext,
  eventProcessorId: String,
  persistenceId: String
) extends EventProcessorStream[ShoppingCart.Event](system, executionContext, eventProcessorId, persistenceId) {

  def processEvent(event: ShoppingCart.Event, persistenceId: PersistenceId, sequenceNr: Long): Future[Done] = {
    log.info("EventProcessor({}) consumed {} from {} with seqNr {}", eventProcessorId, event, persistenceId, sequenceNr)
    system.eventStream ! EventStream.Publish(event)
    Future.successful(Done)
  }

  def processPersistenceId(persistenceId: String): Future[Done] =
    Future.successful(Done)
}
