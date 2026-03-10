package jop.system

import jop.config._
import jop.config.Implementation._

/**
 * Generate Verilog for multiple Wukong DDR3 configs to measure per-feature LUT cost.
 * All variants use the same entity name (JopDdr3WukongTop) — copy/rename between runs.
 *
 * Usage: sbt "runMain jop.system.UtilSweep <label>"
 *   Labels: baseline, icu_full, icu_dsp, fcu, lcu, dcu, all_cu, eth, sd, eth_sd, full
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

    "eth_sd" -> withIo(IoConfig.wukongFull,
      Seq(DeviceDriver.UartCh340, DeviceDriver.EthGmii, DeviceDriver.SdNative)),

    "full" -> JopConfig.wukongFull
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
