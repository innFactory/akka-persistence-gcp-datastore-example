package sample.cqrs

import java.io.File
import java.util.concurrent.CountDownLatch

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.typed.Cluster
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.persistence.datastore.journal.read.DatastoreScaladslReadJournal
import akka.persistence.query.PersistenceQuery
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object Main {

  def main(args: Array[String]): Unit = {
    args.headOption match {

      case Some(portString) if portString.matches("""\d+""") =>
        val port = portString.toInt
        val httpPort = ("80" + portString.takeRight(2)).toInt
        startNode(port, httpPort)

      case Some("cassandra") =>
        println("Started Cassandra, press Ctrl + C to kill")
        new CountDownLatch(1).await()

      case None =>
        throw new IllegalArgumentException("port number, or cassandra required argument")
    }
  }

  def startNode(port: Int, httpPort: Int): Unit = {
    val system = ActorSystem[Nothing](Guardian(), "Shopping", config(port, httpPort))

  }

  def config(port: Int, httpPort: Int): Config =
    ConfigFactory.parseString(s"""
      akka.remote.artery.canonical.port = $port
      shopping.http.port = $httpPort
       """).withFallback(ConfigFactory.load())




}

object Guardian {
  def apply(): Behavior[Nothing] = {
    Behaviors.setup[Nothing] { context =>
      val system = context.system
      val settings = EventProcessorSettings(system)
      val httpPort = context.system.settings.config.getInt("shopping.http.port")

      ShoppingCart.init(system, settings)

      if (Cluster(system).selfMember.hasRole("read-model")) {
        EventProcessor.init(
          system,
          settings,
          tag => new ShoppingCartEventProcessorStream(system, system.executionContext, settings.id, tag))
      }

      val routes = new ShoppingCartRoutes()(context.system)
      new ShoppingCartServer(routes.shopping, httpPort, context.system).start()

      Behaviors.empty
    }
  }
}
