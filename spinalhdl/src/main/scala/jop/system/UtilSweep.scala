package jop.system

import jop.config._
import jop.config.Implementation._

/**
 * Generate Verilog for multiple Wukong DDR3 configs to measure per-feature LUT cost.
 * All variants use the same entity name (JopDdr3WukongTop) — copy/rename between runs.
 *
 * Usage: sbt "runMain jop.system.UtilSweep <label>"
 *   Labels: baseline, no_icu, no_acache, icu_full, icu_dsp, fcu, lcu, dcu, all_cu, eth, sd, sd_spi, eth_sd, full,
 *           ocache_32, ocache_64, ocache_16f, acache_32, acache_8e, mcache_32b
 */
object UtilSweep extends App {

  val base = JopConfig.wukongDdr3
  val baseCc = base.system.coreConfig

  def withCc(cc: JopCoreConfig): JopConfig =
    base.copy(systems = Seq(base.system.copy(coreConfig = cc)))

  def withIo(io: IoConfig, drivers: Seq[DeviceDriver]): JopConfig =
    base.copy(systems = Seq(base.system.copy(ioConfig = io, drivers = drivers)))

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

    "all_cu" -> withCc(baseCc.copy(
      useDspMul = true, imul = Hardware,
      fadd = Hardware, fsub = Hardware, fmul = Hardware, fdiv = Hardware,
      fneg = Hardware, i2f = Hardware, f2i = Hardware, fcmpl = Hardware, fcmpg = Hardware,
      ladd = Hardware, lsub = Hardware, lmul = Hardware, lneg = Hardware,
      lshl = Hardware, lshr = Hardware, lushr = Hardware, lcmp = Hardware,
      dadd = Hardware, dsub = Hardware, dmul = Hardware, ddiv = Hardware,
      i2d = Hardware, d2i = Hardware, l2d = Hardware, d2l = Hardware,
      f2d = Hardware, d2f = Hardware, dcmpl = Hardware, dcmpg = Hardware)),

    "eth" -> withIo(IoConfig(hasEth = true, ethGmii = true),
      Seq(DeviceDriver.UartCh340, DeviceDriver.EthGmii)),

    "sd" -> withIo(IoConfig(hasSdNative = true),
      Seq(DeviceDriver.UartCh340, DeviceDriver.SdNative)),

    "sd_spi" -> withIo(IoConfig(hasSdSpi = true),
      Seq(DeviceDriver.UartCh340, DeviceDriver.SdSpi)),

    "eth_sd" -> withIo(IoConfig.wukongFull,
      Seq(DeviceDriver.UartCh340, DeviceDriver.EthGmii, DeviceDriver.SdNative)),

    "full" -> JopConfig.wukongFull,

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
