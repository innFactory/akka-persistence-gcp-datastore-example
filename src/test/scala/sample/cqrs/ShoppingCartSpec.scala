package sample.cqrs

import java.util.UUID

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

class ShoppingCartSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(s"""
      akka.actor.provider = local
      akka.actor.testkit.typed.single-expect-default = 10s
      # For LoggingTestKit
      akka.actor.testkit.typed.filter-leeway = 10s
    """).withFallback(ConfigFactory.load())) with WordSpecLike {

  private var counter = 0
  def newCartId(): String = {
    counter += 1
    s"cart-$counter"
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val request = HttpRequest(uri = "http://localhost:8081/reset",  method = HttpMethods.POST)
    val http = Http(system)
    http.singleRequest(
      request
    )
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    val request = HttpRequest(uri = "http://localhost:8081/reset",  method = HttpMethods.POST)
    val http = Http(system)
    http.singleRequest(
      request
    )
  }

  "The Shopping Cart" should {

        "add item" in {
          val cart = testKit.spawn(ShoppingCart(newCartId(), Set.empty))
          val probe = testKit.createTestProbe[ShoppingCart.Confirmation]
          cart ! ShoppingCart.AddItem("foo", 42, probe.ref)
          probe.expectMessage(ShoppingCart.Accepted(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = false)))
        }

        "reject already added item" in {
          val cart = testKit.spawn(ShoppingCart(newCartId(), Set.empty))
          val probe = testKit.createTestProbe[ShoppingCart.Confirmation]
          cart ! ShoppingCart.AddItem("foo", 42, probe.ref)
          probe.expectMessageType[ShoppingCart.Accepted]
          cart ! ShoppingCart.AddItem("foo", 13, probe.ref)
          probe.expectMessageType[ShoppingCart.Rejected]
        }

        "remove item" in {
          val cart = testKit.spawn(ShoppingCart(newCartId(), Set.empty))
          val probe = testKit.createTestProbe[ShoppingCart.Confirmation]
          cart ! ShoppingCart.AddItem("foo", 42, probe.ref)
          probe.expectMessageType[ShoppingCart.Accepted]
          cart ! ShoppingCart.RemoveItem("foo", probe.ref)
          probe.expectMessage(ShoppingCart.Accepted(ShoppingCart.Summary(Map.empty, checkedOut = false)))
        }

        "adjust quantity" in {
          val cart = testKit.spawn(ShoppingCart(newCartId(), Set.empty))
          val probe = testKit.createTestProbe[ShoppingCart.Confirmation]
          cart ! ShoppingCart.AddItem("foo", 42, probe.ref)
          probe.expectMessageType[ShoppingCart.Accepted]
          cart ! ShoppingCart.AdjustItemQuantity("foo", 43, probe.ref)
          probe.expectMessage(ShoppingCart.Accepted(ShoppingCart.Summary(Map("foo" -> 43), checkedOut = false)))
        }

        "checkout" in {
          val cart = testKit.spawn(ShoppingCart(newCartId(), Set.empty))
          val probe = testKit.createTestProbe[ShoppingCart.Confirmation]
          cart ! ShoppingCart.AddItem("foo", 42, probe.ref)
          probe.expectMessageType[ShoppingCart.Accepted]
          cart ! ShoppingCart.Checkout(probe.ref)
          probe.expectMessage(ShoppingCart.Accepted(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = true)))

          cart ! ShoppingCart.AddItem("bar", 13, probe.ref)
          probe.expectMessageType[ShoppingCart.Rejected]
        }

        "keep its state" in {
          val cartId = newCartId()
          val cart = testKit.spawn(ShoppingCart(cartId, Set.empty))
          val probe = testKit.createTestProbe[ShoppingCart.Confirmation]
          cart ! ShoppingCart.AddItem("foo", 42, probe.ref)
          probe.expectMessage(ShoppingCart.Accepted(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = false)))

          testKit.stop(cart)

          // start again with same cartId
          val restartedCart = testKit.spawn(ShoppingCart(cartId, Set.empty))
          val stateProbe = testKit.createTestProbe[ShoppingCart.Summary]
          restartedCart ! ShoppingCart.Get(stateProbe.ref)
          stateProbe.expectMessage(ShoppingCart.Summary(Map("foo" -> 42), checkedOut = false))
        }

  }



}
