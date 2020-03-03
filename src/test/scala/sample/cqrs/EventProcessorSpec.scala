package sample.cqrs

import akka.actor.testkit.typed.scaladsl.{LoggingTestKit, ScalaTestWithActorTestKit}
import akka.actor.typed.eventstream.EventStream
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

class EventProcessorSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(s"""
      akka.actor.provider = local
      akka.actor.testkit.typed.single-expect-default = 10s
      # For LoggingTestKit
      akka.actor.testkit.typed.filter-leeway = 10s
    """).withFallback(ConfigFactory.load())) with WordSpecLike {

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val request = HttpRequest(uri = "http://localhost:8081/reset", method = HttpMethods.POST)
    val http    = Http(system)
    http.singleRequest(
      request
    )
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    val request = HttpRequest(uri = "http://localhost:8081/reset", method = HttpMethods.POST)
    val http    = Http(system)
    http.singleRequest(
      request
    )
  }

  "The events from the Shopping Cart" should {

    "be consumed by the event processor" in {
      val cart1 = testKit.spawn(ShoppingCart("cart-1-proc", Set("tag-5")))
      val probe = testKit.createTestProbe[ShoppingCart.Confirmation]

      val eventProbe = testKit.createTestProbe[ShoppingCart.Event]()
      testKit.system.eventStream ! EventStream.Subscribe(eventProbe.ref)

      testKit.spawn(
        EventProcessor(
          new ShoppingCartEventProcessorStream(system, system.executionContext, "EventProcessor", "tag-5"),
          None
        )
      )
      cart1 ! ShoppingCart.AddItem("foo", 42, probe.ref)
      probe.expectMessageType[ShoppingCart.Accepted]
      eventProbe.expectMessage(
        max = FiniteDuration.apply(15, duration.SECONDS),
        ShoppingCart.ItemAdded("cart-1-proc", "foo", 42)
      )

      cart1 ! ShoppingCart.AddItem("bar", 17, probe.ref)
      probe.expectMessageType[ShoppingCart.Accepted]
      eventProbe.expectMessage(
        max = FiniteDuration.apply(15, duration.SECONDS),
        ShoppingCart.ItemAdded("cart-1-proc", "bar", 17)
      )
      cart1 ! ShoppingCart.AdjustItemQuantity("bar", 18, probe.ref)
      probe.expectMessageType[ShoppingCart.Accepted]
      eventProbe.expectMessage(
        max = FiniteDuration.apply(15, duration.SECONDS),
        ShoppingCart.ItemQuantityAdjusted("cart-1-proc", "bar", 18)
      )

      val cart2 = testKit.spawn(ShoppingCart("cart-2-proc", Set("tag-5")))
      // also verify that EventProcessor is logging
      LoggingTestKit.info("consumed ItemAdded(cart-2-proc,another,1)").intercept {
        cart2 ! ShoppingCart.AddItem("another", 1, probe.ref)
        probe.expectMessageType[ShoppingCart.Accepted]
      }
      eventProbe.expectMessage(
        max = FiniteDuration.apply(15, duration.SECONDS),
        ShoppingCart.ItemAdded("cart-2-proc", "another", 1)
      )
    }

  }

}
