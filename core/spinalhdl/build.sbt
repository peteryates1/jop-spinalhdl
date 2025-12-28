name := "jop-spinalhdl"

version := "0.1.0"

scalaVersion := "2.13.12"

// SpinalHDL dependencies
libraryDependencies ++= Seq(
  "com.github.spinalhdl" %% "spinalhdl-core" % "1.10.1",
  "com.github.spinalhdl" %% "spinalhdl-lib" % "1.10.1",
  compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % "1.10.1")
)

// Testing dependencies
libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.17" % Test,
  "io.circe" %% "circe-core" % "0.14.6" % Test,
  "io.circe" %% "circe-generic" % "0.14.6" % Test,
  "io.circe" %% "circe-parser" % "0.14.6" % Test
)

// Scala compiler options
scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-language:reflectiveCalls"
)

// Fork JVM for run
fork := true

// Test configuration
Test / parallelExecution := false
Test / testOptions += Tests.Argument("-oD")  // Show test durations

// Ensure generated directory exists
Compile / unmanagedSourceDirectories += baseDirectory.value / "generated"

// Source directories
Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala"
Test / scalaSource := baseDirectory.value / "src" / "test" / "scala"
