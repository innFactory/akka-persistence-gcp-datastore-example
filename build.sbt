val AkkaVersion                     = "2.6.3"
val AkkaPersistenceCassandraVersion = "0.100"
val AkkaHttpVersion                 = "10.1.10"
val GCPPersistencePlugin = "1.0.1"

lazy val `akka-sample-cqrs-scala` = project
  .in(file("."))
  .settings(
    organization := "com.lightbend.akka.samples",
    version := "1.0",
    scalaVersion := "2.13.1",
    scalacOptions in Compile ++= Seq("-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint", "-Ywarn-unused"),
    javacOptions in Compile ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"),
    libraryDependencies ++= Seq(
      "com.typesafe.akka"    %% "akka-cluster-sharding-typed"         % AkkaVersion,
      "com.typesafe.akka"    %% "akka-persistence-typed"              % AkkaVersion,
      "com.typesafe.akka"    %% "akka-persistence-query"              % AkkaVersion,
      "com.typesafe.akka"    %% "akka-serialization-jackson"          % AkkaVersion,
      "com.typesafe.akka"    %% "akka-persistence-cassandra"          % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka"    %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,
      "com.typesafe.akka"    %% "akka-http"                           % AkkaHttpVersion,
      "com.typesafe.akka"    %% "akka-http-spray-json"                % AkkaHttpVersion,
      "com.typesafe.akka"    %% "akka-slf4j"                          % AkkaVersion,
      "ch.qos.logback"       % "logback-classic"                      % "1.2.3",
      "de.innfactory"        %% "akka-persistence-gcp-datastore"      % GCPPersistencePlugin,
      "commons-io"           % "commons-io"                           % "2.4" % Test,
      "com.typesafe.akka"    %% "akka-actor-testkit-typed"            % AkkaVersion % Test,
      "org.scalatest"        %% "scalatest"                           % "3.0.8" % Test,
      "org.pegdown"          % "pegdown"                              % "1.6.0" % Test,
      "com.vladsch.flexmark" % "flexmark-all"                         % "0.35.10" % Test
    ),
    scalafixDependencies in ThisBuild += "org.scalatest" %% "autofix" % "3.1.0.0",
    addCompilerPlugin(scalafixSemanticdb), // enable SemanticDB
    fork in run := false,
    Global / cancelable := false, // ctrl-c
    mainClass in (Compile, run) := Some("sample.cqrs.Main"),
    // disable parallel tests
    parallelExecution in Test := false,
    // show full stack traces and test case durations
    testOptions in Test += Tests.Argument("-oDF"),
    logBuffered in Test := false,
    licenses := Seq(("CC0", url("http://creativecommons.org/publicdomain/zero/1.0")))
  )

testOptions += Tests.Setup(_ => sys.props("testing") = "true")
testOptions in Test += Tests.Argument(TestFrameworks.ScalaTest, "-h", "./target/html-report")

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias(
  "check",
  "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck"
)
