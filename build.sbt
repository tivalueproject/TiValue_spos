import com.typesafe.config.ConfigFactory

organization := "com.wavesplatform"

val appConf = ConfigFactory.parseFile(new File("src/main/resources/application.conf")).resolve().getConfig("app")

name := "waves"

version := appConf.getString("version")

scalaVersion := "2.11.8"

resolvers += "SonaType" at "https://oss.sonatype.org/content/groups/public"

val modulesVersion = "1.5.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.wavesplatform" %% "scorex-basics" % modulesVersion,
  "com.wavesplatform" %% "scorex-consensus" % modulesVersion,
  "com.wavesplatform" %% "scorex-transaction" % modulesVersion,
  "io.spray" %% "spray-testkit" % "1.+" % "test",
  "org.scalatest" %% "scalatest" % "2.+" % "test",
  "org.scalactic" %% "scalactic" % "2.+" % "test",
  "org.scalacheck" %% "scalacheck" % "1.12.+" % "test",
  "net.databinder.dispatch" %% "dispatch-core" % "+" % "test",
  "org.scalamock" %% "scalamock-scalatest-support" % "3.2.2" % "test"
)

val akkaV       = "2.+"
lazy val addAkkaLibs = Seq(
  "com.typesafe.akka" %% "akka-persistence" % akkaV,
  "com.github.dnvriend" %% "akka-persistence-inmemory" % "1.3.15" % "test",
  "com.typesafe.akka" %% "akka-http-testkit" % akkaV,
  "org.iq80.leveldb"            % "leveldb"          % "0.7",
  "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8",
  "com.github.romix.akka" %% "akka-kryo-serialization" % "0.4.1",
  "com.twitter" %% "storehaus-cache_2.11" %  "0.13.0"
)

libraryDependencies ++= addAkkaLibs


//assembly settings
assemblyJarName in assembly := "waves.jar"

test in assembly := {}

fork in ThisBuild := true
fork in Test := false
parallelExecution in ThisBuild := false

mainClass in assembly := Some("com.wavesplatform.Application")

assemblyMergeStrategy in assembly := {
  case "application.conf" => MergeStrategy.concat
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

javaOptions in Universal ++= Seq(
  "-J-server",
  // JVM memory tuning for 1g ram
  "-J-Xms128m",
  "-J-Xmx1024m",

  // from https://groups.google.com/d/msg/akka-user/9s4Yl7aEz3E/zfxmdc0cGQAJ
  "-J-XX:+UseG1GC",
  "-J-XX:+UseNUMA",
  "-J-XX:+AlwaysPreTouch",

  // may be can't use with jstack and others tools
  "-J-XX:+PerfDisableSharedMem",
  "-J-XX:+ParallelRefProcEnabled",
  "-J-XX:+UseStringDeduplication",

  "-J-Dsun.net.inetaddr.ttl=60"
)
