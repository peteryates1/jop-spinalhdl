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
  mapping: Map[String, String] = Map.empty     // device signal → pin reference
)

// ==========================================================================
// PLL Type — which PLL family a board uses
// ==========================================================================

sealed trait PllType
object PllType {
  case object AlteraDramPll extends PllType      // EP4CGX150 (Cyclone IV GX)
  case object AlteraCyc5000 extends PllType      // CYC5000 (Cyclone V)
  case object AlteraMax1000 extends PllType      // MAX1000 (MAX10)
  case object AlteraEp4ce6 extends PllType       // EP4CE6 (Cyclone IV E)
  case object XilinxWukong extends PllType       // Wukong XC7A100T (SDR/DDR3/BRAM variants)
  case object XilinxDdr3ClkWiz extends PllType   // Au V2, QmtechXC7A100T (DDR3 ClkWiz)
}

// ==========================================================================
// Board
// ==========================================================================

/**
 * A physical PCB — any board in the system.
 *
 * @param connectors Expansion header pin-to-FPGA-pin mapping (FPGA boards only).
 *                   Key: connector name ("J2", "J3").
 *                   Value: physical pin number → FPGA pin name.
 *                   Pins 1-4 (power/GND) and 59+ (NC/VIN) are excluded.
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
  ddr3HasCs: Boolean = false,
  hasEthPll: Boolean = false,
  useStackCache: Boolean = false
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
   * J2 and J3 are 2x32 pin expansion headers (64 pins each).
   * Pins 1-2: GND, 3-4: 3V3, 5-58: I/O, 59-62: NC, 63-64: VIN.
   * Pin-to-FPGA mapping from core board schematic QMTECH-EP4CGX150GX-CORE-BOARD-V01.
   * SDRAM pins verified against jop_sdram.qsf (working FPGA build).
   */
  def QmtechEP4CGX150 = Board(
    name = "qmtech-ep4cgx150",
    fpga = Some(FpgaDevice.EP4CGX150DF27I7),
    pllType = Some(PllType.AlteraDramPll),
    hasEthPll = true,
    devices = Seq(
      // On-board SDRAM (direct FPGA pins, verified against jop_sdram.qsf)
      BoardDevice("W9825G6JH6", mapping = Map(
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
      BoardDevice("SWITCH", mapping = Map("sw0" -> "PIN_AD23", "sw1" -> "PIN_AD24"))),
    connectors = Map(
      // J2: Banks 5, 6, 7 — from core board schematic sheet 2
      "J2" -> Map(
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
      // J3: Banks 3, 4 — from core board schematic sheet 2
      "J3" -> Map(
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
    pllType = Some(PllType.AlteraCyc5000),
    entityTag = "Cyc5000",
    fpga = Some(FpgaDevice.`5CEBA2U15C8`),
    devices = Seq(
      BoardDevice("W9864G6JT", mapping = Map(
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
    fpga = Some(FpgaDevice.XC7A35T),
    pllType = Some(PllType.XilinxDdr3ClkWiz),
    ddr3HasCs = true,
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K"),   // DDR3 pins managed by MIG IP
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
  def WukongXC7A100T = Board(
    name = "qmtech-wukong-xc7a100t",
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
      BoardDevice("LED", mapping = Map("led0" -> "G21", "led1" -> "G20")),
      BoardDevice("SWITCH", mapping = Map("key1" -> "M6", "reset" -> "H7"))))

  /**
   * QMTECH XC7A100T FPGA core board (Artix-7 XC7A100T + MT41K128M16JT DDR3).
   *
   * Separate module that mates with DB_FPGA daughter board via J2/J3 headers.
   * J2/J3 are 2x32 pin expansion headers (64 pins each).
   * Pin-to-FPGA mapping from core board schematic QMTECH_XC7A75T_100T_200T-CORE-BOARD-V01.
   * On-board: DDR3 (MIG-managed), 50 MHz clock, CH340N UART, 2 LEDs, SW2 reset.
   */
  def QmtechXC7A100T = Board(
    name = "qmtech-xc7a100t",
    fpga = Some(FpgaDevice.XC7A100T),
    pllType = Some(PllType.XilinxDdr3ClkWiz),
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K"),   // DDR3 pins managed by MIG IP
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "U22")),
      BoardDevice("CH340N", mapping = Map(
        "TXD" -> "E3", "RXD" -> "F3")),
      BoardDevice("LED", mapping = Map("led0" -> "T23", "led1" -> "R23")),
      BoardDevice("SWITCH", mapping = Map("sw2" -> "P4"))),
    connectors = Map(
      // J2: Banks 13, 14, 15 — from core board schematic sheet 1
      "J2" -> Map(
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
      // J3: Banks 34, 35 — from core board schematic sheet 1
      "J3" -> Map(
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
      // CP2102N USB-UART — on J3
      BoardDevice("CP2102N", mapping = Map(
        "TXD" -> "J3:13", "RXD" -> "J3:14")),
      // RTL8211EG Ethernet PHY — full GMII 8-bit on J2
      BoardDevice("RTL8211EG", mapping = Map(
        "MDC" -> "J2:14", "MDIO" -> "J2:13",
        "RESET" -> "J2:24",
        "GTX_CLK" -> "J2:27",              // FPGA 125 MHz TX clock output
        "TX_CLK" -> "J2:20",               // PHY 25 MHz MII TX clock (unused in GMII)
        "TX_EN" -> "J2:26", "TX_ER" -> "J2:15",
        "TXD0" -> "J2:25", "TXD1" -> "J2:23", "TXD2" -> "J2:22", "TXD3" -> "J2:21",
        "TXD4" -> "J2:19", "TXD5" -> "J2:18", "TXD6" -> "J2:17", "TXD7" -> "J2:16",
        "RX_CLK" -> "J2:35", "RX_DV" -> "J2:40", "RX_ER" -> "J2:30",
        "RXD0" -> "J2:39", "RXD1" -> "J2:38", "RXD2" -> "J2:37", "RXD3" -> "J2:36",
        "RXD4" -> "J2:34", "RXD5" -> "J2:33", "RXD6" -> "J2:32", "RXD7" -> "J2:31")),
      // VGA DAC (5R-6G-5B resistor network) — on J2
      BoardDevice("VGA", mapping = Map(
        "HS" -> "J2:42", "VS" -> "J2:41",
        "R0" -> "J2:58", "R1" -> "J2:56", "R2" -> "J2:57", "R3" -> "J2:54", "R4" -> "J2:55",
        "G0" -> "J2:53", "G1" -> "J2:52", "G2" -> "J2:50", "G3" -> "J2:51",
        "G4" -> "J2:48", "G5" -> "J2:49",
        "B0" -> "J2:47", "B1" -> "J2:45", "B2" -> "J2:46", "B3" -> "J2:43", "B4" -> "J2:44")),
      // Micro SD card — on J2
      BoardDevice("SD_CARD", mapping = Map(
        "CLK" -> "J2:9", "CMD" -> "J2:10",
        "DAT0" -> "J2:8", "DAT1" -> "J2:7",
        "DAT2" -> "J2:12", "DAT3" -> "J2:11",
        "CD" -> "J2:6")),
      // 3-digit seven segment display — on J3
      BoardDevice("SEVEN_SEG", mapping = Map(
        "SEL0" -> "J3:33", "SEL1" -> "J3:25", "SEL2" -> "J3:31",
        "A" -> "J3:29", "B" -> "J3:24",
        "C" -> "J3:26", "D" -> "J3:30",
        "E" -> "J3:32", "F" -> "J3:27",
        "G" -> "J3:23", "DP" -> "J3:28")),
      // LEDs (active low) — on J3
      BoardDevice("LED", mapping = Map(
        "led2" -> "J3:38", "led3" -> "J3:37",
        "led4" -> "J3:36", "led5" -> "J3:35", "led6" -> "J3:34")),
      // PMOD J10 connector — routed through J3
      BoardDevice("PMOD_J10", mapping = Map(
        "pin1" -> "J3:16", "pin2" -> "J3:18",
        "pin3" -> "J3:20", "pin4" -> "J3:22",
        "pin5" -> "J3:15", "pin6" -> "J3:17",
        "pin7" -> "J3:19", "pin8" -> "J3:21")),
      // PMOD J11 connector — routed through J3
      BoardDevice("PMOD_J11", mapping = Map(
        "pin1" -> "J3:6", "pin2" -> "J3:8",
        "pin3" -> "J3:10", "pin4" -> "J3:12",
        "pin5" -> "J3:5", "pin6" -> "J3:7",
        "pin7" -> "J3:9", "pin8" -> "J3:11"))))

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

  // ========================================================================
  // Composite board aliases
  // ========================================================================

  /** QMTECH EP4CGX150 module + DB_FPGA_V4 daughter board */
  def QmtechEP4CGX150_FPGA_DB_V4: Seq[Board] =
    Seq(QmtechEP4CGX150, QmtechFpgaDbV4)

  /** QMTECH XC7A100T module + DB_FPGA_V4 daughter board */
  def QmtechXC7A100T_FPGA_DB_V4: Seq[Board] =
    Seq(QmtechXC7A100T, QmtechFpgaDbV4)

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
   * Pin assignments are placeholders for fit-check — not verified against schematic.
   */
  def MAX1000 = Board(
    name = "max1000",
    fpga = Some(FpgaDevice.`10M08SAE144C8G`),
    pllType = Some(PllType.AlteraMax1000),
    entityTag = "Max1000Sdram",
    devices = Seq(
      BoardDevice("W9864G6JT"),
      BoardDevice("CLOCK_12MHz", mapping = Map("clock" -> "PIN_H6")),
      BoardDevice("FT2232H", mapping = Map(
        "TXD" -> "PIN_A4", "RXD" -> "PIN_B4")),
      BoardDevice("LED", mapping = Map(
        "led0" -> "PIN_A8", "led1" -> "PIN_A9"))))

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
      BoardDevice("W9864G6JT"),
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "PIN_23")),
      BoardDevice("CP2102N", mapping = Map(
        "TXD" -> "PIN_114", "RXD" -> "PIN_115")),
      BoardDevice("LED", mapping = Map(
        "led0" -> "PIN_87", "led1" -> "PIN_86"))))
}

// ==========================================================================
// System Assembly
// ==========================================================================

/** System assembly — a collection of boards forming the complete hardware */
case class SystemAssembly(
  name: String,
  boards: Seq[Board]
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
   * Connector references ("J2:14") are resolved via the FPGA board's
   * connector mapping.
   */
  def resolvePin(ref: String): Option[String] = {
    if (ref.contains(":")) {
      val parts = ref.split(":")
      val connector = parts(0)
      val pin = parts(1).toInt
      fpgaBoard.connectors.get(connector).flatMap(_.get(pin))
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
  /** QMTECH EP4CGX150 + daughter board — primary dev platform */
  def qmtechWithDb = SystemAssembly("qmtech-ep4cgx150-db-v4",
    Board.QmtechEP4CGX150_FPGA_DB_V4)

  /** CYC5000 standalone */
  def cyc5000 = SystemAssembly("cyc5000", Seq(Board.CYC5000))

  /** Alchitry Au V2 standalone */
  def alchitryAuV2 = SystemAssembly("alchitry-au-v2", Seq(Board.AlchitryAuV2))

  /** Wukong standalone (two memories, dual-subsystem capable) */
  def wukong = SystemAssembly("wukong-xc7a100t", Seq(Board.WukongXC7A100T))

  /** QMTECH XC7A100T core board + daughter board */
  def xc7a100tWithDb = SystemAssembly("qmtech-xc7a100t-db-v4",
    Board.QmtechXC7A100T_FPGA_DB_V4)

  /** Alchitry Au V2 + Io V2 daughter board */
  def alchitryAuV2WithIo = SystemAssembly("alchitry-au-v2-io-v2",
    Board.AlchitryAuV2_IoV2)

  /** Arrow MAX1000 standalone */
  def max1000 = SystemAssembly("max1000", Seq(Board.MAX1000))

  /** Generic EP4CE6 standalone */
  def genericEp4ce6 = SystemAssembly("generic-ep4ce6", Seq(Board.GenericEP4CE6))
}
