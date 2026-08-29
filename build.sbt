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

// Verilator's generated model is built and stepped on the calling thread, and
// the C++ it emits nests roughly with the size of the widest combinational
// cone. Past a certain design size the default 1 MB thread stack overflows
// while the model is being constructed, which surfaces as a bare SIGSEGV —
// "nonzero exit code returned from runner: 139", no message, no Java trace, and
// it looks exactly like an RTL bug.
//
// Concretely: LruCacheCore at 512 sets x 4 ways keeps 2048 valid bits in
// registers with dynamic-index write decoders, and that configuration
// segfaulted at construction while 512 x 2 (1024 bits) ran fine. Nothing to do
// with X-state (--x-initial 0 does not help) or with simulation state — it
// crashed with a 5-completion target too.
javaOptions ++= Seq("-Xss512m")
Test / javaOptions ++= Seq("-Xss512m")

// Pass DISPLAY for AWT-based simulation (SimDisplay)
run / envVars ++= sys.env.get("DISPLAY").map("DISPLAY" -> _).toMap
Test / envVars ++= sys.env.get("DISPLAY").map("DISPLAY" -> _).toMap

// Test configuration
Test / parallelExecution := false
Test / testOptions += Tests.Argument("-oD")  // Show test durations

// Source directories (under core/spinalhdl/)
Compile / scalaSource := baseDirectory.value / "spinalhdl" / "src" / "main" / "scala"
Test / scalaSource := baseDirectory.value / "spinalhdl" / "src" / "test" / "scala"
// The sources are relocated under spinalhdl/, so the RESOURCES have to be too --
// otherwise sbt looks in <root>/src/main/resources and a generator asset put
// beside its code is silently not on the classpath. DramPllGen's altpll
// template lives here.
Compile / resourceDirectory := baseDirectory.value / "spinalhdl" / "src" / "main" / "resources"
Test / resourceDirectory := baseDirectory.value / "spinalhdl" / "src" / "test" / "resources"

// Microcode-generated Scala files. Keep in step with jop.config.MicrocodePaths.
//
// THREE boot modes, and only three: asm/Makefile has no other targets. Four more
// directories were listed here -- dsp, serial-dsp, hwmath, serial-hwmath -- and
// none of them has ever existed. sbt ignores a missing source directory, so the
// dead entries cost nothing and said nothing; they are removed rather than left
// to imply variants that are not built.
Compile / unmanagedSourceDirectories += baseDirectory.value / "build" / "microcode" / "simulation" // JumpTableData
Compile / unmanagedSourceDirectories += baseDirectory.value / "build" / "microcode" / "serial"     // SerialJumpTableData
Compile / unmanagedSourceDirectories += baseDirectory.value / "build" / "microcode" / "flash"      // FlashJumpTableData
