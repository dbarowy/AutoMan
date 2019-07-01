// METADATA
name := "automan"

version := "1.2.1-SNAPSHOT"

organization := "edu.umass.cs"

licenses := Seq("GPL-2.0" -> url("http://opensource.org/licenses/GPL-2.0"))

homepage := Some(url("http://github.com/dbarowy/AutoMan"))

scalaVersion := "2.11.7"

exportJars := true

// SUPPORTED SCALA VERSIONS
crossScalaVersions := Seq("2.11.7")

// REQUIRE JAVA 1.8
javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

initialize := {
  val _ = initialize.value
  if (sys.props("java.specification.version") != "1.8")
    sys.error("Java 8 is required for this project.")
}

// DEPENDENCIES
libraryDependencies := {
  val akkaVer   = "2.3.7"
  val sprayVer  = "1.3.2"
  Seq(
    "org.scalatest"              %%  "scalatest"            % "2.2.1"    % "test",
    "commons-codec"              % "commons-codec"          % "1.4",
    "log4j"                      % "log4j"                  % "1.2.17",
    "net.ettinsmoor"         % "java-aws-mturk"         % "1.6.2"
      exclude("org.apache.commons","not-yet-commons-ssl")
      exclude("apache-xerces","xercesImpl")
      exclude("apache-xerces","resolver")
      exclude("apache-xerces","xml-apis")
      exclude("velocity","velocity")
      exclude("org.apache.velocity","velocity")
      exclude("commons-beanutils","commons-beanutils"),
    "ca.juliusdavies"            % "not-yet-commons-ssl"    % "0.3.11",
    "xerces"                     % "xercesImpl"             % "2.9.1",
    "org.apache.velocity"        % "velocity"               % "1.6.2",
    "commons-beanutils"          % "commons-beanutils-core" % "1.7.0",
    "org.specs2"                 %%  "specs2-core"          % "2.3.11" % "test",
    "com.typesafe.slick"         %% "slick"                 % "2.1.0",
    "com.h2database"             % "h2"                     % "1.4.189",
    "org.slf4j"                  % "slf4j-nop"              % "1.6.4"
  )
}

// add scala-xml if scala major version >= 11
libraryDependencies := {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, scalaMajor)) if scalaMajor >= 11 =>
      libraryDependencies.value :+ "org.scala-lang.modules" %% "scala-xml" % "1.0.2"
    case _ =>
      libraryDependencies.value
  }
}

// CUSTOM TASKS
val memoClean = TaskKey[Unit]("memo-clean", "Deletes AutoMan memo database files.")

memoClean := {
  val dbs = Seq.concat(
    (baseDirectory.value ** "*.mv.db").get,
    (baseDirectory.value ** "*.trace.db").get
  )
  dbs.foreach { f: File =>
    println(s"Deleting: ${f.getName}")
    f.delete()
  }
}

val gitHash = TaskKey[String]("githash", "Gets the git hash of HEAD.")

gitHash := {
  import scala.sys.process._
  ("git rev-parse HEAD" !!).replace("\n","")
}

val hashAsConstant = TaskKey[Unit]("hash-as-constant", "Creates a Scala source file containing the git hash for HEAD.")

hashAsConstant := {
  import java.io._

  val path = "src/main/scala/edu/umass/cs/automan/core/util/GitHash.scala"

  val clazz =
    "package edu.umass.cs.automan.core.util\n\n" +
    "object GitHash {\n" +
    "  val value = \"" + gitHash.value + "\"\n" +
    "}"

  val pw = new PrintWriter(new File(path))
  pw.write(clazz)
  pw.close()
}

// TEST CONFIGURATION
parallelExecution in Test := false

// MODIFY BUILD
compile := ((compile in Compile) dependsOn hashAsConstant).value

// GENERATING JAR
enablePlugins(PackPlugin)

// MAVEN

// yes, we want Maven artifacts
publishMavenStyle := true

// specify repository
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

// don't publish test artifacts
publishArtifact in Test := false

// don't include optional repositories
pomIncludeRepository := { _ => false }

// POM body
pomExtra := (
  <scm>
    <url>git@github.com:dbarowy/AutoMan.git</url>
    <connection>scm:git:git@github.com:dbarowy/AutoMan.git</connection>
  </scm>
  <developers>
    <developer>
      <id>dbarowy</id>
      <name>Daniel Barowy</name>
      <url>http://people.cs.umass.edu/~dbarowy</url>
    </developer>
  </developers>)
