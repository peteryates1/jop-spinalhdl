package jop.io

import spinal.core._
import spinal.lib._
import spinal.lib.io.TriState
import spinal.lib.com.eth._
import jop.config._
import jop.system.{JopCluster, EthPll, ResetGenerator}
import jop.system.pll.PllResult

/** Direction and width of a top-level device port. */
sealed trait TopPinType
object TopPinType {
  case class Out(width: Int = 1) extends TopPinType
  case class In(width: Int = 1) extends TopPinType
  case object TriStateBool extends TopPinType
}

/** A top-level FPGA port required by a device. */
case class TopPin(name: String, pinType: TopPinType)

/**
 * Context for top-level device wiring.
 *
 * Provides FPGA-level resources needed by device wiring objects.
 * Populated by JopTop and passed to each DeviceTopWiring phase.
 */
case class TopWiringContext(
  config: JopConfig,
  simulation: Boolean,
  systemReset: Bool = null,
  pllResult: PllResult = null,
  ethPll: EthPll = null
) {
  val manufacturer: Manufacturer = config.fpgaFamily.manufacturer
  val isAltera: Boolean = manufacturer == Manufacturer.Altera
  val isXilinx: Boolean = manufacturer == Manufacturer.Xilinx
}

/**
 * Clock domains created by a device at the top level.
 *
 * Only Ethernet and VGA need external clock domains. Most devices
 * return the default (all None). Merged by JopTop and passed to
 * JopCluster and wireDevice.
 */
case class DeviceClockDomains(
  ethTxCd: Option[ClockDomain] = None,
  ethRxCd: Option[ClockDomain] = None,
  vgaCd: Option[ClockDomain] = None
)

/**
 * Encapsulates top-level wiring for one device type.
 *
 * Each device type that needs non-trivial wiring at the FPGA top level
 * implements this trait. UART is excluded (special hang-detector/CDC path).
 *
 * Two phases:
 *   1. createClockDomains — called after PLL, before mainArea
 *   2. wireDevice — called inside mainArea, wires cluster.devicePins to io pins
 */
trait DeviceTopWiring {
  /** Device type key matching DeviceType.key.
    * This is the string key, not DeviceType, because VgaTopWiring
    * covers both vgadma and vgatext under a single "vga" key. */
  def deviceType: String

  /**
   * Top-level FPGA ports this device requires.
   * Used by JopTop to auto-generate ports — no hardcoded per-device declarations needed.
   *
   * @param inst         Device instance (params for width/mode selection)
   * @param manufacturer FPGA manufacturer (for vendor-specific pin sets)
   */
  def topPins(inst: DeviceInstance, manufacturer: Manufacturer): Seq[TopPin] = Seq.empty

  /**
   * Create clock domains needed before mainArea.
   * Default: none. Override for Ethernet/VGA.
   */
  def createClockDomains(
    ctx: TopWiringContext,
    ioPins: Map[String, Data]
  ): DeviceClockDomains = DeviceClockDomains()

  /**
   * Wire cluster device pins to top-level io pins.
   * Called inside mainArea ClockingArea.
   *
   * @param instanceName  Device instance name (map key, e.g. "eth", "sdNative")
   * @param cluster       JopCluster with devicePins
   * @param ioPins        Top-level io pin signals (name -> Data)
   * @param clockDomains  Clock domains from phase 1
   * @param ctx           Wiring context
   */
  def wireDevice(
    instanceName: String,
    cluster: JopCluster,
    ioPins: Map[String, Data],
    clockDomains: DeviceClockDomains,
    ctx: TopWiringContext
  ): Unit
}

// ========================================================================
// Concrete wiring objects
// ========================================================================

object EthernetTopWiring extends DeviceTopWiring {
  val deviceType = "ethernet"

  override def topPins(inst: DeviceInstance, manufacturer: Manufacturer): Seq[TopPin] = {
    val gmii = inst.params.getOrElse("gmii", false).asInstanceOf[Boolean]
    val rgmii = inst.params.getOrElse("rgmii", false).asInstanceOf[Boolean]
    val dataWidth = inst.params.getOrElse("phyDataWidth", if (gmii) 8 else 4).asInstanceOf[Int]
    // A design that leaves the PHY's reset alone has no port for it -- a
    // declared but unconstrained port would float (see DeviceType.Ethernet).
    val reset =
      if (inst.params.getOrElse("noPhyReset", false).asInstanceOf[Boolean]) Nil
      else Seq(TopPin("e_resetn", TopPinType.Out()))
    val mdio = Seq(
      TopPin("e_mdc", TopPinType.Out()),
      TopPin("e_mdio", TopPinType.TriStateBool))
    // A board that routes neither RX_ER nor TX_ER has neither port (noErPins).
    val er = !inst.params.getOrElse("noErPins", false).asInstanceOf[Boolean]
    if (rgmii) Seq(
      TopPin("e_txc", TopPinType.Out()),
      TopPin("e_txd", TopPinType.Out(4)),
      TopPin("e_txctl", TopPinType.Out()),
      TopPin("e_rxc", TopPinType.In()),
      TopPin("e_rxd", TopPinType.In(4)),
      TopPin("e_rxctl", TopPinType.In())
    ) ++ mdio ++ reset
    // The TX clock: GMII drives GTX_CLK, MII takes TX_CLK from the PHY. Only the
    // one the mode uses is a port -- the board constrains only that one, and a
    // declared port without a pin is placed by the fitter wherever it likes.
    else Seq(
      TopPin("e_txd", TopPinType.Out(dataWidth)),
      TopPin("e_txen", TopPinType.Out())) ++
    (if (er) Seq(TopPin("e_txer", TopPinType.Out())) else Nil) ++ Seq(
      TopPin("e_rxd", TopPinType.In(dataWidth)),
      TopPin("e_rxdv", TopPinType.In())) ++
    (if (er) Seq(TopPin("e_rxer", TopPinType.In())) else Nil) ++ Seq(
      TopPin("e_rxc", TopPinType.In())
    ) ++ mdio ++ reset ++
      (if (gmii) Seq(TopPin("e_gtxc", TopPinType.Out())) else Seq(TopPin("e_txc", TopPinType.In())))
  }

  override def createClockDomains(
    ctx: TopWiringContext,
    ioPins: Map[String, Data]
  ): DeviceClockDomains = {
    if (ctx.simulation) return DeviceClockDomains()

    val sys = ctx.config.system

    // TX clock source. RGMII: the PLL's Ethernet output at Rgmii100.TxClockMhz,
    // which Rgmii100.txDomain below slows to the nibble rate.
    val ethTxClk =
      if (sys.ethRgmii) {
        require(ctx.pllResult != null && ctx.pllResult.ethClk.isDefined,
          "RGMII needs an Ethernet clock from the board's PLL (PllResult.ethClk)")
        ctx.pllResult.ethClk.get
      }
      else if (sys.ethGmii && ctx.ethPll != null) ctx.ethPll.io.c0
      else if (sys.ethGmii && ctx.pllResult != null && ctx.pllResult.ethClk.isDefined) ctx.pllResult.ethClk.get
      else ioPins.get("e_txc").map(_.asInstanceOf[Bool]).orNull

    val ethTxCd = {
      val txBootCd = ClockDomain(ethTxClk, config = ClockDomainConfig(resetKind = BOOT))
      val txReset = ResetCtrl.asyncAssertSyncDeassert(
        input = ctx.systemReset,
        clockDomain = txBootCd,
        inputPolarity = HIGH,
        outputPolarity = HIGH
      )
      val raw = ClockDomain(
        clock = ethTxClk,
        reset = txReset,
        config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
      )
      if (sys.ethRgmii) Rgmii100.txDomain(raw) else raw
    }

    val ethRxClk = ioPins("e_rxc").asInstanceOf[Bool]
    val ethRxCd = {
      val rxBootCd = ClockDomain(ethRxClk, config = ClockDomainConfig(resetKind = BOOT))
      val rxReset = ResetCtrl.asyncAssertSyncDeassert(
        input = ctx.systemReset,
        clockDomain = rxBootCd,
        inputPolarity = HIGH,
        outputPolarity = HIGH
      )
      ClockDomain(
        clock = ethRxClk,
        reset = rxReset,
        config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
      )
    }

    DeviceClockDomains(ethTxCd = Some(ethTxCd), ethRxCd = Some(ethRxCd))
  }

  def wireDevice(
    instanceName: String,
    cluster: JopCluster,
    ioPins: Map[String, Data],
    clockDomains: DeviceClockDomains,
    ctx: TopWiringContext
  ): Unit = {
    if (!cluster.devicePins.contains(instanceName)) return

    val sys = ctx.config.system
    val dataWidth = sys.phyDataWidth

    // MDIO
    ioPins("e_mdc").asInstanceOf[Bool] := cluster.devicePin[Bool](instanceName, "mdc")
    val mdio = ioPins("e_mdio").asInstanceOf[TriState[Bool]]
    mdio.write := cluster.devicePin[Bool](instanceName, "mdioOut")
    mdio.writeEnable := cluster.devicePin[Bool](instanceName, "mdioOe")
    cluster.devicePin[Bool](instanceName, "mdioIn") := mdio.read

    // PHY hardware reset, unless the design leaves it alone (noPhyReset)
    if (ioPins.contains("e_resetn")) {
      val phyRstCnt = Reg(UInt(20 bits)) init(0)
      val phyRstDone = phyRstCnt.andR
      when(!phyRstDone) { phyRstCnt := phyRstCnt + 1 }
      ioPins("e_resetn").asInstanceOf[Bool] := phyRstDone && cluster.devicePin[Bool](instanceName, "phyReset")
    }

    // PHY interface
    val ethPhyIo = cluster.devicePins(instanceName).elements.find(_._1 == "phy").get._2.asInstanceOf[PhyIo]

    // RGMII: the whole pin side is Rgmii100 (TX inter-frame gap, TXC, the RX
    // framing), clocked by the domains createClockDomains made.
    if (sys.ethRgmii) {
      val rgmii = Rgmii100(clockDomains.ethTxCd.get, clockDomains.ethRxCd.get)
      rgmii.io.phy.tx << ethPhyIo.tx
      ethPhyIo.rx << rgmii.io.phy.rx
      ethPhyIo.colision := rgmii.io.phy.colision
      ethPhyIo.busy := rgmii.io.phy.busy
      ioPins("e_txc").asInstanceOf[Bool] := rgmii.io.txc
      ioPins("e_txd").asInstanceOf[Bits] := rgmii.io.txd
      ioPins("e_txctl").asInstanceOf[Bool] := rgmii.io.txCtl
      rgmii.io.rxd := ioPins("e_rxd").asInstanceOf[Bits]
      rgmii.io.rxCtl := ioPins("e_rxctl").asInstanceOf[Bool]
      return
    }

    ethPhyIo.colision := False
    ethPhyIo.busy := False

    // GTX clock -- GMII only; an MII design has no e_gtxc port (topPins)
    if (sys.ethGmii) {
      val ethTxClk =
        if (ctx.ethPll != null) ctx.ethPll.io.c0
        else if (ctx.pllResult != null && ctx.pllResult.ethClk.isDefined) ctx.pllResult.ethClk.get
        else null
      ioPins("e_gtxc").asInstanceOf[Bool] := ethTxClk
    }

    // TX adapter
    val ethTxCd = clockDomains.ethTxCd.get
    val txArea = new ClockingArea(ethTxCd) {
      val interframe = MacTxInterFrame(dataWidth)
      interframe.io.input << ethPhyIo.tx
      ioPins("e_txen").asInstanceOf[Bool] := RegNext(interframe.io.output.valid) init(False)
      ioPins("e_txd").asInstanceOf[Bits]  := RegNext(interframe.io.output.fragment.data) init(0)
      ioPins.get("e_txer").foreach(_.asInstanceOf[Bool] := False)
    }

    // RX adapter
    val ethRxCd = clockDomains.ethRxCd.get
    val rxArea = new ClockingArea(ethRxCd) {
      val unbuffered = Flow(PhyRx(dataWidth))
      unbuffered.valid := ioPins("e_rxdv").asInstanceOf[Bool]
      unbuffered.data  := ioPins("e_rxd").asInstanceOf[Bits]
      // No RX_ER on the board (noErPins): the PHY's errors still corrupt the
      // frame, and the CRC check catches them there.
      unbuffered.error := ioPins.get("e_rxer").map(_.asInstanceOf[Bool]).getOrElse(False)

      // EVERY PIN FEEDS ONE REGISTER. `captured` is the pins' only load, so it
      // can sit in the I/O element (QsfGenerator.ethRxCapture), and `last` comes
      // from it rather than from the raw RX_DV. Reading the pin here fanned it
      // out through the fabric to the preamble detector, the CRC checker and the
      // RX buffer's cleanup, each at its own delay: re-timed on the EP4CGX150
      // with RX_DV changing on the clock edge, those loads spanned -0.91 to
      // +0.90 ns of hold slack, so they could disagree about where a frame ends.
      // Hygiene, not a measured fix -- that board's 3-5 % CRC loss did not move
      // (status item 166). One more cycle at 125 MHz; GmiiRxCaptureTest.
      val captured = unbuffered.stage()
      val buffered = captured.stage()

      val rxFlow = Flow(Fragment(PhyRx(dataWidth)))
      rxFlow.valid          := buffered.valid
      rxFlow.fragment       := buffered.payload
      rxFlow.last           := !captured.valid && buffered.valid

      ethPhyIo.rx << rxFlow.toStream
    }
  }
}

object VgaTopWiring extends DeviceTopWiring {
  val deviceType = "vga"

  override def topPins(inst: DeviceInstance, manufacturer: Manufacturer): Seq[TopPin] = Seq(
    TopPin("vga_hs", TopPinType.Out()),
    TopPin("vga_vs", TopPinType.Out()),
    TopPin("vga_r", TopPinType.Out(5)),
    TopPin("vga_g", TopPinType.Out(6)),
    TopPin("vga_b", TopPinType.Out(5))
  )

  override def createClockDomains(
    ctx: TopWiringContext,
    ioPins: Map[String, Data]
  ): DeviceClockDomains = {
    val vgaCd = if (ctx.simulation) {
      ClockDomain.external("vgaCd", withReset = false,
        config = ClockDomainConfig(resetKind = BOOT))
    } else {
      val vgaBootCd = ClockDomain(ctx.pllResult.vgaClk.get, config = ClockDomainConfig(resetKind = BOOT))
      val vgaReset = ResetCtrl.asyncAssertSyncDeassert(
        input = ctx.systemReset,
        clockDomain = vgaBootCd,
        inputPolarity = HIGH,
        outputPolarity = HIGH
      )
      ClockDomain(
        clock = ctx.pllResult.vgaClk.get,
        reset = vgaReset,
        config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH)
      )
    }
    DeviceClockDomains(vgaCd = Some(vgaCd))
  }

  def wireDevice(
    instanceName: String,
    cluster: JopCluster,
    ioPins: Map[String, Data],
    clockDomains: DeviceClockDomains,
    ctx: TopWiringContext
  ): Unit = {
    // Resolve device name: could be "vgaDma" or "vgaText"
    val vgaDeviceName = if (cluster.devicePins.contains("vgaDma")) "vgaDma"
                        else if (cluster.devicePins.contains("vgaText")) "vgaText"
                        else return
    ioPins("vga_hs").asInstanceOf[Bool] := cluster.devicePin[Bool](vgaDeviceName, "vgaHsync")
    ioPins("vga_vs").asInstanceOf[Bool] := cluster.devicePin[Bool](vgaDeviceName, "vgaVsync")
    ioPins("vga_r").asInstanceOf[Bits]  := cluster.devicePin[Bits](vgaDeviceName, "vgaR")
    ioPins("vga_g").asInstanceOf[Bits]  := cluster.devicePin[Bits](vgaDeviceName, "vgaG")
    ioPins("vga_b").asInstanceOf[Bits]  := cluster.devicePin[Bits](vgaDeviceName, "vgaB")
  }
}

object SdNativeTopWiring extends DeviceTopWiring {
  val deviceType = "sdnative"

  override def topPins(inst: DeviceInstance, manufacturer: Manufacturer): Seq[TopPin] = Seq(
    TopPin("sd_clk", TopPinType.Out()),
    TopPin("sd_cmd", TopPinType.TriStateBool),
    TopPin("sd_dat_0", TopPinType.TriStateBool),
    TopPin("sd_dat_1", TopPinType.TriStateBool),
    TopPin("sd_dat_2", TopPinType.TriStateBool),
    TopPin("sd_dat_3", TopPinType.TriStateBool)
  ) ++ (if (inst.params.getOrElse("noCardDetect", false).asInstanceOf[Boolean]) Nil
        else Seq(TopPin("sd_cd", TopPinType.In())))

  def wireDevice(
    instanceName: String,
    cluster: JopCluster,
    ioPins: Map[String, Data],
    clockDomains: DeviceClockDomains,
    ctx: TopWiringContext
  ): Unit = {
    if (!cluster.devicePins.contains(instanceName)) return

    val sdPins = cluster.devicePins(instanceName)
    val sdCmd = sdPins.elements.find(_._1 == "sdCmd").get._2.asInstanceOf[Bundle]
    val sdDat = sdPins.elements.find(_._1 == "sdDat").get._2.asInstanceOf[Bundle]

    ioPins("sd_clk").asInstanceOf[Bool] := cluster.devicePin[Bool](instanceName, "sdClk")

    val ioCmd = ioPins("sd_cmd").asInstanceOf[TriState[Bool]]
    ioCmd.write       := sdCmd.elements.find(_._1 == "write").get._2.asInstanceOf[Bool]
    ioCmd.writeEnable := sdCmd.elements.find(_._1 == "writeEnable").get._2.asInstanceOf[Bool]
    sdCmd.elements.find(_._1 == "read").get._2.asInstanceOf[Bool] := ioCmd.read

    val sdDatWrite   = sdDat.elements.find(_._1 == "write").get._2.asInstanceOf[Bits]
    val sdDatWriteEn = sdDat.elements.find(_._1 == "writeEnable").get._2.asInstanceOf[Bits]
    val sdDatRead    = sdDat.elements.find(_._1 == "read").get._2.asInstanceOf[Bits]

    for (i <- 0 until 4) {
      val dat = ioPins(s"sd_dat_$i").asInstanceOf[TriState[Bool]]
      dat.write       := sdDatWrite(i)
      dat.writeEnable := sdDatWriteEn(i)
    }
    sdDatRead := ioPins("sd_dat_3").asInstanceOf[TriState[Bool]].read ##
                 ioPins("sd_dat_2").asInstanceOf[TriState[Bool]].read ##
                 ioPins("sd_dat_1").asInstanceOf[TriState[Bool]].read ##
                 ioPins("sd_dat_0").asInstanceOf[TriState[Bool]].read

    // CARD DETECT IS ACTIVE LOW: SdNative computes `!BufferCC(io.sdCd, True)`,
    // so driving False means "card present". A board with no detect line cannot
    // report absence, so present is the only useful assumption -- and it must be
    // DRIVEN, not left floating.
    cluster.devicePin[Bool](instanceName, "sdCd") :=
      ioPins.get("sd_cd").map(_.asInstanceOf[Bool]).getOrElse(False)
  }
}

object SdSpiTopWiring extends DeviceTopWiring {
  val deviceType = "sdspi"

  override def topPins(inst: DeviceInstance, manufacturer: Manufacturer): Seq[TopPin] = Seq(
    TopPin("sd_spi_clk", TopPinType.Out()),
    TopPin("sd_spi_mosi", TopPinType.Out()),
    TopPin("sd_spi_miso", TopPinType.In()),
    TopPin("sd_spi_cs", TopPinType.Out()),
  ) ++ (if (inst.params.getOrElse("noCardDetect", false).asInstanceOf[Boolean]) Nil
        else Seq(TopPin("sd_spi_cd", TopPinType.In())))

  def wireDevice(
    instanceName: String,
    cluster: JopCluster,
    ioPins: Map[String, Data],
    clockDomains: DeviceClockDomains,
    ctx: TopWiringContext
  ): Unit = {
    if (!cluster.devicePins.contains(instanceName)) return

    ioPins("sd_spi_clk").asInstanceOf[Bool]  := cluster.devicePin[Bool](instanceName, "sclk")
    ioPins("sd_spi_mosi").asInstanceOf[Bool] := cluster.devicePin[Bool](instanceName, "mosi")
    ioPins("sd_spi_cs").asInstanceOf[Bool]   := cluster.devicePin[Bool](instanceName, "cs")
    cluster.devicePin[Bool](instanceName, "miso") := ioPins("sd_spi_miso").asInstanceOf[Bool]
    // Active low, as SdNative above: absent detect line => drive present.
    cluster.devicePin[Bool](instanceName, "cd")   :=
      ioPins.get("sd_spi_cd").map(_.asInstanceOf[Bool]).getOrElse(False)
  }
}

object ConfigFlashTopWiring extends DeviceTopWiring {
  val deviceType = "cfgflash"

  override def topPins(inst: DeviceInstance, manufacturer: Manufacturer): Seq[TopPin] = {
    if (manufacturer == Manufacturer.Altera) Seq(
      TopPin("cf_dclk", TopPinType.Out()),
      TopPin("cf_ncs", TopPinType.Out()),
      TopPin("cf_asdo", TopPinType.Out()),
      TopPin("cf_data0", TopPinType.In())
    ) else if (manufacturer == Manufacturer.Xilinx) Seq(
      TopPin("cf_miso", TopPinType.In())
    ) else Seq.empty
  }

  def wireDevice(
    instanceName: String,
    cluster: JopCluster,
    ioPins: Map[String, Data],
    clockDomains: DeviceClockDomains,
    ctx: TopWiringContext
  ): Unit = {
    if (!cluster.devicePins.contains(instanceName)) return

    if (ctx.isAltera) {
      ioPins("cf_dclk").asInstanceOf[Bool] := cluster.devicePin[Bool](instanceName, "dclk")
      ioPins("cf_ncs").asInstanceOf[Bool]  := cluster.devicePin[Bool](instanceName, "ncs")
      ioPins("cf_asdo").asInstanceOf[Bool] := cluster.devicePin[Bool](instanceName, "asdo")
      cluster.devicePin[Bool](instanceName, "data0") := ioPins("cf_data0").asInstanceOf[Bool]
      cluster.devicePin[Bool](instanceName, "flashReady") := True
    } else if (ctx.isXilinx) {
      cluster.devicePin[Bool](instanceName, "data0") := ioPins("cf_miso").asInstanceOf[Bool]
      cluster.devicePin[Bool](instanceName, "flashReady") := True
    }
  }
}

// ========================================================================
// Registry
// ========================================================================

object DeviceTopWirings {
  /** All available top-level wiring objects */
  private val all: Seq[DeviceTopWiring] = Seq(
    EthernetTopWiring,
    VgaTopWiring,
    SdNativeTopWiring,
    SdSpiTopWiring,
    ConfigFlashTopWiring
  )

  /** Map from device type string key to wiring object */
  val byType: Map[String, DeviceTopWiring] =
    all.map(w => w.deviceType -> w).toMap

  /** Look up wiring for a DeviceInstance. VGA maps both vgadma/vgatext to VgaTopWiring. */
  def forInstance(inst: DeviceInstance): Option[DeviceTopWiring] = {
    byType.get(inst.deviceType.key).orElse(
      inst.deviceType match {
        case DeviceType.VgaDma | DeviceType.VgaText => Some(VgaTopWiring)
        case _ => None
      }
    )
  }
}
