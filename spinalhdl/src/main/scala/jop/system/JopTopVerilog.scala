package jop.system

import spinal.core._
import spinal.lib.io.InOutWrapper
import jop.config._
import jop.utils.JopFileLoader

/**
 * Unified Verilog generation entry point.
 *
 * Resolves a preset name to a JopConfig, loads ROM/RAM, and generates Verilog
 * via JopTop. Entity name is automatically set from JopConfig.entityName.
 *
 * Usage:
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150Bram"
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150BramSerial"
 *   sbt "runMain jop.system.JopTopVerilog cyc5000Serial"
 *   sbt "runMain jop.system.JopTopVerilog auSerial"
 *   sbt "runMain jop.system.JopTopVerilog wukongSdram"
 *   sbt "runMain jop.system.JopTopVerilog wukongDdr3"
 *   sbt "runMain jop.system.JopTopVerilog ep4cgx150Smp 8"
 */
object JopTopVerilog {

  /** Resolve a preset name to a JopConfig */
  def resolvePreset(name: String, args: Array[String] = Array.empty): JopConfig = name match {
    case "ep4cgx150Serial"     => JopConfig.ep4cgx150Serial
    case "ep4cgx150Bram"       => JopConfig.ep4cgx150Bram
    case "ep4cgx150BramGc"     => JopConfig.ep4cgx150BramGc
    case "ep4cgx150BramSerial" => JopConfig.ep4cgx150BramSerial
    case "ep4cgx150HwMath"     => JopConfig.ep4cgx150HwMath
    case "ep4cgx150HwFloat"    => JopConfig.ep4cgx150HwFloat
    case "ep4cgx150Smp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.ep4cgx150Smp(n)
    case "cyc5000Serial"    => JopConfig.cyc5000Serial
    case "cyc5000Smp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.cyc5000Smp(n)
    case "auSerial"         => JopConfig.auSerial
    case "wukongSdram"      => JopConfig.wukongSdram
    case "wukongSdrAllCu"   => JopConfig.wukongSdrAllCu
    case "wukongDdr3"       => JopConfig.wukongDdr3
    case "wukongDdr3AllCu"  => JopConfig.wukongDdr3AllCu
    case "wukongBram"       => JopConfig.wukongBram
    case "wukongFull"       => JopConfig.wukongFull
    case "wukongSdrFull"    => JopConfig.wukongSdrFull
    case "wukongFullSmp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.wukongFullSmp(n)
    case "wukongSmp" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.wukongSmp(n)
    case "wukongSmpMinimal" =>
      val n = args.drop(1).headOption.map(_.toInt).getOrElse(2)
      JopConfig.wukongSmpMinimal(n)
    case "wukongNoDcu"      => JopConfig.wukongNoDcu
    case "wukongDdr3DspMul" => JopConfig.wukongDdr3DspMul
    case "wukongDdr3Fcu"    => JopConfig.wukongDdr3Fcu
    case "wukongDdr3Lcu"    => JopConfig.wukongDdr3Lcu
    case "minimum"          => JopConfig.minimum
    case "max1000Sdram"     => JopConfig.max1000Sdram
    case "ep4ce6Sdram"      => JopConfig.ep4ce6Sdram
    case other =>
      throw new RuntimeException(s"Unknown preset: '$other'. Available: " +
        "ep4cgx150Serial, ep4cgx150Bram, ep4cgx150BramGc, ep4cgx150BramSerial, " +
        "ep4cgx150HwMath, ep4cgx150HwFloat, ep4cgx150Smp, " +
        "cyc5000Serial, auSerial, wukongSdram, wukongDdr3, wukongBram, " +
        "wukongFull, wukongSdrFull, wukongFullSmp, wukongSmp, minimum, " +
        "max1000Sdram, ep4ce6Sdram")
  }

  /** Generate Verilog from a JopConfig */
  def generate(
    jopConfig: JopConfig,
    jopFilePath: Option[String] = None
  ): Unit = {
    val sys = jopConfig.system
    val isBram = !jopConfig.resolveMemory(sys).isDefined
    val bramSize = sys.coreConfig.memConfig.mainMemSize.toInt

    val romData = JopFileLoader.loadMicrocodeRom(sys.romPath)
    val ramData = JopFileLoader.loadStackRam(sys.ramPath)

    // BRAM with pre-initialized memory (not serial boot — serial fills at runtime)
    val mainMemInit = if (isBram && sys.bootMode != BootMode.Serial) {
      val path = jopFilePath.getOrElse("java/apps/Smallest/HelloWorld.jop")
      val data = JopFileLoader.jopFileToMemoryInit(path, bramSize / 4)
      println(s"  BRAM init: $path (${data.length} words, ${bramSize / 1024}KB)")
      Some(data)
    } else None

    println(s"=== JopTop Verilog Generation ===")
    println(s"  Entity: ${jopConfig.entityName}")
    println(s"  Board:  ${jopConfig.assembly.fpgaBoard.name}")
    println(s"  FPGA:   ${jopConfig.fpga.name}")
    println(s"  Memory: ${sys.memory}")
    println(s"  ROM:    ${sys.romPath} (${romData.length} entries)")
    println(s"  RAM:    ${sys.ramPath} (${ramData.length} entries)")
    println(s"  Cores:  ${sys.cpuCnt}, Clock: ${sys.clkFreq}")

    val spinalConfig = JopSpinalConfig(jopConfig)

    spinalConfig.generate(InOutWrapper(JopTop(
      config = jopConfig,
      romInit = romData,
      ramInit = ramData,
      mainMemInit = mainMemInit,
      mainMemSize = if (isBram) bramSize else 64 * 1024
    )))

    println(s"Generated: spinalhdl/generated/${jopConfig.entityName}.v")
  }

  def main(args: Array[String]): Unit = {
    val preset = args.headOption.getOrElse("ep4cgx150Serial")
    val config = resolvePreset(preset, args)
    val jopFile = preset match {
      case "ep4cgx150BramGc" => Some("java/apps/Small/HelloWorld.jop")
      case _ => None
    }
    generate(config, jopFilePath = jopFile)
  }
}
