packSettings

packMain := Map("HowManyThings" -> "HowManyThings")

name := "HowManyThings"

version := "0.2"

organization := "edu.umass.cs"

scalaVersion := "2.11.4"

exportJars := true

libraryDependencies ++= Seq(
  "edu.umass.cs" %% "automan" % "0.5-SNAPSHOT",
  "com.amazonaws" % "aws-java-sdk" % "1.7.5" exclude("org.apache.httpcomponents", "httpclient"),
  "org.imgscalr" % "imgscalr-lib" % "4.2",
  "net.ettinsmoor" % "bingerator_2.10" % "0.2.2"
)

//net.virtualvoid.sbt.graph.Plugin.graphSettings
