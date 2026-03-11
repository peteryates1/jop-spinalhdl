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
 *           eth, sd_native, sd_spi, vga_text, vga_dma, eth_sd_native, eth_sd_spi, full
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
      ioConfig = IoConfig(),
      drivers = Seq(DeviceDriver.Uart))))

  val baseCc = base.system.coreConfig

  def withCc(cc: JopCoreConfig): JopConfig =
    base.copy(systems = Seq(base.system.copy(coreConfig = cc)))

  def withIo(io: IoConfig, drivers: Seq[DeviceDriver]): JopConfig =
    base.copy(systems = Seq(base.system.copy(ioConfig = io, drivers = drivers)))

  def withCcAndIo(cc: JopCoreConfig, io: IoConfig, drivers: Seq[DeviceDriver]): JopConfig =
    base.copy(systems = Seq(base.system.copy(coreConfig = cc, ioConfig = io, drivers = drivers)))

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

    "eth" -> withIo(IoConfig(hasEth = true, ethGmii = true),
      Seq(DeviceDriver.Uart, DeviceDriver.EthGmii)),

    "sd_native" -> withIo(IoConfig(hasSdNative = true),
      Seq(DeviceDriver.Uart, DeviceDriver.SdNative)),

    "sd_spi" -> withIo(IoConfig(hasSdSpi = true),
      Seq(DeviceDriver.Uart, DeviceDriver.SdSpi)),

    "vga_text" -> withIo(IoConfig(hasVgaText = true),
      Seq(DeviceDriver.Uart, DeviceDriver.VgaText)),

    "vga_dma" -> withIo(IoConfig(hasVgaDma = true),
      Seq(DeviceDriver.Uart, DeviceDriver.VgaDma)),

    "eth_sd_native" -> withIo(
      IoConfig(hasEth = true, ethGmii = true, hasSdNative = true),
      Seq(DeviceDriver.Uart, DeviceDriver.EthGmii, DeviceDriver.SdNative)),

    "eth_sd_spi" -> withIo(
      IoConfig(hasEth = true, ethGmii = true, hasSdSpi = true),
      Seq(DeviceDriver.Uart, DeviceDriver.EthGmii, DeviceDriver.SdSpi)),

    "full" -> withCcAndIo(allCuCc,
      IoConfig(hasEth = true, ethGmii = true, hasSdNative = true, hasVgaText = true),
      Seq(DeviceDriver.Uart, DeviceDriver.EthGmii, DeviceDriver.SdNative, DeviceDriver.VgaText))
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
