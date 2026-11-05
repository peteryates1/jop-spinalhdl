package jop.system

import spinal.core._
import spinal.core.sim._
import jop.config._
import jop.utils.{JopFileLoader, JopSimDefaults}
import jop.sim.SimVerdict
import jop.memory.JopMemoryConfig
import java.io.PrintWriter

/**
 * Config-driven BRAM simulation.
 *
 * Takes a JopConfig, extracts boot mode paths and core config,
 * loads ROM/RAM/app, and runs the sim.
 */
object JopConfigBramSim {

  def runSimDebug(
    jopConfig: JopConfig,
    jopFilePath: String,
    maxCycles: Int = 5000,
    logFilePath: String = "build/sim-logs/config_bram_debug.log"
  ): Unit = {
    val sys = jopConfig.system
    val coreConfig = sys.coreConfig.copy(
      memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
      supersetJumpTable = sys.baseJumpTable,
      memoryStyle = Some(MemoryStyle.Generic)  // see runSim
    )
    val romData = JopFileLoader.loadMicrocodeRom(sys.romPath)
    val ramData = JopFileLoader.loadStackRam(sys.ramPath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, 256 * 1024 / 4)
    println(s"=== DEBUG: ${sys.name} useSyncRam=${coreConfig.useSyncRam.getOrElse(false)} useStackCache=${coreConfig.useStackCache} ===")

    JopSimDefaults.config
      .compile(JopCoreTestHarness(romData, ramData, mainMemData, coreConfig = Some(coreConfig)))
      .doSim { dut =>
        val log = { new java.io.File(logFilePath).getParentFile.mkdirs(); new PrintWriter(logFilePath) }
        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(5)
        var lastPc = -1
        var stuckCount = 0
        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()
          val pc = dut.io.pc.toInt
          val sp = dut.io.debugSp.toInt
          val vp = dut.io.debugVp.toInt
          val a = dut.io.aout.toLong & 0xFFFFFFFFL
          val b = dut.io.bout.toLong & 0xFFFFFFFFL
          val jfetch = dut.io.jfetch.toBoolean
          val jpc = dut.io.jpc.toInt
          val line = f"[$cycle%5d] PC=$pc%04x JPC=$jpc%04x SP=$sp%04x VP=$vp%04x A=$a%08x B=$b%08x jf=${if(jfetch) 1 else 0}"
          log.println(line)
          if (cycle < 200 || cycle % 100 == 0 || pc != lastPc) {
            // print sparse output
          }
          if (pc == lastPc) stuckCount += 1 else stuckCount = 0
          if (stuckCount == 50) {
            println(f"STUCK at cycle $cycle: PC=$pc%04x SP=$sp%04x VP=$vp%04x A=$a%08x B=$b%08x")
          }
          lastPc = pc
          if (dut.io.uartTxValid.toBoolean) {
            val ch = dut.io.uartTxData.toInt
            println(f"[$cycle%5d] UART: '${if (ch >= 32 && ch < 127) ch.toChar else '?'}' (0x$ch%02x)")
          }
        }
        log.close()
        println(s"Debug log: $logFilePath")
      }
  }

  /** Runs `jopFilePath` on a preset's core config, and exits 1 unless
    * `verdict` passes the UART output. These sims printed what they saw and
    * exited 0, so none of them could fail (item 176). */
  def runSim(
    jopConfig: JopConfig,
    jopFilePath: String,
    maxCycles: Int = 2000000,
    logFilePath: String = "build/sim-logs/config_bram_simulation.log",
    verdict: String => Either[String, String] = SimVerdict.helloWorld,
    memBytes: Int = 256 * 1024
  ): Unit = {
    val sys = jopConfig.system
    val coreConfig = sys.coreConfig.copy(
      memConfig = JopMemoryConfig(mainMemSize = memBytes),
      supersetJumpTable = sys.baseJumpTable,
      // `jopConfig.system` is the RESOLVED system, and resolution picks the
      // FPGA's memory style: AlteraLpm on every EP4CGX150 preset,
      // `JopConfig.simulation` among them -- lpm_rom/lpm_ram_dp blackboxes only
      // Quartus supplies. Verilator stopped on 'rom', and no sim here had built
      // since 69b7aa7 (item 176). The vendor style is a synthesis choice; a
      // simulation infers its memories.
      memoryStyle = Some(MemoryStyle.Generic)
    )

    val romData = JopFileLoader.loadMicrocodeRom(sys.romPath)
    val ramData = JopFileLoader.loadStackRam(sys.ramPath)
    val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memBytes / 4)

    println(s"=== ${sys.name} (${sys.bootMode}) ===")
    println(s"  ROM: ${sys.romPath} (${romData.length} entries)")
    println(s"  RAM: ${sys.ramPath} (${ramData.length} entries)")
    println(s"  App: $jopFilePath (${mainMemData.length} words)")
    println(s"  imul=${coreConfig.impl("imul")}, idiv=${coreConfig.impl("idiv")}, irem=${coreConfig.impl("irem")}")
    println(s"  needsIntegerCompute=${coreConfig.needsIntegerCompute}, needsFloatCompute=${coreConfig.needsFloatCompute}")

    JopSimDefaults.config
      .compile(JopCoreTestHarness(romData, ramData, mainMemData, coreConfig = Some(coreConfig)))
      .doSim { dut =>
        val log = { new java.io.File(logFilePath).getParentFile.mkdirs(); new PrintWriter(logFilePath) }
        var uartOutput = new StringBuilder
        var lineBuffer = new StringBuilder

        def logLine(msg: String): Unit = {
          log.println(msg)
          log.flush()
        }

        logLine(s"=== ${sys.name} BRAM Simulation ===")
        logLine(s"Config: imul=${coreConfig.impl("imul")}, idiv=${coreConfig.impl("idiv")}, irem=${coreConfig.impl("irem")}")

        dut.clockDomain.forkStimulus(10)
        dut.clockDomain.waitSampling(5)

        val reportInterval = 100000

        for (cycle <- 0 until maxCycles) {
          dut.clockDomain.waitSampling()

          if (dut.io.uartTxValid.toBoolean) {
            val char = dut.io.uartTxData.toInt
            if (char == 10) {
              val line = lineBuffer.toString
              println(line)
              logLine(f"[$cycle%7d] $line")
              lineBuffer.clear()
            } else if (char >= 32 && char < 127) {
              lineBuffer.append(char.toChar)
            }
            uartOutput.append(if (char >= 32 && char < 127) char.toChar else '\n')
          }

          if (cycle > 0 && cycle % reportInterval == 0) {
            println(f"  [$cycle%7d cycles]")
          }
        }

        if (lineBuffer.nonEmpty) {
          println(lineBuffer.toString)
          logLine(f"[$maxCycles%7d] ${lineBuffer.toString}")
        }

        logLine("")
        logLine(s"=== Simulation Complete ($maxCycles cycles) ===")
        logLine(s"UART Output:\n${uartOutput.toString}")
        log.close()

        println(s"\n=== Simulation Complete ($maxCycles cycles) ===")
        println(s"Full output:\n${uartOutput.toString}")
        println(s"Log: $logFilePath")

        verdict(uartOutput.toString) match {
          case Left(why) =>
            println(s"FAIL: $why")
            System.exit(1)
          case Right(what) =>
            println(s"PASS: $what")
        }
      }
  }
}

/** HelloWorld on `JopConfig.simulation` as it stands: idiv and irem in hardware
  * (the IntegerComputeUnit), imul in microcode. JopConfigHwMathHelloWorldSim
  * asked for idiv/irem in hardware on top of this, got this same core, and was
  * retired (item 176). */
object JopConfigHelloWorldSim extends App {
  JopConfigBramSim.runSim(
    jopConfig = JopConfig.simulation,
    jopFilePath = jop.utils.SimApp.jop("Smallest", "HelloWorld")
  )
}

/** HelloWorld with the stack cache (useSyncRam + useStackCache, as the DDR3
  * FPGA config builds it). JopConfigCacheOnlyHelloWorldSim asked for the cache
  * with async reads, but resolution sets useSyncRam on every preset that leaves
  * it unset, so it built this same core; retired (item 176). */
object JopConfigSyncCacheHelloWorldSim extends App {
  val base = JopConfig.simulation
  val syncCache = base.copy(systems = Seq(base.system.copy(
    name = "synccache-sim",
    coreConfig = base.system.coreConfig.copy(
      useSyncRam = Some(true), useStackCache = true,
      spillBaseAddrOverride = Some(0)))))
  JopConfigBramSim.runSim(
    jopConfig = syncCache,
    jopFilePath = jop.utils.SimApp.jop("Smallest", "HelloWorld")
  )
}

/** HelloWorld with full hardware float + integer (FloatComputeUnit + IntegerComputeUnit) */
object JopConfigHwFloatHelloWorldSim extends App {
  val base = JopConfig.simulation
  val hwFloat = base.copy(systems = Seq(base.system.copy(
    name = "hwfloat-sim",
    coreConfig = base.system.coreConfig.copy(
      bytecodes = Map("idiv" -> "hw", "irem" -> "hw", "float" -> "hw")))))
  JopConfigBramSim.runSim(
    jopConfig = hwFloat,
    jopFilePath = jop.utils.SimApp.jop("Smallest", "HelloWorld")
  )
}

/**
 * JVM test suite runner for any JopConfig preset.
 *
 * Takes a preset name, overrides boot mode to Simulation (so ROM/RAM
 * load from asm/generated/ and jump table uses simulation variant),
 * and runs the full DoAll.jop test suite.
 *
 * Usage:
 *   sbt "Test / runMain jop.system.JopConfigJvmTestsSim wukongFull"
 *   sbt "Test / runMain jop.system.JopConfigJvmTestsSim ep4cgx150HwFloat"
 */
object JopConfigJvmTestsSim {
  def main(args: Array[String]): Unit = {
    val presetName = args.headOption.getOrElse("simulation")
    // JopTopVerilog resolves the FPGA presets, and `simulation` is not one: the
    // default named it, and died "Unknown preset: 'simulation'" before
    // simulating anything, from db447d8 until item 176 ran it.
    val preset = args.headOption.map(JopTopVerilog.resolvePreset(_, args))
      .getOrElse(JopConfig.simulation)

    // Override to simulation boot mode (loads ROM/RAM from asm/generated/)
    val simConfig = preset.copy(systems = Seq(preset.system.copy(
      bootMode = BootMode.Simulation)))

    println(s"=== JVM Tests with preset: $presetName ===")
    val cc = simConfig.system.coreConfig
    println(s"  imul=${cc.impl("imul")}, idiv=${cc.impl("idiv")}, irem=${cc.impl("irem")}")
    println(s"  fadd=${cc.impl("fadd")}, fmul=${cc.impl("fmul")}, fdiv=${cc.impl("fdiv")}")
    println(s"  fneg=${cc.impl("fneg")}, i2f=${cc.impl("i2f")}, f2i=${cc.impl("f2i")}, fcmpl=${cc.impl("fcmpl")}")

    JopConfigBramSim.runSim(
      jopConfig = simConfig,
      jopFilePath = jop.utils.SimApp.jop("JvmTests", "DoAll"),
      maxCycles = 60000000,
      logFilePath = s"build/sim-logs/jvmtests_${presetName}_simulation.log",
      verdict = SimVerdict.doAll,
      // DoAll is 75,005 words, past runSim's 256 KB default, which truncated
      // it in silence until the loader learned to refuse (item 176). 512 KB
      // is what the JVM-suite sims CI runs give it.
      memBytes = 512 * 1024
    )
  }
}
