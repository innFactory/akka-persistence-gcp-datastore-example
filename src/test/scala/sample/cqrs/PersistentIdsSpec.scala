package sample.cqrs

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.eventstream.EventStream
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration

class PersistentIdsSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(s"""
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

  "The PersistentIds" should {

    "be consumed by the event processor" in {

      val cart10 = testKit.spawn(ShoppingCart("cart-10-proc", Set()))
      val cart11 = testKit.spawn(ShoppingCart("cart-11-proc", Set()))
      val cart12 = testKit.spawn(ShoppingCart("cart-12-proc", Set()))
      val cart13 = testKit.spawn(ShoppingCart("cart-13-proc", Set()))
      val probe  = testKit.createTestProbe[ShoppingCart.Confirmation]

      val eventProbe = testKit.createTestProbe[String]()
      testKit.system.eventStream ! EventStream.Subscribe(eventProbe.ref)

      testKit.spawn(
        EventProcessor(
          new PersistenceIdsEventProcessorStream(
            system,
            system.executionContext,
            "EventProcessor",
            "ShoppingCart|cart-1-proc"
          ),
          Some("ShoppingCart|cart-1-proc")
        )
      )

      cart10 ! ShoppingCart.AddItem("foo", 42, probe.ref)
      probe.expectMessageType[ShoppingCart.Accepted]
      eventProbe.expectMessage(max = FiniteDuration.apply(15, duration.SECONDS), "ShoppingCart|cart-10-proc")

      cart11 ! ShoppingCart.AddItem("bar", 17, probe.ref)
      probe.expectMessageType[ShoppingCart.Accepted]
      eventProbe.expectMessage(max = FiniteDuration.apply(15, duration.SECONDS), "ShoppingCart|cart-11-proc")

      cart12 ! ShoppingCart.AddItem("bar", 18, probe.ref)
      probe.expectMessageType[ShoppingCart.Accepted]
      eventProbe.expectMessage(max = FiniteDuration.apply(15, duration.SECONDS), "ShoppingCart|cart-12-proc")

      cart13 ! ShoppingCart.AddItem("another", 1, probe.ref)
      probe.expectMessageType[ShoppingCart.Accepted]
      eventProbe.expectMessage(max = FiniteDuration.apply(15, duration.SECONDS), "ShoppingCart|cart-13-proc")
    }
  }

}
