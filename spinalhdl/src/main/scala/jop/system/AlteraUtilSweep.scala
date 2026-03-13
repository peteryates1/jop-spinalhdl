package jop.system

import spinal.core._
import jop.config._
import jop.config.Implementation._

/**
 * Generate Verilog for multiple EP4CGX150 SDR SDRAM configs to measure per-feature LUT cost.
 * All variants use the same entity name (JopSdramTop) — copy/rename between runs.
 *
 * Usage: sbt "runMain jop.system.AlteraUtilSweep <label>"
 *   Labels: baseline, no_icu, no_acache, icu_full, icu_dsp, fcu, lcu, dcu, all_cu,
 *           eth, sd_native, sd_spi, vga_text, vga_dma, eth_sd_native, eth_sd_spi, full,
 *           ocache_32, ocache_64, ocache_16f, acache_32, acache_8e, mcache_32b
 */
object AlteraUtilSweep extends App {

  // Base: EP4CGX150 + SDR SDRAM + UART only (idiv/irem HW = default ICU)
  val base = JopConfig(
    assembly = SystemAssembly.qmtechWithDb,
    systems = Seq(JopSystem(
      name = "main",
      memory = "W9825G6JH6",
      bootMode = BootMode.Serial,
      clkFreq = 80 MHz,
      devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))

  val baseCc = base.system.coreConfig

  def withCc(cc: JopCoreConfig): JopConfig =
    base.copy(systems = Seq(base.system.copy(coreConfig = cc)))

  def withDevices(devs: Map[String, DeviceInstance]): JopConfig =
    base.copy(systems = Seq(base.system.copy(devices = devs)))

  def withCcAndDevices(cc: JopCoreConfig, devs: Map[String, DeviceInstance]): JopConfig =
    base.copy(systems = Seq(base.system.copy(coreConfig = cc, devices = devs)))

  // Common device instances for QMTECH daughter board
  private val uartDev = "uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))
  private val ethDev = "eth" -> DeviceInstance("ethernet",
    params = Map("gmii" -> true, "phyDataWidth" -> 8),
    devicePart = Some("RTL8211EG"))
  private val sdNativeDev = "sdNative" -> DeviceInstance("sdnative",
    devicePart = Some("SD_CARD"))
  private val sdSpiDev = "sdSpi" -> DeviceInstance("sdspi",
    devicePart = Some("SD_CARD"))
  private val vgaTextDev = "vga" -> DeviceInstance("vgatext", devicePart = Some("VGA"))
  private val vgaDmaDev = "vga" -> DeviceInstance("vgadma", devicePart = Some("VGA"))

  val allCuCc = baseCc.copy(
    useDspMul = true, imul = Hardware,
    fadd = Hardware, fsub = Hardware, fmul = Hardware, fdiv = Hardware,
    fneg = Hardware, i2f = Hardware, f2i = Hardware, fcmpl = Hardware, fcmpg = Hardware,
    ladd = Hardware, lsub = Hardware, lmul = Hardware, lneg = Hardware,
    lshl = Hardware, lshr = Hardware, lushr = Hardware, lcmp = Hardware,
    dadd = Hardware, dsub = Hardware, dmul = Hardware, ddiv = Hardware,
    i2d = Hardware, d2i = Hardware, l2d = Hardware, d2l = Hardware,
    f2d = Hardware, d2f = Hardware, dcmpl = Hardware, dcmpg = Hardware)

  val configs: Map[String, JopConfig] = Map(
    "baseline" -> base,

    "no_icu" -> withCc(baseCc.copy(idiv = Microcode, irem = Microcode)),

    "no_acache" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(useAcache = false))),

    "icu_full" -> withCc(baseCc.copy(imul = Hardware)),

    "icu_dsp" -> withCc(baseCc.copy(useDspMul = true, imul = Hardware)),

    "fcu" -> withCc(baseCc.copy(
      fadd = Hardware, fsub = Hardware, fmul = Hardware, fdiv = Hardware,
      fneg = Hardware, i2f = Hardware, f2i = Hardware,
      fcmpl = Hardware, fcmpg = Hardware)),

    "lcu" -> withCc(baseCc.copy(
      ladd = Hardware, lsub = Hardware, lmul = Hardware, lneg = Hardware,
      lshl = Hardware, lshr = Hardware, lushr = Hardware, lcmp = Hardware)),

    "dcu" -> withCc(baseCc.copy(
      dadd = Hardware, dsub = Hardware, dmul = Hardware, ddiv = Hardware,
      i2d = Hardware, d2i = Hardware, l2d = Hardware, d2l = Hardware,
      f2d = Hardware, d2f = Hardware, dcmpl = Hardware, dcmpg = Hardware)),

    "all_cu" -> withCc(allCuCc),

    "eth" -> withDevices(Map(uartDev, ethDev)),

    "sd_native" -> withDevices(Map(uartDev, sdNativeDev)),

    "sd_spi" -> withDevices(Map(uartDev, sdSpiDev)),

    "vga_text" -> withDevices(Map(uartDev, vgaTextDev)),

    "vga_dma" -> withDevices(Map(uartDev, vgaDmaDev)),

    "eth_sd_native" -> withDevices(Map(uartDev, ethDev, sdNativeDev)),

    "eth_sd_spi" -> withDevices(Map(uartDev, ethDev, sdSpiDev)),

    "full" -> withCcAndDevices(allCuCc,
      Map(uartDev, ethDev, sdNativeDev, vgaTextDev)),

    // --- Cache size sweep variants ---
    // Object cache: 32 entries (up from 16)
    "ocache_32" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(ocacheWayBits = 5))),

    // Object cache: 64 entries
    "ocache_64" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(ocacheWayBits = 6))),

    // Object cache: 16 fields per entry (up from 8)
    "ocache_16f" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(ocacheIndexBits = 4))),

    // Array cache: 32 entries (up from 16)
    "acache_32" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(acacheWayBits = 5))),

    // Array cache: 8 elements per line (up from 4)
    "acache_8e" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(acacheFieldBits = 3))),

    // Method cache: 32 blocks (up from 16)
    "mcache_32b" -> withCc(baseCc.copy(blockBits = 5))
  )

  val label = args.headOption.getOrElse {
    println("Available labels: " + configs.keys.toSeq.sorted.mkString(", "))
    sys.exit(1)
    ""
  }

  val config = configs.getOrElse(label, {
    println(s"Unknown label: $label")
    println("Available: " + configs.keys.toSeq.sorted.mkString(", "))
    sys.exit(1)
    null
  })

  println(s"=== Generating: $label ===")
  JopTopVerilog.generate(config)
  println(s"=== Done: $label -> ${config.entityName} ===")
}
