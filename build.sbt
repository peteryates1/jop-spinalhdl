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

// Pass DISPLAY for AWT-based simulation (SimDisplay)
run / envVars ++= sys.env.get("DISPLAY").map("DISPLAY" -> _).toMap
Test / envVars ++= sys.env.get("DISPLAY").map("DISPLAY" -> _).toMap

// Test configuration
Test / parallelExecution := false
Test / testOptions += Tests.Argument("-oD")  // Show test durations

// Source directories (under core/spinalhdl/)
Compile / scalaSource := baseDirectory.value / "spinalhdl" / "src" / "main" / "scala"
Test / scalaSource := baseDirectory.value / "spinalhdl" / "src" / "test" / "scala"

// Microcode-generated Scala files (superset ROMs — 3 boot modes)
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated"           // JumpTableData (simulation)
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "serial" // SerialJumpTableData
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "flash"  // FlashJumpTableData
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "dsp"          // DspJumpTableData
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "serial-dsp"   // SerialDspJumpTableData
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "hwmath"       // HwMathJumpTableData
Compile / unmanagedSourceDirectories += baseDirectory.value / "asm" / "generated" / "serial-hwmath" // SerialHwMathJumpTableData
