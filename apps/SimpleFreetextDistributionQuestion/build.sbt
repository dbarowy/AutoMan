enablePlugins(PackPlugin)

name := "SimpleFreetextDistributionQuestion"

version := "1.1"

scalaVersion := "2.12.12"

exportJars := true

libraryDependencies ++= Seq(
  "org.automanlang" %% "automan" % "1.4.0"
)
