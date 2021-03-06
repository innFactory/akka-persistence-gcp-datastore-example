package sample.cqrs

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.eventstream.EventStream
import akka.cluster.MemberStatus
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.typed.{Cluster, Join}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest}
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}
import org.scalatest.time.Span
import org.scalatest.{BeforeAndAfterAll, Matchers, TestSuite, WordSpecLike}

import scala.concurrent.duration._

object IntegrationSpec {
  val config: Config = ConfigFactory.parseString(s"""
      akka.cluster {
         seed-nodes = []
      }
      event-processor {
        keep-alive-interval = 1 seconds
      }
      akka.actor.testkit.typed.single-expect-default = 5s
      # For LoggingTestKit
      akka.actor.testkit.typed.filter-leeway = 5s
    """).withFallback(ConfigFactory.load())
}

class IntegrationSpec
    extends TestSuite
    with Matchers
    with BeforeAndAfterAll
    with WordSpecLike
    with ScalaFutures
    with Eventually {

  implicit private val patience: PatienceConfig =
    PatienceConfig(3.seconds, Span(100, org.scalatest.time.Millis))

  private def roleConfig(role: String): Config =
    ConfigFactory.parseString(s"akka.cluster.roles = [$role]")

  // one TestKit (ActorSystem) per cluster node
  private val testKit1 = ActorTestKit("IntegrationSpec", roleConfig("write-model").withFallback(IntegrationSpec.config))
  private val testKit2 =
    ActorTestKit("IntegrationSpec", roleConfig("write-model").withFallback(IntegrationSpec.config))
  private val testKit3 = ActorTestKit("IntegrationSpec", roleConfig("read-model").withFallback(IntegrationSpec.config))
  private val testKit4 = ActorTestKit("IntegrationSpec", roleConfig("read-model").withFallback(IntegrationSpec.config))

  private val systems3 = List(testKit1.system, testKit2.system, testKit3.system)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val request = HttpRequest(uri = "http://localhost:8081/reset", method = HttpMethods.POST)
    val http    = Http(testKit1.system)
    http.singleRequest(
      request
    )
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    val request = HttpRequest(uri = "http://localhost:8081/reset", method = HttpMethods.POST)
    val http    = Http(testKit1.system)
    http.singleRequest(
      request
    )
    testKit4.shutdownTestKit()
    testKit3.shutdownTestKit()
    testKit2.shutdownTestKit()
    testKit1.shutdownTestKit()
  }

  "Shopping Cart application" should {

    "init and join Cluster" in {
      testKit1.spawn[Nothing](Guardian(), "guardian")
      testKit2.spawn[Nothing](Guardian(), "guardian")
      testKit3.spawn[Nothing](Guardian(), "guardian")
      // node4 is initialized and joining later

      systems3.foreach(sys => Cluster(sys).manager ! Join(Cluster(testKit1.system).selfMember.address))

      // let the nodes join and become Up
      eventually(PatienceConfiguration.Timeout(10.seconds)) {
        systems3.foreach(sys => Cluster(sys).selfMember.status should ===(MemberStatus.Up))
      }
    }

    "update and consume from different nodes" in {
      val cart1  = ClusterSharding(testKit1.system).entityRefFor(ShoppingCart.EntityKey, "cart-1")
      val probe1 = testKit1.createTestProbe[ShoppingCart.Confirmation]

      val cart2  = ClusterSharding(testKit2.system).entityRefFor(ShoppingCart.EntityKey, "cart-2")
      val probe2 = testKit2.createTestProbe[ShoppingCart.Confirmation]

      val eventProbe3 = testKit3.createTestProbe[ShoppingCart.Event]()
      testKit3.system.eventStream ! EventStream.Subscribe(eventProbe3.ref)

      // update from node1, consume event from node3
      cart1 ! ShoppingCart.AddItem("foo", 42, probe1.ref)
      probe1.expectMessageType[ShoppingCart.Accepted]
      eventProbe3.expectMessage(ShoppingCart.ItemAdded("cart-1", "foo", 42))

      // update from node2, consume event from node3
      cart2 ! ShoppingCart.AddItem("bar", 17, probe2.ref)
      probe2.expectMessageType[ShoppingCart.Accepted]
      cart2 ! ShoppingCart.AdjustItemQuantity("bar", 18, probe2.ref)
      probe2.expectMessageType[ShoppingCart.Accepted]
      eventProbe3.expectMessage(ShoppingCart.ItemAdded("cart-2", "bar", 17))
      eventProbe3.expectMessage(ShoppingCart.ItemQuantityAdjusted("cart-2", "bar", 18))
    }

    "continue even processing from offset" in {
      testKit3.shutdownTestKit()

      testKit4.spawn[Nothing](Guardian(), "guardian")

      Cluster(testKit4.system).manager ! Join(Cluster(testKit1.system).selfMember.address)

      // let the node join and become Up
      eventually(PatienceConfiguration.Timeout(10.seconds)) {
        Cluster(testKit4.system).selfMember.status should ===(MemberStatus.Up)
      }

      val cart3  = ClusterSharding(testKit1.system).entityRefFor(ShoppingCart.EntityKey, "cart-3")
      val probe3 = testKit1.createTestProbe[ShoppingCart.Confirmation]

      val eventProbe4 = testKit4.createTestProbe[ShoppingCart.Event]()
      testKit4.system.eventStream ! EventStream.Subscribe(eventProbe4.ref)

      // update from node1, consume event from node4
      cart3 ! ShoppingCart.AddItem("abc", 43, probe3.ref)
      probe3.expectMessageType[ShoppingCart.Accepted]
      // note that node4 is new, but continues reading from previous offset, i.e. not receiving events
      // that have already been consumed
      eventProbe4.receiveMessages(4)
    }

  }
}
