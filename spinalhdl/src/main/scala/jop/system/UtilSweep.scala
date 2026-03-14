package jop.system

import jop.config._

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

  def withCcAndBc(bc: Map[String, String], extra: JopCoreConfig => JopCoreConfig = identity): JopConfig =
    withCc(extra(baseCc.copy(bytecodes = baseCc.bytecodes ++ bc)))

  def withDevices(devs: Map[String, DeviceInstance]): JopConfig =
    base.copy(systems = Seq(base.system.copy(devices = devs)))

  // Common device instances for Wukong board
  private val uartDev = "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CH340N"))
  private val ethDev = "eth" -> DeviceInstance(DeviceType.Ethernet,
    params = Map("gmii" -> true, "phyDataWidth" -> 8),
    devicePart = Some("RTL8211EG"))
  private val sdNativeDev = "sdNative" -> DeviceInstance(DeviceType.SdNative,
    devicePart = Some("SD_CARD"))
  private val sdSpiDev = "sdSpi" -> DeviceInstance(DeviceType.SdSpi,
    devicePart = Some("SD_CARD"))

  val configs: Map[String, JopConfig] = Map(
    "baseline" -> base,

    "no_icu" -> withCcAndBc(Map("idiv" -> "mc", "irem" -> "mc")),

    "no_acache" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(useAcache = false))),

    "icu_full" -> withCcAndBc(Map("imul" -> "hw")),

    "icu_dsp" -> withCcAndBc(Map("imul" -> "hw"), _.copy(useDspMul = true)),

    "fcu" -> withCcAndBc(Map("float" -> "hw")),

    "lcu" -> withCcAndBc(Map("long" -> "hw")),

    "dcu" -> withCcAndBc(Map("double" -> "hw")),

    "all_cu" -> withCcAndBc(Map("*" -> "hw"), _.copy(useDspMul = true)),

    "eth" -> withDevices(Map(uartDev, ethDev)),

    "sd" -> withDevices(Map(uartDev, sdNativeDev)),

    "sd_spi" -> withDevices(Map(uartDev, sdSpiDev)),

    "eth_sd" -> withDevices(Map(uartDev, ethDev, sdNativeDev)),

    "full" -> JopConfig.wukongFull,

    // --- Cache size sweep variants ---
    "ocache_32" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(ocacheWayBits = 5))),

    "ocache_64" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(ocacheWayBits = 6))),

    "ocache_16f" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(ocacheIndexBits = 4))),

    "acache_32" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(acacheWayBits = 5))),

    "acache_8e" -> withCc(baseCc.copy(
      memConfig = baseCc.memConfig.copy(acacheFieldBits = 3))),

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
