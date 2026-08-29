package jop.config

/**
 * Board — Physical PCB with Devices and Pin Mappings
 *
 * Models the physical hardware hierarchy:
 *   BoardDevice   — a component on a PCB with signal-to-pin mapping
 *   Board         — a PCB (may or may not carry an FPGA)
 *   SystemAssembly — a collection of boards that form the complete hardware
 *
 * No distinction between "FPGA board" and "carrier board" at the type level.
 * A board either has an FPGA (fpga = Some(...)) or doesn't.
 *
 * Pin resolution for multi-board assemblies (e.g., QMTECH core + daughter board):
 *   Device signal → connector pin (e.g., "J2:14") → FPGA pin (e.g., "PIN_A20")
 *   Daughter board devices map signals to connector pins.
 *   The FPGA board's connectors map connector pins to FPGA pins.
 *   SystemAssembly.pinMapping() resolves through the chain.
 *
 * Boards that have expansion connectors (QMTECH, Alchitry) expose them via
 * the connectors map, even if no daughter board is currently attached.
 * Boards without connectors defined yet can be extended later.
 */

// ==========================================================================
// Board Devices
// ==========================================================================

/**
 * A device mounted on a board with its signal-to-pin mapping.
 *
 * Pin references are either:
 *   - Direct FPGA pins: "PIN_A20" (Altera) or "A20" (Xilinx)
 *   - Connector references: "J2:14" (connector name : pin number)
 * Direct pins are used for on-board FPGA devices.
 * Connector references are used for devices on carrier/daughter boards.
 */
case class BoardDevice(
  part: String,                                // part number or device name
  role: Option[String] = None,                 // optional role disambiguation
  count: Int = 1,                              // physical chip count (>1 = ganged for wider bus)
  mapping: Map[String, String] = Map.empty     // device signal → pin reference
)

// ==========================================================================
// PLL Type — which PLL family a board uses
// ==========================================================================

import spinal.core._
import jop.system.{DramPll, Max1000Pll, Ep4ce6Pll, SdramExerciserClkWiz}
import jop.system.pll.{Cyc5000Pll, WukongClkWizBlackBox, I5Pll, PllResult}
import jop.ddr3.ClkWizBlackBox

/** Where a PLL's IP file comes from. */
sealed trait PllIpFile
object PllIpFile {
  /** Written per configuration into build/<config>/ip/ by DramPllGen. */
  case class Generated(name: String) extends PllIpFile
  /** A hand-written file at a fixed repo-relative path. */
  case class Static(path: String) extends PllIpFile
}

sealed trait PllType {
  /** Instantiate the PLL and return all clock outputs.
    * @param systemIndex differentiates multiple PLL instances in multi-system designs */
  def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0): PllResult

  /** The IP file that defines this PLL, if a Quartus project must list one.
    *
    * `Generated` means DramPllGen writes it per configuration into
    * `build/<config>/ip/`; `Static` means a hand-written file at a fixed
    * repo-relative path. Xilinx and Lattice return None -- their IP is an .xci
    * the project reads separately, or a module generated beside the design.
    *
    * Stated here because the generator hardcoded `dram_pll.vhd`, which is the
    * EP4CGX150's PLL: a Cyclone V project came out naming a file that does not
    * exist for it. */
  def ipFile: Option[PllIpFile] = None

  /** What this generator PRODUCES, described without the vendor.
    *
    * Optional while the tree is converted: a PllType that still wires a
    * hand-written blackbox has no spec to declare, and saying so is better than
    * inventing one that nothing checks. Where it IS declared, the IP is
    * generated from it and the declaration is verified against the result. */
  def spec: Option[PllSpec] = None

  /** The instance name this PLL gets in the netlist, for timing constraints to
    * refer to.
    *
    * STATED, not inferred. `create` builds the blackbox as a local `val pll`,
    * which is not a component field, so SpinalHDL cannot see that name and
    * falls back to the class name -- `DramPll` becomes `dramPll`. Relying on
    * that implicitly is how `jop_sdram.sdc` came to name the instance `pll`
    * and have its entire `set_clock_groups` silently discarded:
    *
    *   Warning (332049): Ignored set_clock_groups ... could not match any
    *   element of the following types: ( clk )
    *
    * Empty for PLL types whose constraints are not generated yet. */
  def instanceName: String = ""

  /** Altera megafunction path from the instance to a numbered clock output.
    * Quartus names altpll outputs `<inst>|altpll_component|auto_generated|pll1|clk[n]`. */
  def alteraClockPath(n: Int): String =
    s"$instanceName|altpll_component|auto_generated|pll1|clk[$n]"
}

object PllType {

  /** EP4CGX150 (Cyclone IV GX): DramPll megafunction.
    * SDR: c1=80MHz system, c2=80MHz/-3ns SDRAM, c3=25MHz VGA/Eth.
    * BRAM: c1=100MHz system. */
  case object AlteraDramPll extends PllType {
    override val instanceName = "dramPll"
    override val ipFile = Some(PllIpFile.Generated("dram_pll.vhd"))
    def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0) = {
      val pll = DramPll()
      pll.io.inclk0 := inputClock
      pll.io.areset := False
      memType match {
        case MemoryType.SDRAM_SDR =>
          PllResult(systemClk = Some(pll.io.c1), locked = pll.io.locked,
            sdramClk = Some(pll.io.c2), ethClk = Some(pll.io.c3), vgaClk = Some(pll.io.c3))
        case _ =>
          PllResult(systemClk = Some(pll.io.c1), locked = pll.io.locked)
      }
    }
  }

  /** CYC5000 (Cyclone V): altera_pll megafunction.
    * 12 MHz -> outclk_0=80MHz system, outclk_1=80MHz/-2.5ns SDRAM. */
  case object AlteraCyc5000 extends PllType {
    override val ipFile = Some(PllIpFile.Static("fpga/cyc5000-sdram/cyc5000_pll.vhd"))
    def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0) = {
      val pll = Cyc5000Pll()
      pll.io.refclk := inputClock
      pll.io.rst := False
      PllResult(systemClk = Some(pll.io.outclk_0), locked = pll.io.locked,
        sdramClk = Some(pll.io.outclk_1))
    }
  }

  /** Colorlight i5 (ECP5): EHXPLLL, 25 MHz -> 40 MHz system + 40 MHz/-3.1 ns
    * SDRAM clock.
    *
    * `sdramClk` is returned unconditionally rather than only for
    * MemoryType.SDRAM_SDR. The BRAM preset simply never reads it, and the
    * unused PLL output costs nothing — whereas making it conditional means the
    * BRAM and SDRAM builds instantiate different PLLs, which is exactly the
    * kind of difference that makes a working BRAM build stop predicting
    * anything about the SDRAM one. */
  case object LatticeEcp5I5 extends PllType {
    /** 25 MHz board oscillator -> 40 MHz system + 40 MHz SDRAM at 315 deg.
      *
      * 315 deg = -45 deg = -3.1 ns at 40 MHz. Expressed in DEGREES because that
      * stays correct if the frequency moves; the equivalent nanosecond figure
      * does not. This is the spec `pll_jop_i5.v` was generated from -- its own
      * header records the ecppll command -- so the file is now produced from it
      * rather than kept beside it. */
    override val spec = Some(PllSpec(
      inputMhz = 25,
      outputs = Seq(
        PllOutput(PllRole.System, 40),
        PllOutput(PllRole.Sdram, 40, phaseDeg = 315))))

    def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0) = {
      val pll = I5Pll()
      pll.io.clkin := inputClock
      PllResult(
        systemClk = Some(pll.io.clkout0),
        locked = pll.io.locked,
        sdramClk = Some(pll.io.clkout1))
    }
  }

  /** MAX1000 (MAX10): c0=80MHz system, c1=80MHz/-3ns SDRAM. */
  case object AlteraMax1000 extends PllType {
    // .v, not .vhd: the file on disk is Verilog. Naming it .vhd made the
    // generated project reference a path that does not exist, and Quartus
    // reported it as `undefined entity "max1000_pll"` -- a missing FILE
    // presented as a missing MODULE. (It is still a fit-check stub; see the
    // header of max1000_pll.v.)
    override val ipFile = Some(PllIpFile.Static("fpga/max1000/max1000_pll.v"))
    def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0) = {
      val pll = Max1000Pll()
      pll.io.inclk0 := inputClock
      pll.io.areset := False
      PllResult(systemClk = Some(pll.io.c0), locked = pll.io.locked,
        sdramClk = Some(pll.io.c1))
    }
  }

  /** EP4CE6 (Cyclone IV E): c0=80MHz system, c1=80MHz/-3ns SDRAM. */
  case object AlteraEp4ce6 extends PllType {
    override val ipFile = Some(PllIpFile.Static("fpga/ip/ep4ce6_pll.vhd"))
    def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0) = {
      val pll = Ep4ce6Pll()
      pll.io.inclk0 := inputClock
      pll.io.areset := False
      PllResult(systemClk = Some(pll.io.c0), locked = pll.io.locked,
        sdramClk = Some(pll.io.c1))
    }
  }

  /** Wukong XC7A100T: Vivado clk_wiz variants per memory type.
    * SDR: clk_100 system + clk_100_shift SDRAM + clk_125 Eth.
    * DDR3: clk_100 MIG sys + clk_200 MIG ref + clk_125 Eth.
    * BRAM: clk_100 system.
    *
    * The IP instance is named for its FUNCTION, not its position in `systems`.
    * These three variants have incompatible port sets, and while they were all
    * called `clk_wiz_0` the three generator scripts each deleted the other two's
    * output -- so switching memory type without re-running create-ip failed with
    * "named port connection 'clk_100_shift' does not exist", and the dual build
    * could only work because SDR happened to land at index 1. Naming by function
    * makes the flows independent: `make sdram-create-ip` and `ddr3-create-ip`
    * now produce different directories and no longer clobber each other, and the
    * dual build gets both from one generation with no index dependence. */
  case object XilinxWukong extends PllType {
    def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0) = {
      val clkWizName = memType match {
        case MemoryType.SDRAM_SDR  => "sdr_clk"
        case MemoryType.SDRAM_DDR3 => "ddr3_clk"
        case _                     => "bram_clk"
      }
      memType match {
        case MemoryType.SDRAM_SDR =>
          val clkWiz = new SdramExerciserClkWiz(clkWizName)
          clkWiz.io.clk_in := inputClock
          clkWiz.io.resetn := True
          PllResult(systemClk = Some(clkWiz.io.clk_100), locked = clkWiz.io.locked,
            sdramClk = Some(clkWiz.io.clk_100_shift), ethClk = Some(clkWiz.io.clk_125))
        case MemoryType.SDRAM_DDR3 =>
          val clkWiz = new ClkWizBlackBox(clkWizName)
          clkWiz.io.clk_in := inputClock
          clkWiz.io.resetn := !ClockDomain.current.readResetWire
          PllResult(locked = clkWiz.io.locked, migSysClk = Some(clkWiz.io.clk_100),
            migRefClk = Some(clkWiz.io.clk_200), ethClk = Some(clkWiz.io.clk_125))
        case _ =>
          val clkWiz = new WukongClkWizBlackBox(clkWizName)
          clkWiz.io.clk_in := inputClock
          clkWiz.io.resetn := False
          PllResult(systemClk = Some(clkWiz.io.clk_100), locked = clkWiz.io.locked)
      }
    }
  }

  /** Au V2, QmtechXC7A100T (DDR3 ClkWiz):
    * clk_100=MIG sys, clk_200=MIG ref, clk_125=Eth. */
  case object XilinxDdr3ClkWiz extends PllType {
    def create(memType: MemoryType, inputClock: Bool, systemIndex: Int = 0) = {
      val instanceName = if (systemIndex == 0) "clk_wiz_0" else s"clk_wiz_$systemIndex"
      val clkWiz = new ClkWizBlackBox(instanceName)
      clkWiz.io.clk_in := inputClock
      clkWiz.io.resetn := !ClockDomain.current.readResetWire
      PllResult(locked = clkWiz.io.locked, migSysClk = Some(clkWiz.io.clk_100),
        migRefClk = Some(clkWiz.io.clk_200), ethClk = Some(clkWiz.io.clk_125))
    }
  }
}

// ==========================================================================
// Board
// ==========================================================================

/**
 * A physical PCB — any board in the system.
 *
 * @param connectors Expansion header pin-to-FPGA-pin mapping (FPGA boards only).
 *                   Key: physical connector designator ("U4", "U5", "U2", "J4", "J5").
 *                   Value: physical pin number → FPGA pin name.
 *                   QMTECH 2x32 headers: pins 1-6 (power/GND) and 61+ (NC/VIN) excluded;
 *                   signal pins are 5-58. Alchitry DF40C: per-connector pin numbering.
 */
case class Board(
  name: String,
  fpga: Option[FpgaDevice] = None,
  devices: Seq[BoardDevice] = Seq.empty,
  connectors: Map[String, Map[Int, String]] = Map.empty,
  pllType: Option[PllType] = None,
  entityTag: String = "",       // Replaces memType-based entity name (e.g., "Cyc5000", "Max1000Sdram")
  entitySuffix: String = "",    // Appended to memType-based entity name (e.g., "Wukong")
  ledActiveHigh: Boolean = false,
  /** Polarity of the board's reset switch, as wired. Active-low (a button
    * pulling to ground with a pull-up) is by far the common case, which is why
    * the top-level port is named `reset_n`. Set false for a board that wires
    * the switch the other way -- the config then carries the fact and `JopTop`
    * inverts accordingly, instead of everyone assuming active-low. */
  resetActiveLow: Boolean = true,
  ddr3HasCs: Boolean = false,
  hasEthPll: Boolean = false,
  useStackCache: Boolean = false,
  /** Default I/O standard for this board's user I/O. */
  ioStandard: String = "LVCMOS33",
  /** Release the dedicated nCEO pin as regular I/O (Cyclone families).
    *
    * Needed only by a board that assigns a user signal to it -- the A-E115FB
    * puts `mem_addr[10]` there, and without this Quartus reports the clash as
    * an unplaceable pin ("Can't place multiple pins assigned to pin location
    * Pin_K22") rather than as a configuration conflict.
    *
    * NOT free, which is why it is opt-in rather than set for every Cyclone
    * board alongside the other configuration-pin releases: enabling it on the
    * EP4CGX150, which does not need it, cost 27 logic elements and 0.084 ns of
    * setup slack (11,112 LE / +0.626 ns became 11,139 / +0.542). */
  reserveNceoAsIo: Boolean = false,
  /** Per-PORT I/O standards, where one board-wide default will not do.
    *
    * The A-E115FB's clock, reset and LEDs sit in banks shared with the 1.8 V
    * DDR2 interface while its CH340 is 3.3 V, so the board has no single
    * answer. Keyed by top-level port name; a bare name covers a bus, so `led`
    * applies to `led[0]`..`led[3]`. */
  portIoStandards: Map[String, String] = Map.empty,
  /** This board's alias in `fpga/scripts/jtag_probe_map`, and its CONSOLE alias
    * in `fpga/scripts/usb_serial_map`. The two namespaces differ on purpose:
    * JTAG has one alias per board, but serial needs one per CDC ENDPOINT --
    * a Pico presents two, and there are two Picos, so the XC7A100T's console is
    * `xc7a100t-pico-0` while its probe is `xc7a100t`.
    *
    * Named here so a preset knows its own hardware. Guessing an alias from the
    * board name silently yields nothing, and an empty string reads as "not
    * attached" -- which has twice been mistaken for a broken board. */
  probeAlias: Option[String] = None,
  consoleAlias: Option[String] = None,
  /** openFPGALoader `-c` cable or `-b` board profile. Empty on Altera boards,
    * which are programmed with quartus_pgm using the cable NAME that
    * jtag_probe_map reports. */
  loaderCable: Option[String] = None,
  loaderBoard: Option[String] = None,
  /** Vendor IP this board always needs, beyond the system PLL, as
    * repo-relative paths. The Quartus assignment is inferred from the
    * extension -- `.qip` -> QIP_FILE, `.vhd` -> VHDL_FILE, `.v`/`.sv` ->
    * VERILOG_FILE -- so a new kind of IP needs no new field.
    *
    * The A-E115FB's DDR2 controller is the case: without its `.qip` Quartus
    * stops with "instantiates undefined entity ddr2_64bit". */
  extraIpFiles: Seq[String] = Seq.empty,
  /** IP needed only when the DESIGN declares an Ethernet device. The board has
    * the PHY and its PLL either way; a UART-only build must not list them. */
  ethIpFiles: Seq[String] = Seq.empty,
  /** Vendor constraint files the generated project SOURCES rather than restates.
    *
    * For pins this project owns, `QsfGenerator` emits assignments from board
    * data and that is the right shape. A hard-memory PHY is not that: the
    * A-E115FB's `ddr2_pins.qsf` carries ~380 instance assignments of six kinds,
    * and only two of them (`IO_STANDARD`, location) are pin facts. The rest --
    * `MEM_INTERFACE_DELAY_CHAIN_CONFIG`, `OUTPUT_ENABLE_GROUP`, `CKN_CK_PAIR`,
    * `PAD_TO_CORE_DELAY`, `CURRENT_STRENGTH_NEW` -- are properties of the
    * ALTMEMPHY instance and the SODIMM, produced by the vendor tool.
    *
    * Transcribing those into Scala would make a SECOND copy of a vendor
    * artifact, free to drift from the reference project it came from, and the
    * drift would surface as a memory that trains at one temperature and not
    * another. Referencing keeps one source of truth -- which is the whole point
    * -- and it is what the hand-written `jop_ddr2.qsf` did (`source
    * ddr2_pins.qsf`) before it was generated.
    *
    * Repo-root-relative; the generated Tcl rewrites them for the project dir. */
  constraintFiles: Seq[String] = Seq.empty,
  /** Ports the generated top HAS but this board does not WIRE.
    *
    * A fixed interface bundle (SdramInterface, say) always presents every
    * signal, even where the board straps it to a rail instead of routing it to
    * the FPGA. Those ports must still be given a ball, and parking them on a
    * documented unused header pin is strictly better than the alternative --
    * nextpnr's `--lpf-allow-unconstrained` lets the placer pick ANY free ball,
    * including ones wired to something, trading a known-harmless output for an
    * unknown possibly-harmful one.
    *
    * Kept as CONFIG rather than as a comment in a hand-written constraint file,
    * because the constraint file is generated from here. */
  parkedPins: Map[String, String] = Map.empty
) {
  def hasFpga: Boolean = fpga.isDefined

  /** Find a device by part name */
  def findDevice(part: String): Option[BoardDevice] =
    devices.find(_.part == part)

  /** Find a device by role */
  def findDeviceByRole(role: String): Option[BoardDevice] =
    devices.find(_.role.contains(role))

  /** All memory devices on this board (resolved via MemoryDevice registry) */
  def memoryDevices: Seq[(BoardDevice, MemoryDevice)] =
    devices.flatMap(bd => MemoryDevice.byName(bd.part).map(md => (bd, md)))

  /** Board oscillator frequency (derived from CLOCK_* device, hardware fact) */
  def clockFreq: spinal.core.HertzNumber = {
    import spinal.core._
    devices.find(_.part.startsWith("CLOCK_")).map { d =>
      val mhz = d.part.stripPrefix("CLOCK_").stripSuffix("MHz").toInt
      HertzNumber(BigDecimal(mhz) * 1000000)
    }.getOrElse(HertzNumber(BigDecimal(50000000)))
  }

  /** Number of on-board LEDs */
  def ledCount: Int = devices.filter(_.part == "LED").flatMap(_.mapping.keys).size
}

object Board {
  // ========================================================================
  // FPGA modules
  // ========================================================================

  /**
   * QMTECH EP4CGX150 FPGA module (Cyclone IV GX + W9825G6JH6 SDR SDRAM).
   *
   * U5 and U4 are 2x32 pin expansion headers (64 pins each).
   * Pins 1-2: GND, 3-4: 3V3, 5-58: I/O, 59-62: NC, 63-64: VIN.
   * U4 (Banks 5,6,7) mates with DB_FPGA J3. U5 (Banks 3,4) mates with DB_FPGA J2.
   * Pin-to-FPGA mapping from core board schematic QMTECH-EP4CGX150GX-CORE-BOARD-V01.
   * SDRAM pins verified against jop_sdram.qsf (working FPGA build).
   */
  def QmtechEP4CGX150 = Board(
    name = "qmtech-ep4cgx150",
    ethIpFiles = Seq("fpga/qmtech-ep4cgx150-sdram/pll_125.v"),
    probeAlias = Some("ep4cgx150"),
    consoleAlias = Some("ep4cgx150"),
    fpga = Some(FpgaDevice.EP4CGX150DF27I7),
    pllType = Some(PllType.AlteraDramPll),
    hasEthPll = true,
    devices = Seq(
      // On-board SDRAM (direct FPGA pins, verified against jop_sdram.qsf)
      BoardDevice("W9825G6JH6", role = Some("sdr"), mapping = Map(
        "CLK" -> "PIN_E22", "CKE" -> "PIN_K24",
        "CS_n" -> "PIN_H26", "RAS_n" -> "PIN_H25",
        "CAS_n" -> "PIN_G26", "WE_n" -> "PIN_G25",
        "BA0" -> "PIN_J25", "BA1" -> "PIN_J26",
        "A0" -> "PIN_L25", "A1" -> "PIN_L26", "A2" -> "PIN_M25",
        "A3" -> "PIN_M26", "A4" -> "PIN_N22", "A5" -> "PIN_N23",
        "A6" -> "PIN_N24", "A7" -> "PIN_M22", "A8" -> "PIN_M24",
        "A9" -> "PIN_L23", "A10" -> "PIN_K26", "A11" -> "PIN_L24",
        "A12" -> "PIN_K23",
        "DQ0" -> "PIN_B25", "DQ1" -> "PIN_B26", "DQ2" -> "PIN_C25",
        "DQ3" -> "PIN_C26", "DQ4" -> "PIN_D25", "DQ5" -> "PIN_D26",
        "DQ6" -> "PIN_E25", "DQ7" -> "PIN_E26",
        "DQ8" -> "PIN_H23", "DQ9" -> "PIN_G24", "DQ10" -> "PIN_G22",
        "DQ11" -> "PIN_F24", "DQ12" -> "PIN_F23", "DQ13" -> "PIN_E24",
        "DQ14" -> "PIN_D24", "DQ15" -> "PIN_C24",
        "DQM0" -> "PIN_F26", "DQM1" -> "PIN_H24")),
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "PIN_B14")),
      BoardDevice("LED", mapping = Map("led0" -> "PIN_A25", "led1" -> "PIN_A24")),
      // sw1 is the reset button. The key name "reset" is what PinResolver and
      // JopTop look for; sw0 stays free for application use. Both are the
      // core board's user push-buttons, active low.
      BoardDevice("SWITCH", mapping = Map("sw0" -> "PIN_AD23", "reset" -> "PIN_AD24")),
      // EPCS configuration flash, on the DEDICATED configuration pins. Using
      // them as user I/O is what ENABLE_CONFIGURATION_PINS and the four
      // RESERVE_*_AFTER_CONFIGURATION settings are for -- QuartusProject emits
      // those only when a design declares a cfgflash device, because releasing
      // configuration pins on a board that does not touch them is not free.
      // Pins from the hand-written config_flash_exerciser.qsf and
      // flash_programmer.qsf, which are the two designs that drive it.
      BoardDevice("EPCS", mapping = Map(
        "DCLK" -> "PIN_F6", "NCS" -> "PIN_D5",
        "ASDO" -> "PIN_E6", "DATA0" -> "PIN_D6"))),
    connectors = Map(
      // U4: Banks 5, 6, 7 — mates with DB_FPGA J3 when used with daughter board
      "U4" -> Map(
        5 -> "PIN_C21",  6 -> "PIN_B22",  7 -> "PIN_B23",  8 -> "PIN_A23",
        9 -> "PIN_B21",  10 -> "PIN_A22", 11 -> "PIN_C19", 12 -> "PIN_B19",
        13 -> "PIN_A21", 14 -> "PIN_A20", 15 -> "PIN_A19", 16 -> "PIN_A18",
        17 -> "PIN_C17", 18 -> "PIN_B18", 19 -> "PIN_C16", 20 -> "PIN_B17",
        21 -> "PIN_A17", 22 -> "PIN_A16", 23 -> "PIN_B15", 24 -> "PIN_A15",
        25 -> "PIN_C15", 26 -> "PIN_C14", 27 -> "PIN_C13", 28 -> "PIN_B13",
        29 -> "PIN_C12", 30 -> "PIN_C11", 31 -> "PIN_A13", 32 -> "PIN_A12",
        33 -> "PIN_B11", 34 -> "PIN_A11", 35 -> "PIN_B10", 36 -> "PIN_A10",
        37 -> "PIN_C10", 38 -> "PIN_B9",  39 -> "PIN_A9",  40 -> "PIN_A8",
        41 -> "PIN_A7",  42 -> "PIN_A6",  43 -> "PIN_B7",  44 -> "PIN_B6",
        45 -> "PIN_B5",  46 -> "PIN_A5",  47 -> "PIN_B4",  48 -> "PIN_A4",
        49 -> "PIN_C5",  50 -> "PIN_C4",  51 -> "PIN_A3",  52 -> "PIN_A2",
        53 -> "PIN_B2",  54 -> "PIN_B1",  55 -> "PIN_D1",  56 -> "PIN_C1",
        57 -> "PIN_E2",  58 -> "PIN_E1"),
      // U5: Banks 3, 4 — mates with DB_FPGA J2 when used with daughter board
      "U5" -> Map(
        5 -> "PIN_AF24",  6 -> "PIN_AF25",  7 -> "PIN_AC21",  8 -> "PIN_AD21",
        9 -> "PIN_AE23",  10 -> "PIN_AF23", 11 -> "PIN_AE22", 12 -> "PIN_AF22",
        13 -> "PIN_AD20", 14 -> "PIN_AE21", 15 -> "PIN_AF20", 16 -> "PIN_AF21",
        17 -> "PIN_AE19", 18 -> "PIN_AF19", 19 -> "PIN_AC19", 20 -> "PIN_AD19",
        21 -> "PIN_AE18", 22 -> "PIN_AF18", 23 -> "PIN_AC18", 24 -> "PIN_AD18",
        25 -> "PIN_AE17", 26 -> "PIN_AF17", 27 -> "PIN_AC17", 28 -> "PIN_AD17",
        29 -> "PIN_AF15", 30 -> "PIN_AF16", 31 -> "PIN_AC16", 32 -> "PIN_AD16",
        33 -> "PIN_AE14", 34 -> "PIN_AE15", 35 -> "PIN_AC15", 36 -> "PIN_AD15",
        37 -> "PIN_AC14", 38 -> "PIN_AD14", 39 -> "PIN_AF11", 40 -> "PIN_AF12",
        41 -> "PIN_AC10", 42 -> "PIN_AD10", 43 -> "PIN_AE9",  44 -> "PIN_AF9",
        45 -> "PIN_AF7",  46 -> "PIN_AF8",  47 -> "PIN_AE7",  48 -> "PIN_AF6",
        49 -> "PIN_AE5",  50 -> "PIN_AE6",  51 -> "PIN_AD5",  52 -> "PIN_AD6",
        53 -> "PIN_AF4",  54 -> "PIN_AF5",  55 -> "PIN_AD3",  56 -> "PIN_AE3",
        57 -> "PIN_AC4",  58 -> "PIN_AD4")))

  /**
   * CYC5000 / Trenz TEI0050 (Cyclone V E + W9864G6JT SDR SDRAM).
   *
   * FPGA: 5CEBA2U15C8 (UBGA324 package). Single board, direct FPGA pins.
   * Pin mappings verified against jop_cyc5000.qsf (working FPGA build).
   */
  def CYC5000 = Board(
    name = "cyc5000",
    probeAlias = Some("cyc5000"),
    consoleAlias = Some("cyc5000"),
    pllType = Some(PllType.AlteraCyc5000),
    entityTag = "Cyc5000",
    fpga = Some(FpgaDevice.`5CEBA2U15C8`),
    devices = Seq(
      BoardDevice("W9864G6JT", role = Some("sdr"), mapping = Map(
        "CLK" -> "PIN_P16", "CKE" -> "PIN_T14",
        "CS_n" -> "PIN_L13", "RAS_n" -> "PIN_P13",
        "CAS_n" -> "PIN_M14", "WE_n" -> "PIN_N12",
        "BA0" -> "PIN_T12", "BA1" -> "PIN_N13",
        "A0" -> "PIN_R13", "A1" -> "PIN_U12", "A2" -> "PIN_V12",
        "A3" -> "PIN_V13", "A4" -> "PIN_V15", "A5" -> "PIN_V16",
        "A6" -> "PIN_T16", "A7" -> "PIN_U15", "A8" -> "PIN_P14",
        "A9" -> "PIN_T15", "A10" -> "PIN_M13", "A11" -> "PIN_P15",
        "DQ0" -> "PIN_U4", "DQ1" -> "PIN_T4", "DQ2" -> "PIN_V6",
        "DQ3" -> "PIN_U5", "DQ4" -> "PIN_V7", "DQ5" -> "PIN_T5",
        "DQ6" -> "PIN_V8", "DQ7" -> "PIN_U8",
        "DQ8" -> "PIN_P10", "DQ9" -> "PIN_P9", "DQ10" -> "PIN_T11",
        "DQ11" -> "PIN_R9", "DQ12" -> "PIN_R11", "DQ13" -> "PIN_T9",
        "DQ14" -> "PIN_V10", "DQ15" -> "PIN_U9",
        "DQM0" -> "PIN_U13", "DQM1" -> "PIN_U14")),
      BoardDevice("CLOCK_12MHz", mapping = Map("clock" -> "PIN_F14")),
      BoardDevice("FT2232H", mapping = Map(
        "TXD" -> "PIN_F16", "RXD" -> "PIN_E18")),
      BoardDevice("LED", mapping = Map(
        "led0" -> "PIN_P4", "led1" -> "PIN_M4", "led2" -> "PIN_M3",
        "led3" -> "PIN_N3", "led4" -> "PIN_V2", "led5" -> "PIN_T2",
        "led6" -> "PIN_L1", "led7" -> "PIN_K1"))))

  /**
   * Colorlight i5 v7.0 module + its ext (breakout) board.
   *
   * FPGA: LFE5U-25F-6BG381C. Open-source toolchain only (yosys / nextpnr-ecp5 /
   * ecppack), so pin names are bare ECP5 ball designators as an .lpf wants them,
   * with no "PIN_" prefix — the Xilinx convention, not the Altera one.
   *
   * The UART is the CDC serial of the ext board's ARM mbed DAPLink, which is the
   * same USB device that carries JTAG. One cable does programming and download.
   * Pins are from Colorlight's own i5 examples (src/i5/uart_tx/top.lpf and
   * src/i5/picosoc/top.lpf both agree): TX=J17, RX=H18.
   *
   * Do NOT take the UART pins from riscvOnColorlight-5A-75B (U16 / R16). That is
   * the 5A-75B, a different module: on the i5, U16 is LED D2 and R16 is not
   * bonded out to the SODIMM at all.
   *
   * SDRAM (stage 2) is an EM638325BK-6H, 8 MB, and unusually is **32 bits wide**
   * with CKE tied high, CS tied low and all four DQM tied low. Two consequences:
   *   - no byte masking, so any sub-word write needs read-modify-write. JOP's
   *     main memory is word-addressed, so a JOP word is exactly one SDRAM access
   *     and nothing needs masking — this actually suits it.
   *   - BmbSdramCtrl32 is a 32-bit-BMB-to-16-bit-SDRAM bridge and is the wrong
   *     shape here; a 32-bit-wide path is needed instead.
   */
  def ColorlightI5 = Board(
    name = "colorlight-i5",
    probeAlias = Some("i5"),
    consoleAlias = Some("i5"),
    loaderBoard = Some("colorlight-i5"),
    pllType = Some(PllType.LatticeEcp5I5),
    entitySuffix = "I5",
    fpga = Some(FpgaDevice.LFE5U25F),
    devices = Seq(
      BoardDevice("CLOCK_25MHz", mapping = Map("clock" -> "P3")),
      // DAPLink CDC serial on the ext board
      BoardDevice("DAPLINK", mapping = Map(
        "TXD" -> "J17", "RXD" -> "H18")),
      BoardDevice("LED", mapping = Map("led0" -> "U16")),
      BoardDevice("EM638325BK6H", role = Some("sdr"), mapping = Map(
        "CLK" -> "B9", "RAS_n" -> "B10", "CAS_n" -> "A9", "WE_n" -> "A10",
        "BA0" -> "B11", "BA1" -> "C8",
        "A0" -> "B13", "A1" -> "C14", "A2" -> "A16", "A3" -> "A17",
        "A4" -> "B16", "A5" -> "B15", "A6" -> "A14", "A7" -> "A13",
        "A8" -> "A12", "A9" -> "A11", "A10" -> "B12",
        "DQ0" -> "B6", "DQ1" -> "A5", "DQ2" -> "A6", "DQ3" -> "A7",
        "DQ4" -> "C7", "DQ5" -> "B8", "DQ6" -> "B5", "DQ7" -> "A8",
        "DQ8" -> "D8", "DQ9" -> "D7", "DQ10" -> "E8", "DQ11" -> "D6",
        "DQ12" -> "C6", "DQ13" -> "D5", "DQ14" -> "E7", "DQ15" -> "C5",
        "DQ16" -> "C10", "DQ17" -> "D9", "DQ18" -> "E11", "DQ19" -> "D11",
        "DQ20" -> "C11", "DQ21" -> "D12", "DQ22" -> "E9", "DQ23" -> "C12",
        "DQ24" -> "E14", "DQ25" -> "C15", "DQ26" -> "E13", "DQ27" -> "D15",
        "DQ28" -> "E12", "DQ29" -> "B17", "DQ30" -> "D14", "DQ31" -> "D13"))),
    // CKE is strapped to VCC, CS to GND and all four DQM to GND on this board,
    // so none of them reach the FPGA. Parked on ext-board header balls (SODIMM
    // 69/71/73/75/77/79) that this design does not otherwise use; nothing is
    // attached to those headers.
    //
    // Consequence of DQM being strapped low: no byte masking exists, so every
    // write writes all four bytes. Safe only because JOP issues full-word
    // writes exclusively -- see BmbSdramCtrlWide's header comment.
    parkedPins = Map(
      "sdram_CKE" -> "U18", "sdram_CSn" -> "U17",
      "sdram_DQM[0]" -> "P18", "sdram_DQM[1]" -> "N17",
      "sdram_DQM[2]" -> "N18", "sdram_DQM[3]" -> "M18"))

  /**
   * Alchitry Au V2 (Artix-7 XC7A35T + MT41K128M16JT DDR3).
   *
   * J4 (80-pin DF40C, Bank A) and J5 (80-pin DF40C, Bank B) expansion connectors.
   * J3 (50-pin DF40C, Power/Control) carries LEDs, reset, JTAG — no user I/O.
   * Pin mapping from Alchitry-Labs-V2 AuV2Pin.kt (verified against schematic).
   *
   * DF40C connectors have pin mirroring between top (Au) and bottom (Io):
   * Au pin 1 mates with Io pin 2 and vice versa (odd↔even swap within each pair).
   * Device mappings on daughter boards reference Au pin numbers (pre-swapped).
   */
  def AlchitryAuV2 = Board(
    name = "alchitry-au-v2",
    consoleAlias = Some("alchitry"),
    fpga = Some(FpgaDevice.XC7A35T),
    pllType = Some(PllType.XilinxDdr3ClkWiz),
    ddr3HasCs = true,
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", role = Some("ddr3")),   // DDR3 pins managed by MIG IP
      BoardDevice("CLOCK_100MHz", mapping = Map("clock" -> "N14")),
      BoardDevice("FT2232H", mapping = Map(
        "TXD" -> "P16", "RXD" -> "P15")),
      BoardDevice("LED", mapping = Map(
        "led0" -> "K13", "led1" -> "K12", "led2" -> "L14",
        "led3" -> "L13", "led4" -> "M15", "led5" -> "M14",
        "led6" -> "M12", "led7" -> "P14")),
      BoardDevice("SWITCH", mapping = Map("reset" -> "P6"))),
    connectors = Map(
      // J4: Bank A (80-pin DF40C) — Bank 35 + Bank 14 partial
      "J4" -> Map(
        3 -> "N6",   4 -> "P9",   5 -> "M6",   6 -> "N9",
        9 -> "J1",  10 -> "L2",  11 -> "K1",  12 -> "L3",
       15 -> "H1",  16 -> "K2",  17 -> "H2",  18 -> "K3",
       21 -> "E1",  22 -> "H3",  23 -> "F2",  24 -> "J3",
       27 -> "G4",  28 -> "H4",  29 -> "G5",  30 -> "H5",
       33 -> "G1",  34 -> "J4",  35 -> "G2",  36 -> "J5",
       39 -> "C4",  40 -> "D3",  41 -> "D4",  42 -> "E3",
       45 -> "E5",  46 -> "F3",  47 -> "F5",  48 -> "F4",
       51 -> "A3",  52 -> "D5",  53 -> "B4",  54 -> "D6",
       57 -> "A4",  58 -> "B1",  59 -> "A5",  60 -> "C1",
       63 -> "D1",  64 -> "A2",  65 -> "E2",  66 -> "B2",
       69 -> "C2",  70 -> "C6",  71 -> "C3",  72 -> "C7",
       75 -> "B5",  76 -> "A7",  77 -> "B6",  78 -> "B7"),
      // J5: Bank B (80-pin DF40C) — Bank 14 partial + Bank 34 + Bank 15 partial
      "J5" -> Map(
        3 -> "T8",   4 -> "T10",  5 -> "T7",   6 -> "T9",
        9 -> "T5",  10 -> "T12", 11 -> "R5",  12 -> "R12",
       15 -> "R7",  16 -> "T13", 17 -> "R6",  18 -> "R13",
       21 -> "R8",  22 -> "T15", 23 -> "P8",  24 -> "T14",
       27 -> "R11", 28 -> "R16", 29 -> "R10", 30 -> "R15",
       33 -> "K5",  34 -> "N16", 35 -> "E6",  36 -> "M16",
       39 -> "P11", 40 -> "P13", 41 -> "P10", 42 -> "N13",
       45 -> "N12", 46 -> "D9",  47 -> "N11", 48 -> "D10",
       51 -> "M1",  52 -> "P1",  53 -> "M2",  54 -> "N1",
       57 -> "N2",  58 -> "R1",  59 -> "N3",  60 -> "R2",
       63 -> "P3",  64 -> "T2",  65 -> "P4",  66 -> "R3",
       69 -> "M4",  70 -> "T3",  71 -> "L4",  72 -> "T4",
       75 -> "L5",  76 -> "N4",  77 -> "P5",  78 -> "M5")))

  /**
   * QMTECH Wukong V3 (Artix-7 XC7A100T) — single board, direct FPGA pins.
   *
   * Pin assignments from qmtech-wukong-board.md (verified against QMTECH
   * Test10_SDRAM, Test08_GMII_Ethernet, and working JOP XDC constraints).
   * All peripherals on-board — no expansion connectors needed.
   */
  /**
   * A-E115FB: EP4CE115 core board + 1 GB DDR2 SODIMM.
   *
   * The DDR2 pins are owned by the ALTMEMPHY IP and constrained by the vendor
   * pin script (fpga/a-e115fb-ddr2/ddr2_pins.qsf), so they are not mapped here —
   * same arrangement as the DDR3 boards, whose pins the MIG owns.
   *
   * LEDs are active low, and note they are wired into 1.8 V banks shared with
   * DDR2. See docs/boards/ep4ce115-ddr2-board.md.
   */
  def AE115FB = Board(
    name = "a-e115fb",
    extraIpFiles = Seq("fpga/a-e115fb-ddr2/ip/ddr2_64bit/ddr2_64bit.qip"),
    // The DDR2 pin, I/O-standard and PHY assignments, lifted verbatim from the
    // vendor reference (DDR667_read_write/quartus/ddr2_sodimm.qsf). Sourced,
    // not restated -- see `constraintFiles`.
    constraintFiles = Seq("fpga/a-e115fb-ddr2/ddr2_pins.qsf"),
    probeAlias = Some("ae115fb"),
    consoleAlias = Some("ae115fb"),
    fpga = Some(FpgaDevice.EP4CE115F23I7),
    entitySuffix = "Ae115fb",
    ledActiveHigh = false,
    // Clock and reset, both of which this board was MISSING: PinResolver finds
    // the clock via a CLOCK_* device and the reset via a SWITCH, and AE115FB
    // declared neither -- so the generated project assigned neither, leaving
    // the 50 MHz input for the fitter to place wherever it liked. The
    // hand-written jop_ddr2.qsf had both. Nothing compared the two until the
    // generated project was diffed against it on 2026-08-26.
    portIoStandards = Map(
      "clk_in" -> "1.8 V", "reset" -> "1.8 V", "led" -> "1.8 V",
      "ser_txd" -> "3.3-V LVTTL", "ser_rxd" -> "3.3-V LVTTL"),
    reserveNceoAsIo = true,   // mem_addr[10] is on nCEO (K22)
    devices = Seq(
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "PIN_AB11")),
      BoardDevice("SWITCH", mapping = Map("reset" -> "PIN_N21")),
      BoardDevice("HYS64T128021", role = Some("ddr2")),
      // On-board CH340: FPGA TX -> CH340 RX is H5, CH340 TX -> FPGA RX is N1.
      // Both verified by loopback (commit a32434b), so no Pico bridge is needed
      // here — which is why that board's Pico could be switched to a blaster.
      BoardDevice("CH340", mapping = Map("TXD" -> "PIN_H5", "RXD" -> "PIN_N1")),
      // Core-board LEDs D3..D6, active low, in banks shared with the 1.8 V DDR2
      // interface. NOTE: the board auto-loads a factory EPCS demo at power-up
      // that also drives these, so they are unreliable as design status.
      BoardDevice("LED", mapping = Map(
        "led0" -> "PIN_A5", "led1" -> "PIN_B5", "led2" -> "PIN_C4", "led3" -> "PIN_C3"))
    ))

  def WukongXC7A100T = Board(
    name = "qmtech-wukong-xc7a100t",
    probeAlias = Some("wukong"),
    consoleAlias = Some("wukong"),
    loaderCable = Some("dirtyJtag"),
    fpga = Some(FpgaDevice.XC7A100T),
    pllType = Some(PllType.XilinxWukong),
    entitySuffix = "Wukong",
    ledActiveHigh = true,
    useStackCache = true,
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", role = Some("ddr3")),  // DDR3 pins managed by MIG IP
      // SDR SDRAM — Bank 14 (address/control) + Bank 15 (data)
      BoardDevice("W9825G6JH6", role = Some("sdr"), mapping = Map(
        "CLK" -> "G22", "CKE" -> "H22",
        "CS_n" -> "L25", "RAS_n" -> "K26",
        "CAS_n" -> "K25", "WE_n" -> "J26",
        "BA0" -> "M25", "BA1" -> "M26",
        "A0" -> "R26", "A1" -> "P25", "A2" -> "P26",
        "A3" -> "N26", "A4" -> "M24", "A5" -> "M22",
        "A6" -> "L24", "A7" -> "L23", "A8" -> "L22",
        "A9" -> "K21", "A10" -> "R25", "A11" -> "K22",
        "A12" -> "J21",
        "DQ0" -> "D25", "DQ1" -> "D26", "DQ2" -> "E25",
        "DQ3" -> "E26", "DQ4" -> "F25", "DQ5" -> "G25",
        "DQ6" -> "G26", "DQ7" -> "H26",
        "DQ8" -> "J24", "DQ9" -> "J23", "DQ10" -> "H24",
        "DQ11" -> "H23", "DQ12" -> "G24", "DQ13" -> "F24",
        "DQ14" -> "F23", "DQ15" -> "E23",
        "DQM0" -> "J25", "DQM1" -> "K23")),
      // Ethernet PHY — Bank 34, GMII (8-bit, 1 Gbps)
      BoardDevice("RTL8211EG", mapping = Map(
        "MDC" -> "H2", "MDIO" -> "H1",
        "RESET" -> "R1",
        "GTX_CLK" -> "U1", "TX_EN" -> "T2", "TX_ER" -> "J1",
        "TXD0" -> "R2", "TXD1" -> "P1", "TXD2" -> "N2", "TXD3" -> "N1",
        "TXD4" -> "M1", "TXD5" -> "L2", "TXD6" -> "K2", "TXD7" -> "K1",
        "RX_CLK" -> "P4", "RX_DV" -> "L3", "RX_ER" -> "U5",
        "RXD0" -> "M4", "RXD1" -> "N3", "RXD2" -> "N4", "RXD3" -> "P3",
        "RXD4" -> "R3", "RXD5" -> "T3", "RXD6" -> "T4", "RXD7" -> "T5",
        "COL" -> "U4", "CRS" -> "U2")),
      // SD card (microSD J9) — Bank 34 + Bank 35
      BoardDevice("SD_CARD", mapping = Map(
        "CLK" -> "L4", "CMD" -> "J8",
        "DAT0" -> "M5", "DAT1" -> "M7",
        "DAT2" -> "H6", "DAT3" -> "J6",
        "CD" -> "N6")),
      // HDMI — Bank 35, TMDS_33 + LVCMOS33 control
      BoardDevice("HDMI", mapping = Map(
        "CLK_P" -> "D4", "CLK_N" -> "C4",
        "D0_P" -> "E1", "D0_N" -> "D1",
        "D1_P" -> "F2", "D1_N" -> "E2",
        "D2_P" -> "G2", "D2_N" -> "G1",
        "SCL" -> "B2", "SDA" -> "A2",
        "HPD" -> "A3", "CEC" -> "B1")),
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "M21")),
      BoardDevice("CH340N", mapping = Map(
        "TXD" -> "E3", "RXD" -> "F3")),
      // UART brought out on the J11 header instead of the on-board CH340, for
      // rigs where the console is a Pico rather than the board's own USB
      // serial (which avoids two indistinguishable 1a86:7523 bridges on one
      // host). Directions are from the FPGA's point of view, so TXD lands on
      // the Pico's RX:
      //   J11.1 <- pico gpio4  (uart1 TX)  => FPGA RXD
      //   J11.2 -> pico gpio5  (uart1 RX)  => FPGA TXD
      //   J11.3 <- pico gpio12 (uart0 TX)  => FPGA RXD
      //   J11.4 -> pico gpio13 (uart0 RX)  => FPGA TXD
      // Getting these the wrong way round gives silence at every baud, which
      // looks exactly like a dead design -- it is not.
      //
      // Mapped THROUGH the J11 connector (declared below) rather than to FPGA
      // pins directly: J11 is a physical header, and what is plugged into it is
      // a property of the rig, not of the FPGA. A different adapter on J11 is
      // then a new BoardDevice against connector pins, with the header's
      // pin->ball mapping stated once.
      BoardDevice("PICO_UART0", mapping = Map("TXD" -> "J11:4", "RXD" -> "J11:3")),
      BoardDevice("PICO_UART1", mapping = Map("TXD" -> "J11:2", "RXD" -> "J11:1")),
      BoardDevice("LED", mapping = Map("led0" -> "G21", "led1" -> "G20")),
      BoardDevice("SWITCH", mapping = Map("key1" -> "M6", "reset" -> "H7"))),
    connectors = Map(
      // J11: 4-pin header. The Pico console rig mates here; see PICO_UART0/1.
      "J11" -> Map(1 -> "H4", 2 -> "F4", 3 -> "A4", 4 -> "A5")))

  /**
   * QMTECH XC7A100T FPGA core board (Artix-7 XC7A100T + MT41K128M16JT DDR3).
   *
   * Separate module that mates with DB_FPGA daughter board via U2/U4 headers.
   * U2 and U4 are 2x32 pin expansion headers (64 pins each).
   * Pins 1-2: GND, 3-4: 3V3, 5-58: I/O, 59-62: NC, 63-64: VIN.
   * U2 (Banks 13,14,15) mates with DB_FPGA J2. U4 (Banks 34,35) mates with DB_FPGA J3.
   * Pin-to-FPGA mapping from core board schematic QMTECH_XC7A75T_100T_200T-CORE-BOARD-V01.
   * On-board: DDR3 (MIG-managed), 50 MHz clock, 2 LEDs, SW2 reset.
   * No on-board UART — use DB_FPGA daughter board (V4 CP2102N or V5 RP2040).
   */
  def QmtechXC7A100T = Board(
    name = "qmtech-xc7a100t",
    probeAlias = Some("xc7a100t"),
    consoleAlias = Some("xc7a100t-pico-0"),
    loaderCable = Some("dirtyJtag"),
    fpga = Some(FpgaDevice.XC7A100T),
    pllType = Some(PllType.XilinxDdr3ClkWiz),
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", role = Some("ddr3")),   // DDR3 pins managed by MIG IP
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "U22")),
      BoardDevice("LED", mapping = Map("led0" -> "T23", "led1" -> "R23")),
      BoardDevice("SWITCH", mapping = Map("sw2" -> "P4"))),
    connectors = Map(
      // U2: Banks 13, 14, 15 — mates with DB_FPGA J2 when used with daughter board
      "U2" -> Map(
        5 -> "D26",  6 -> "E26",  7 -> "D25",  8 -> "E25",
        9 -> "G26",  10 -> "H26", 11 -> "E23", 12 -> "F23",
        13 -> "F22", 14 -> "G22", 15 -> "J26", 16 -> "J25",
        17 -> "G21", 18 -> "G20", 19 -> "H22", 20 -> "H21",
        21 -> "J21", 22 -> "K21", 23 -> "K26", 24 -> "K25",
        25 -> "K23", 26 -> "K22", 27 -> "M26", 28 -> "N26",
        29 -> "L23", 30 -> "L22", 31 -> "P26", 32 -> "R26",
        33 -> "M25", 34 -> "M24", 35 -> "N22", 36 -> "N21",
        37 -> "P24", 38 -> "P23", 39 -> "P25", 40 -> "R25",
        41 -> "T25", 42 -> "T24", 43 -> "V21", 44 -> "U21",
        45 -> "W23", 46 -> "V23", 47 -> "Y23", 48 -> "Y22",
        49 -> "AA25", 50 -> "Y25", 51 -> "AC24", 52 -> "AB24",
        53 -> "Y21", 54 -> "W21", 55 -> "Y26", 56 -> "W25",
        57 -> "AC26", 58 -> "AB26"),
      // U4: Banks 34, 35 — mates with DB_FPGA J3 when used with daughter board
      "U4" -> Map(
        5 -> "B5",  6 -> "A5",  7 -> "B4",  8 -> "A4",
        9 -> "A3",  10 -> "A2", 11 -> "D4", 12 -> "C4",
        13 -> "C2", 14 -> "B2", 15 -> "E5", 16 -> "D5",
        17 -> "C1", 18 -> "B1", 19 -> "E1", 20 -> "D1",
        21 -> "F2", 22 -> "E2", 23 -> "G4", 24 -> "F4",
        25 -> "G2", 26 -> "G1", 27 -> "J4", 28 -> "H4",
        29 -> "H2", 30 -> "H1", 31 -> "H9", 32 -> "G9",
        33 -> "M2", 34 -> "L2", 35 -> "L5", 36 -> "K5",
        37 -> "M4", 38 -> "L4", 39 -> "N3", 40 -> "N2",
        41 -> "M6", 42 -> "M5", 43 -> "K1", 44 -> "J1",
        45 -> "R3", 46 -> "P3", 47 -> "T4", 48 -> "T3",
        49 -> "P6", 50 -> "P5", 51 -> "N1", 52 -> "M1",
        53 -> "R1", 54 -> "P1", 55 -> "T2", 56 -> "R2",
        57 -> "U2", 58 -> "U1")))

  // ========================================================================
  // Carrier / daughter boards (no FPGA)
  // ========================================================================

  /**
   * QMTECH DB_FPGA V4 daughter board.
   *
   * All device signals map to J2/J3 connector pins that mate with the
   * core board's expansion headers. Pin assignments verified against
   * qmtech-ep4cgx150-db.qsf and jop_dbfpga.qsf (working FPGA builds).
   */
  def QmtechFpgaDbV4 = Board(
    name = "qmtech-fpga-db-v4",
    devices = Seq(
      // CP2102N USB-UART — on J2 (J2_IO15/IO16)
      BoardDevice("CP2102N", mapping = Map(
        "TXD" -> "J2:13", "RXD" -> "J2:14")),
      // RTL8211EG Ethernet PHY — full GMII 8-bit on J3
      BoardDevice("RTL8211EG", mapping = Map(
        "MDC" -> "J3:14", "MDIO" -> "J3:13",
        "RESET" -> "J3:24",
        "GTX_CLK" -> "J3:27",              // FPGA 125 MHz TX clock output
        "TX_CLK" -> "J3:20",               // PHY 25 MHz MII TX clock (unused in GMII)
        "TX_EN" -> "J3:26", "TX_ER" -> "J3:15",
        "TXD0" -> "J3:25", "TXD1" -> "J3:23", "TXD2" -> "J3:22", "TXD3" -> "J3:21",
        "TXD4" -> "J3:19", "TXD5" -> "J3:18", "TXD6" -> "J3:17", "TXD7" -> "J3:16",
        "RX_CLK" -> "J3:35", "RX_DV" -> "J3:40", "RX_ER" -> "J3:30",
        "RXD0" -> "J3:39", "RXD1" -> "J3:38", "RXD2" -> "J3:37", "RXD3" -> "J3:36",
        "RXD4" -> "J3:34", "RXD5" -> "J3:33", "RXD6" -> "J3:32", "RXD7" -> "J3:31")),
      // VGA DAC (5R-6G-5B resistor network) — on J3
      BoardDevice("VGA", mapping = Map(
        "HS" -> "J3:42", "VS" -> "J3:41",
        "R0" -> "J3:58", "R1" -> "J3:56", "R2" -> "J3:57", "R3" -> "J3:54", "R4" -> "J3:55",
        "G0" -> "J3:53", "G1" -> "J3:52", "G2" -> "J3:50", "G3" -> "J3:51",
        "G4" -> "J3:48", "G5" -> "J3:49",
        "B0" -> "J3:47", "B1" -> "J3:45", "B2" -> "J3:46", "B3" -> "J3:43", "B4" -> "J3:44")),
      // Micro SD card — on J3
      BoardDevice("SD_CARD", mapping = Map(
        "CLK" -> "J3:9", "CMD" -> "J3:10",
        "DAT0" -> "J3:8", "DAT1" -> "J3:7",
        "DAT2" -> "J3:12", "DAT3" -> "J3:11",
        "CD" -> "J3:6")),
      // 3-digit seven segment display — on J2 (J2_IO25-IO35)
      BoardDevice("SEVEN_SEG", mapping = Map(
        "SEL0" -> "J2:33", "SEL1" -> "J2:25", "SEL2" -> "J2:31",
        "A" -> "J2:29", "B" -> "J2:24",
        "C" -> "J2:26", "D" -> "J2:30",
        "E" -> "J2:32", "F" -> "J2:27",
        "G" -> "J2:23", "DP" -> "J2:28")),
      // LEDs (active low) — on J2 (J2_IO36-IO40)
      BoardDevice("LED", mapping = Map(
        "led2" -> "J2:38", "led3" -> "J2:37",
        "led4" -> "J2:36", "led5" -> "J2:35", "led6" -> "J2:34")),
      // PMOD J10 connector — routed through J2 (conflicts with ETH TX path)
      BoardDevice("PMOD_J10", mapping = Map(
        "pin1" -> "J2:15", "pin2" -> "J2:17",
        "pin3" -> "J2:19", "pin4" -> "J2:21",
        "pin7" -> "J2:16", "pin8" -> "J2:18",
        "pin9" -> "J2:20", "pin10" -> "J2:22")),
      // PMOD J11 connector — routed through J2 (conflicts with SD card)
      BoardDevice("PMOD_J11", mapping = Map(
        "pin1" -> "J2:5", "pin2" -> "J2:7",
        "pin3" -> "J2:9", "pin4" -> "J2:11",
        "pin7" -> "J2:6", "pin8" -> "J2:8",
        "pin9" -> "J2:10", "pin10" -> "J2:12"))))

  /**
   * Alchitry Io V2 daughter board.
   *
   * 24 LEDs, 24 DIP switches, 5 push buttons, 4-digit seven-segment display.
   * Mates with Au V2 via DF40C connectors: Io J2 ↔ Au J4 (Bank A),
   * Io J3 ↔ Au J5 (Bank B), Io J1 ↔ Au J3 (power only).
   *
   * Device mappings reference Au connector names/pins (J4, J5) because
   * DF40C mirroring swaps odd↔even pin numbers between top and bottom.
   * The Alchitry .acf format uses Au-side numbering (A3 = Au J4 pin 3).
   * Pin assignments from Alchitry Io V2 .acf (PINOUT V2).
   */
  def AlchitryIoV2 = Board(
    name = "alchitry-io-v2",
    devices = Seq(
      // 4-digit seven-segment display (active-low segments, common-anode via P-FET)
      BoardDevice("SEVEN_SEG_X4", mapping = Map(
        "A" -> "J4:9", "B" -> "J4:3", "C" -> "J4:21", "D" -> "J4:15",
        "E" -> "J4:11", "F" -> "J4:5", "G" -> "J4:23", "DP" -> "J4:17",
        "SEL0" -> "J4:4", "SEL1" -> "J4:6", "SEL2" -> "J4:12", "SEL3" -> "J4:10")),
      // 24 LEDs (active high, accent LEDs on Io board)
      BoardDevice("LED", mapping = Map(
        "led0" -> "J4:70", "led1" -> "J4:72", "led2" -> "J4:76", "led3" -> "J4:78",
        "led4" -> "J4:77", "led5" -> "J4:75", "led6" -> "J4:71", "led7" -> "J4:69",
        "led8" -> "J4:65", "led9" -> "J4:63", "led10" -> "J4:59", "led11" -> "J4:57",
        "led12" -> "J4:53", "led13" -> "J4:51", "led14" -> "J4:47", "led15" -> "J4:45",
        "led16" -> "J4:41", "led17" -> "J4:39", "led18" -> "J4:35", "led19" -> "J4:33",
        "led20" -> "J4:29", "led21" -> "J4:27", "led22" -> "J4:30", "led23" -> "J4:34")),
      // 24 DIP switches (active high with pull-down)
      BoardDevice("DIP_SWITCH", mapping = Map(
        "dip0" -> "J4:66", "dip1" -> "J4:64", "dip2" -> "J4:60", "dip3" -> "J4:58",
        "dip4" -> "J4:54", "dip5" -> "J4:52", "dip6" -> "J4:48", "dip7" -> "J4:46",
        "dip8" -> "J4:42", "dip9" -> "J4:40", "dip10" -> "J4:36", "dip11" -> "J5:18",
        "dip12" -> "J5:16", "dip13" -> "J5:12", "dip14" -> "J5:10", "dip15" -> "J5:6",
        "dip16" -> "J5:4", "dip17" -> "J5:3", "dip18" -> "J5:5", "dip19" -> "J5:9",
        "dip20" -> "J5:11", "dip21" -> "J5:15", "dip22" -> "J5:17", "dip23" -> "J5:21")),
      // 5 push buttons (active high with pull-down)
      BoardDevice("BUTTON", mapping = Map(
        "btn0" -> "J4:24", "btn1" -> "J4:22", "btn2" -> "J4:18",
        "btn3" -> "J4:16", "btn4" -> "J4:28"))))

  /**
   * QMTECH DB_FPGA V5 daughter board (with RP2040).
   *
   * Same peripheral pin assignments as V4 (Ethernet, VGA, SD on J3;
   * PMODs, JP1 on J2) except no 7-segment display or LEDs on V5.
   * Key difference: RP2040 replaces CP2102N
   * for USB-UART, and additionally provides DirtyJTAG for Xilinx FPGAs.
   *
   * UART pins differ from V4: RP2040 UART0 is on J3:5/6,
   * NOT J2:13/14 like V4's CP2102N. Different FPGA pins required.
   */
  def QmtechFpgaDbV5 = Board(
    name = "qmtech-fpga-db-v5",
    devices = Seq(
      // RP2040 DirtyJTAG + 2x UART (USB 1209:c0ca)
      // DirtyJTAG pins: TMS=GP19, TDI=GP16, TDO=GP17, TCK=GP18
      // UART0 (/dev/ttyACM0): GPIO0 (TX) → J3 pin 5 (J3_IO7), GPIO1 (RX) → J3 pin 6 (J3_IO8)
      // UART1 (/dev/ttyACM1): GPIO4 (TX) → J2 pin 40 (J2_IO42), GPIO5 (RX) → J2 pin 39 (J2_IO41)
      // UART1 on J2 — does NOT conflict with Ethernet (which is on J3)
      // (V4 CP2102N was on J2:13/14 — V5 RP2040 UART0 uses J3:5/6, different connector!)
      // Firmware: pico-dirtyJtag with BOARD_PICO config, CDC_UART_INTF_COUNT=2
      BoardDevice("RP2040", mapping = Map(
        "TXD" -> "J3:5", "RXD" -> "J3:6",
        "TXD1" -> "J2:40", "RXD1" -> "J2:39")),
      // RTL8211EG Ethernet PHY — full GMII 8-bit on J3 (same as V4)
      BoardDevice("RTL8211EG", mapping = Map(
        "MDC" -> "J3:14", "MDIO" -> "J3:13",
        "RESET" -> "J3:24",
        "GTX_CLK" -> "J3:27",
        "TX_CLK" -> "J3:20",
        "TX_EN" -> "J3:26", "TX_ER" -> "J3:15",
        "TXD0" -> "J3:25", "TXD1" -> "J3:23", "TXD2" -> "J3:22", "TXD3" -> "J3:21",
        "TXD4" -> "J3:19", "TXD5" -> "J3:18", "TXD6" -> "J3:17", "TXD7" -> "J3:16",
        "RX_CLK" -> "J3:35", "RX_DV" -> "J3:40", "RX_ER" -> "J3:30",
        "RXD0" -> "J3:39", "RXD1" -> "J3:38", "RXD2" -> "J3:37", "RXD3" -> "J3:36",
        "RXD4" -> "J3:34", "RXD5" -> "J3:33", "RXD6" -> "J3:32", "RXD7" -> "J3:31")),
      // VGA DAC (5R-6G-5B resistor network) — on J3 (same as V4)
      BoardDevice("VGA", mapping = Map(
        "HS" -> "J3:42", "VS" -> "J3:41",
        "R0" -> "J3:58", "R1" -> "J3:56", "R2" -> "J3:57", "R3" -> "J3:54", "R4" -> "J3:55",
        "G0" -> "J3:53", "G1" -> "J3:52", "G2" -> "J3:50", "G3" -> "J3:51",
        "G4" -> "J3:48", "G5" -> "J3:49",
        "B0" -> "J3:47", "B1" -> "J3:45", "B2" -> "J3:46", "B3" -> "J3:43", "B4" -> "J3:44")),
      // Micro SD card — on J3 (same as V4)
      BoardDevice("SD_CARD", mapping = Map(
        "CLK" -> "J3:9", "CMD" -> "J3:10",
        "DAT0" -> "J3:8", "DAT1" -> "J3:7",
        "DAT2" -> "J3:12", "DAT3" -> "J3:11",
        "CD" -> "J3:6")),
      // No 7-segment display on V5 (removed, was on V4)
      // No FPGA-accessible LEDs on V5 (only RP2040 LED on GPIO25)
      // No FPGA-accessible switches on V5 (only RP2040 BOOTSEL + RUN buttons)
      // PMOD J10 connector — routed through J2
      BoardDevice("PMOD_J10", mapping = Map(
        "pin1" -> "J2:15", "pin2" -> "J2:17",
        "pin3" -> "J2:19", "pin4" -> "J2:21",
        "pin7" -> "J2:16", "pin8" -> "J2:18",
        "pin9" -> "J2:20", "pin10" -> "J2:22")),
      // PMOD J11 connector — routed through J2 (conflicts with SD card on J3 via mating)
      BoardDevice("PMOD_J11", mapping = Map(
        "pin1" -> "J2:5", "pin2" -> "J2:7",
        "pin3" -> "J2:9", "pin4" -> "J2:11",
        "pin7" -> "J2:6", "pin8" -> "J2:8",
        "pin9" -> "J2:10", "pin10" -> "J2:12"))))

  /**
   * J11 UART adapter — second UART on the Wukong J11 header.
   *
   * Second UART for dual-subsystem designs, on header J11, wired to the
   * on-board Pico 2 W's uart0 so it appears as one of the Pico's CDC ports.
   *
   * Direction is from the FPGA's point of view, which is the opposite of the
   * Pico's: the Pico's TX drives an FPGA input.
   *
   *   J11.1 -> H4 <- Pico GP4  (uart1_tx)   FPGA RXD
   *   J11.2 -> F4 -> Pico GP5  (uart1_rx)   FPGA TXD
   *   J11.3 -> A4 <- Pico GP12 (uart0_tx)   FPGA RXD   <- used here
   *   J11.4 -> A5 -> Pico GP13 (uart0_rx)   FPGA TXD   <- used here
   *
   * Confirmed by loopback, not by reading a table: a jig looping all four
   * candidate pairs echoed on A4/A5 and nowhere else. D5/G5/G7/G8 are J10 —
   * the board doc lists J10's table first while J11 sits first on the silk, so
   * it is easy to read the wrong one.
   *
   * uart0 (GP12/13) is the pair to use: dirtyJtagConfig.h puts uart1 on GP4/5,
   * not GP8/9, so J11.1/.2 are not bridged by the current Pico firmware.
   *
   * This previously claimed J12 with TXD=U14/RXD=V14 and a SEL pin. Those pins
   * are not what is wired, and SEL was never consumed by any generator.
   */
  def J11UartAdapter = Board(
    name = "j11-uart-adapter",
    devices = Seq(BoardDevice("J11_UART", mapping = Map("TXD" -> "A5", "RXD" -> "A4"))))

  // ========================================================================
  // Composite board aliases
  // ========================================================================

  /** QMTECH EP4CGX150 module + DB_FPGA_V4 daughter board */
  def QmtechEP4CGX150_FPGA_DB_V4: Seq[Board] =
    Seq(QmtechEP4CGX150, QmtechFpgaDbV4)

  /** QMTECH EP4CGX150 module + DB_FPGA_V5 daughter board (with RP2040) */
  def QmtechEP4CGX150_FPGA_DB_V5: Seq[Board] =
    Seq(QmtechEP4CGX150, QmtechFpgaDbV5)

  /** QMTECH XC7A100T module + DB_FPGA_V4 daughter board */
  def QmtechXC7A100T_FPGA_DB_V4: Seq[Board] =
    Seq(QmtechXC7A100T, QmtechFpgaDbV4)

  /** QMTECH XC7A100T module + DB_FPGA_V5 daughter board (with RP2040) */
  def QmtechXC7A100T_FPGA_DB_V5: Seq[Board] =
    Seq(QmtechXC7A100T, QmtechFpgaDbV5)

  /** Alchitry Au V2 + Io V2 daughter board */
  def AlchitryAuV2_IoV2: Seq[Board] =
    Seq(AlchitryAuV2, AlchitryIoV2)

  // ========================================================================
  // Small FPGA boards (fit-check targets, placeholder pins)
  // ========================================================================

  /**
   * Arrow MAX1000 (MAX10 10M08SAE144C8G + W9864G6JT-6 SDR SDRAM).
   *
   * 12 MHz on-board oscillator, FT2232H USB-UART.
   *
   * PINS ARE REAL AND CROSS-CHECKED AGAINST TWO INDEPENDENT SOURCES:
   * the working jopmin project for this board
   * (/srv/git/jopmin/quartus/max1000/jop.qsf -- the first board JOP ran on),
   * and Trenz's own reference design
   * (TEI0001-test_board .../board_files/4/TEI0001_pin_assignments.tcl).
   * Every SDRAM, clock, UART and LED pin agrees between them, and Trenz
   * confirms the package as 10M08SAU169C8G.
   *
   * The UART needs its direction read carefully. Trenz names the FT2232H
   * channel-B pins raw -- BDBUS0=PIN_A4, BDBUS1=PIN_B4 -- and BDBUS0 is the
   * FT2232H's TXD, i.e. an INPUT to the FPGA. So the FPGA's ser_rxd is A4 and
   * its ser_txd is B4, which is what jopmin has and the opposite of what this
   * file said before.
   *
   * A[12] exists on the package (PIN_L11) and is deliberately unmapped: the
   * W9864G6JT is a 12-bit-address part, A0-A11. They were previously described as "placeholders not verified
   * against schematic", and were then deleted for being rejected by the
   * fitter. Both were wrong: the pins were right and the DEVICE was wrong.
   * 10M08SAU169C8G is a 169-ball UBGA; the model named the 144-pin EQFP, whose
   * pin names are entirely different, so every assignment was illegal.
   */
  def MAX1000 = Board(
    name = "max1000",
    fpga = Some(FpgaDevice.`10M08SAU169C8G`),
    pllType = Some(PllType.AlteraMax1000),
    entityTag = "Max1000Sdram",
    devices = Seq(
      // SDRAM: W9864G6JT-6, 8 MB, 16-bit. Absent entirely until now, so a
      // generated project silently left every DRAM pin unplaced.
      BoardDevice("W9864G6JT", role = Some("sdr"), mapping = Map(
        "CLK" -> "PIN_M9", "CKE" -> "PIN_M8",
        "CS_n" -> "PIN_M4", "RAS_n" -> "PIN_M7",
        "CAS_n" -> "PIN_N7", "WE_n" -> "PIN_K7",
        "BA0" -> "PIN_N6", "BA1" -> "PIN_K8",
        "A0" -> "PIN_K6", "A1" -> "PIN_M5", "A2" -> "PIN_N5",
        "A3" -> "PIN_J8", "A4" -> "PIN_N10", "A5" -> "PIN_M11",
        "A6" -> "PIN_N9", "A7" -> "PIN_L10", "A8" -> "PIN_M13",
        "A9" -> "PIN_N8", "A10" -> "PIN_N4", "A11" -> "PIN_M10",
        "DQ0" -> "PIN_D11", "DQ1" -> "PIN_G10", "DQ2" -> "PIN_F10",
        "DQ3" -> "PIN_F9", "DQ4" -> "PIN_E10", "DQ5" -> "PIN_D9",
        "DQ6" -> "PIN_G9", "DQ7" -> "PIN_F8",
        "DQ8" -> "PIN_F13", "DQ9" -> "PIN_E12", "DQ10" -> "PIN_E13",
        "DQ11" -> "PIN_D12", "DQ12" -> "PIN_C12", "DQ13" -> "PIN_B12",
        "DQ14" -> "PIN_B13", "DQ15" -> "PIN_A12",
        "DQM0" -> "PIN_E9", "DQM1" -> "PIN_F12")),
      BoardDevice("CLOCK_12MHz", mapping = Map("clock" -> "PIN_H6")),
      // TXD/RXD were SWAPPED here before: the FT2232H drives ser_rxd on A4 and
      // receives ser_txd on B4, not the other way round.
      BoardDevice("FT2232H", mapping = Map(
        "TXD" -> "PIN_B4", "RXD" -> "PIN_A4")),
      // All EIGHT, as the board has -- the old entry mapped two and said so.
      BoardDevice("LED", mapping = Map(
        "led0" -> "PIN_A8", "led1" -> "PIN_A9", "led2" -> "PIN_A11",
        "led3" -> "PIN_A10", "led4" -> "PIN_B10", "led5" -> "PIN_C9",
        "led6" -> "PIN_C10", "led7" -> "PIN_D8"))))

  /**
   * Generic EP4CE6 board (Cyclone IV E EP4CE6E22C8 + W9864G6JT SDR SDRAM).
   *
   * 50 MHz on-board oscillator. Minimal config.
   * Pin assignments are placeholders for fit-check — not verified against schematic.
   */
  def GenericEP4CE6 = Board(
    name = "generic-ep4ce6",
    fpga = Some(FpgaDevice.EP4CE6E22C8),
    pllType = Some(PllType.AlteraEp4ce6),
    entityTag = "Ep4ce6Sdram",
    devices = Seq(
      BoardDevice("W9864G6JT", role = Some("sdr")),
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "PIN_23")),
      BoardDevice("CP2102N", mapping = Map(
        "TXD" -> "PIN_114", "RXD" -> "PIN_115")),
      BoardDevice("LED", mapping = Map(
        "led0" -> "PIN_87", "led1" -> "PIN_86"))))
}

// ==========================================================================
// System Assembly
// ==========================================================================

/**
 * System assembly — a collection of boards forming the complete hardware.
 *
 * @param connectorMating Maps daughter board connector names to FPGA board connector names.
 *                        E.g., Map("J2" -> "U5", "J3" -> "U4") means DB_FPGA J2 mates with
 *                        EP4CGX150 U5, and DB_FPGA J3 mates with EP4CGX150 U4.
 *                        Used by resolvePin to translate connector references in device
 *                        mappings to the correct FPGA board connector.
 *                        Empty for single-board systems or when connector names already match.
 */
case class SystemAssembly(
  name: String,
  boards: Seq[Board],
  connectorMating: Map[String, String] = Map.empty
) {
  require(boards.exists(_.hasFpga), s"SystemAssembly '$name': at least one board must carry an FPGA")

  /** All FPGA devices across all boards */
  def fpgaDevices: Seq[FpgaDevice] = boards.flatMap(_.fpga)

  /** Primary FPGA (first one found) */
  def fpga: FpgaDevice = fpgaDevices.head

  /** FPGA family of the primary FPGA */
  def fpgaFamily: FpgaFamily = fpga.family

  /** The board carrying the primary FPGA */
  def fpgaBoard: Board = boards.find(_.hasFpga).get

  /** All devices across all boards */
  def allDevices: Seq[BoardDevice] = boards.flatMap(_.devices)

  /** Find a device by part name (across all boards) */
  def findDevice(part: String): Option[BoardDevice] =
    allDevices.find(_.part == part)

  /** Find a device by role (across all boards) */
  def findDeviceByRole(role: String): Option[BoardDevice] =
    allDevices.find(_.role.contains(role))

  /** All memory devices across all boards (resolved via MemoryDevice registry) */
  def memoryDevices: Seq[(BoardDevice, MemoryDevice)] =
    boards.flatMap(_.memoryDevices)

  /**
   * Resolve a pin reference to an FPGA pin name.
   *
   * Direct references ("PIN_A20", "N14") pass through unchanged.
   * Connector references ("J2:16") are resolved via connector mating
   * (daughter board connector → FPGA board connector) then pin lookup.
   * All pin numbers are physical connector pin numbers.
   */
  def resolvePin(ref: String): Option[String] = {
    if (ref.contains(":")) {
      val parts = ref.split(":")
      val daughterConnector = parts(0)
      val pin = parts(1).toInt
      // Map daughter board connector name to FPGA board connector name
      val fpgaConnector = connectorMating.getOrElse(daughterConnector, daughterConnector)
      fpgaBoard.connectors.get(fpgaConnector).flatMap(_.get(pin))
    } else {
      Some(ref)
    }
  }

  /**
   * Get resolved pin mapping for a device (signal → FPGA pin).
   *
   * Connector references are resolved through the FPGA board's connector
   * mapping. Returns only pins that successfully resolve.
   */
  def pinMapping(part: String): Map[String, String] =
    findDevice(part).map(_.mapping.flatMap { case (signal, ref) =>
      resolvePin(ref).map(signal -> _)
    }).getOrElse(Map.empty)

  /**
   * Get resolved pin mapping for ALL devices matching a part name, merged across boards.
   *
   * Useful for devices like "LED" that may span multiple boards (e.g., core board + DB).
   */
  def allPinMappings(part: String): Map[String, String] =
    allDevices.filter(_.part == part).flatMap(_.mapping.flatMap { case (signal, ref) =>
      resolvePin(ref).map(signal -> _)
    }).toMap

  /** Board oscillator frequency */
  def boardClockFreq: spinal.core.HertzNumber = fpgaBoard.clockFreq

  /** LED active-high vs active-low */
  def ledActiveHigh: Boolean = fpgaBoard.ledActiveHigh

  /** Total LED count across all boards in assembly */
  def totalLedCount: Int = allDevices.filter(_.part == "LED").flatMap(_.mapping.keys).size
}

object SystemAssembly {
  // Connector mating for QMTECH core boards + DB_FPGA daughter board.
  // DB_FPGA J2 mates with core board U5 (EP4CGX150) or U2 (XC7A100T).
  // DB_FPGA J3 mates with core board U4 (both boards).
  // Verified by physical inspection and QSF cross-reference.
  private val ep4cgx150DbMating = Map("J2" -> "U5", "J3" -> "U4")
  private val xc7a100tDbMating  = Map("J2" -> "U2", "J3" -> "U4")

  /** QMTECH EP4CGX150 + daughter board — primary dev platform */
  def qmtechWithDb = SystemAssembly("qmtech-ep4cgx150-db-v4",
    Board.QmtechEP4CGX150_FPGA_DB_V4, ep4cgx150DbMating)

  /** CYC5000 standalone */
  def cyc5000 = SystemAssembly("cyc5000", Seq(Board.CYC5000))

  /** Alchitry Au V2 standalone */
  def alchitryAuV2 = SystemAssembly("alchitry-au-v2", Seq(Board.AlchitryAuV2))

  /** Wukong standalone (two memories, dual-subsystem capable) */
  def wukong = SystemAssembly("wukong-xc7a100t", Seq(Board.WukongXC7A100T))

  /** A-E115FB standalone — EP4CE115 + 1 GB DDR2 SODIMM */
  def ae115fb = SystemAssembly("a-e115fb", Seq(Board.AE115FB))

  /** QMTECH XC7A100T core board + DB_FPGA_V4 daughter board */
  def xc7a100tWithDb = SystemAssembly("qmtech-xc7a100t-db-v4",
    Board.QmtechXC7A100T_FPGA_DB_V4, xc7a100tDbMating)

  /** QMTECH XC7A100T core board + DB_FPGA_V5 daughter board (with RP2040) */
  def xc7a100tWithDbV5 = SystemAssembly("qmtech-xc7a100t-db-v5",
    Board.QmtechXC7A100T_FPGA_DB_V5, xc7a100tDbMating)

  /** QMTECH EP4CGX150 + DB_FPGA_V5 daughter board (with RP2040) */
  def qmtechWithDbV5 = SystemAssembly("qmtech-ep4cgx150-db-v5",
    Board.QmtechEP4CGX150_FPGA_DB_V5, ep4cgx150DbMating)

  /** Alchitry Au V2 + Io V2 daughter board */
  def alchitryAuV2WithIo = SystemAssembly("alchitry-au-v2-io-v2",
    Board.AlchitryAuV2_IoV2)

  /** Wukong + J11 UART adapter (dual-subsystem: DDR3 + SDR with separate UARTs) */
  def wukongWithJ11Uart = SystemAssembly("wukong-xc7a100t-dual",
    Seq(Board.WukongXC7A100T, Board.J11UartAdapter))

  /** Arrow MAX1000 standalone */
  def max1000 = SystemAssembly("max1000", Seq(Board.MAX1000))

  /** Generic EP4CE6 standalone */
  def genericEp4ce6 = SystemAssembly("generic-ep4ce6", Seq(Board.GenericEP4CE6))

  /** Colorlight i5 v7.0 module + ext board (modelled as one board: the ext board
    * carries no devices of its own beyond the DAPLink, whose serial pins are
    * already in the module's pin space) */
  def colorlightI5 = SystemAssembly("colorlight-i5", Seq(Board.ColorlightI5))
}
