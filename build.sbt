name := "jop-spinalhdl"

version := "0.1.0"

scalaVersion := "2.13.18"

// SpinalHDL dependencies
libraryDependencies ++= Seq(
  "com.github.spinalhdl" %% "spinalhdl-core" % "1.12.2",
  "com.github.spinalhdl" %% "spinalhdl-lib" % "1.12.2",
  compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % "1.12.2")
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

// Source directories (under core/spinalhdl/)
Compile / scalaSource := baseDirectory.value / "spinalhdl" / "src" / "main" / "scala"
Test / scalaSource := baseDirectory.value / "spinalhdl" / "src" / "test" / "scala"

// Include microcode-generated Scala files (JumpTableData.scala)
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated"

// Include serial microcode Scala files (SerialJumpTableData.scala)
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "serial"

// Include flash microcode Scala files (FlashJumpTableData.scala)
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "flash"

// Include FPU microcode Scala files (FpuJumpTableData.scala)
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "fpu"

// Include serial+FPU microcode Scala files (SerialFpuJumpTableData.scala)
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "serial-fpu"
