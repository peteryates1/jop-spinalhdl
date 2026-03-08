package jop.system

/**
 * Board — Physical PCB with Devices and Pin Mappings
 *
 * Models the physical hardware hierarchy:
 *   BoardDevice — a component on a PCB with signal-to-FPGA-pin mapping
 *   Board       — a PCB (may or may not carry an FPGA)
 *   SystemAssembly — a collection of boards that form the complete hardware
 *
 * No distinction between "FPGA board" and "carrier board" at the type level.
 * A board either has an FPGA (fpga = Some(...)) or doesn't.
 * Pin mappings are resolved through the board chain.
 */

// ==========================================================================
// Board Devices
// ==========================================================================

/** A device mounted on a board with its signal-to-FPGA-pin mapping */
case class BoardDevice(
  part: String,                                // part number or device name
  role: Option[String] = None,                 // optional role disambiguation ("sdram_main")
  mapping: Map[String, String] = Map.empty     // device signal → FPGA pin
)

// ==========================================================================
// Device Drivers — SpinalHDL component variant selection
// ==========================================================================

/**
 * Maps a physical device to a SpinalHDL component variant.
 *
 * Some devices have multiple driver variants (e.g., SD card via SPI vs native).
 * The JOP system config selects which variant to use.
 */
sealed trait DeviceDriver {
  def devicePart: String        // which physical device this drives
  def componentName: String     // SpinalHDL component class name
}

object DeviceDriver {
  // UART
  case object Uart extends DeviceDriver {
    val devicePart = "CP2102N"
    val componentName = "BmbUart"
  }
  case object UartFt2232 extends DeviceDriver {
    val devicePart = "FT2232H"
    val componentName = "BmbUart"
  }

  // Ethernet
  case object EthMii extends DeviceDriver {
    val devicePart = "RTL8211EG"
    val componentName = "BmbEth"
  }
  case object EthGmii extends DeviceDriver {
    val devicePart = "RTL8211EG"
    val componentName = "BmbEth"  // same component, ethGmii=true in IoConfig
  }

  // SD card — two variants
  case object SdSpi extends DeviceDriver {
    val devicePart = "SD_CARD"
    val componentName = "BmbSdSpi"
  }
  case object SdNative extends DeviceDriver {
    val devicePart = "SD_CARD"
    val componentName = "BmbSdNative"
  }

  // VGA — two variants
  case object VgaDma extends DeviceDriver {
    val devicePart = "VGA"
    val componentName = "BmbVgaDma"
  }
  case object VgaText extends DeviceDriver {
    val devicePart = "VGA"
    val componentName = "BmbVgaText"
  }

  // Config flash (EPCS/EPCQ)
  case object ConfigFlash extends DeviceDriver {
    val devicePart = "CONFIG_FLASH"
    val componentName = "BmbConfigFlash"
  }
}

// ==========================================================================
// Board
// ==========================================================================

/** A physical PCB — any board in the system */
case class Board(
  name: String,
  fpga: Option[FpgaDevice] = None,
  devices: Seq[BoardDevice] = Seq.empty,
  connectors: Seq[String] = Seq.empty
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
}

object Board {
  // ========================================================================
  // FPGA modules
  // ========================================================================

  /** QMTECH EP4CGX150 FPGA module (Cyclone IV GX + W9825G6JH6 SDR SDRAM) */
  def QmtechEP4CGX150 = Board(
    name = "qmtech-ep4cgx150",
    fpga = Some(FpgaDevice.EP4CGX150DF27I7),
    devices = Seq(
      BoardDevice("W9825G6JH6", mapping = Map(
        "CLK" -> "PIN_E22", "CKE" -> "PIN_K24",
        "CS_n" -> "PIN_H26", "RAS_n" -> "PIN_H25",
        "CAS_n" -> "PIN_G26", "WE_n" -> "PIN_G25",
        "BA0" -> "PIN_J25", "BA1" -> "PIN_J26",
        "A0" -> "PIN_L25", "A1" -> "PIN_L26", "A2" -> "PIN_N25",
        "A3" -> "PIN_N26", "A4" -> "PIN_P25", "A5" -> "PIN_P26",
        "A6" -> "PIN_R25", "A7" -> "PIN_R26", "A8" -> "PIN_T26",
        "A9" -> "PIN_K26", "A10" -> "PIN_N24", "A11" -> "PIN_U26",
        "A12" -> "PIN_V25",
        "DQ0" -> "PIN_B25", "DQ1" -> "PIN_B26", "DQ2" -> "PIN_C25",
        "DQ3" -> "PIN_C26", "DQ4" -> "PIN_D25", "DQ5" -> "PIN_D26",
        "DQ6" -> "PIN_E25", "DQ7" -> "PIN_E26",
        "DQ8" -> "PIN_G23", "DQ9" -> "PIN_G24", "DQ10" -> "PIN_H23",
        "DQ11" -> "PIN_H24", "DQ12" -> "PIN_F24", "DQ13" -> "PIN_F25",
        "DQ14" -> "PIN_F23", "DQ15" -> "PIN_G22",
        "DQM0" -> "PIN_F26", "DQM1" -> "PIN_H24")),
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "PIN_B14")),
      BoardDevice("LED", mapping = Map("led0" -> "PIN_A25", "led1" -> "PIN_A24")),
      BoardDevice("SWITCH", mapping = Map("sw0" -> "PIN_AD23", "sw1" -> "PIN_AD24")),
      BoardDevice("CONFIG_FLASH", mapping = Map(
        "DCLK" -> "PIN_C1", "DATA0" -> "PIN_D2",
        "nCSO" -> "PIN_E2", "ASDO" -> "PIN_D1"))),
    connectors = Seq("J2", "J3"))

  /** CYC5000 (Cyclone V + W9864G6JT SDR SDRAM) */
  def CYC5000 = Board(
    name = "cyc5000",
    fpga = Some(FpgaDevice.`5CEBA2F17A7`),
    devices = Seq(
      BoardDevice("W9864G6JT", mapping = Map(
        "CLK" -> "PIN_R14", "CKE" -> "PIN_K16",
        "CS_n" -> "PIN_N16", "RAS_n" -> "PIN_L14",
        "CAS_n" -> "PIN_M14", "WE_n" -> "PIN_L13",
        "BA0" -> "PIN_L16", "BA1" -> "PIN_L15",
        "A0" -> "PIN_N14", "A1" -> "PIN_M12", "A2" -> "PIN_P14",
        "A3" -> "PIN_P13", "A4" -> "PIN_P12", "A5" -> "PIN_R12",
        "A6" -> "PIN_R11", "A7" -> "PIN_R10", "A8" -> "PIN_P9",
        "A9" -> "PIN_M16", "A10" -> "PIN_P8", "A11" -> "PIN_P11",
        "DQ0" -> "PIN_J12", "DQ1" -> "PIN_J13", "DQ2" -> "PIN_K12",
        "DQ3" -> "PIN_L11", "DQ4" -> "PIN_K11", "DQ5" -> "PIN_J11",
        "DQ6" -> "PIN_G11", "DQ7" -> "PIN_F14",
        "DQ8" -> "PIN_N9", "DQ9" -> "PIN_L10", "DQ10" -> "PIN_M11",
        "DQ11" -> "PIN_M10", "DQ12" -> "PIN_P7", "DQ13" -> "PIN_R6",
        "DQ14" -> "PIN_R5", "DQ15" -> "PIN_R4",
        "DQM0" -> "PIN_K13", "DQM1" -> "PIN_N8")),
      BoardDevice("CLOCK_12MHz", mapping = Map("clock" -> "PIN_M2")),
      BoardDevice("FT2232H", mapping = Map(
        "TXD" -> "PIN_A16", "RXD" -> "PIN_C15")),
      BoardDevice("LED", mapping = Map(
        "led0" -> "PIN_M6", "led1" -> "PIN_N6", "led2" -> "PIN_N5",
        "led3" -> "PIN_N4", "led4" -> "PIN_M4"))))

  /** Alchitry Au V2 (Artix-7 XC7A35T + MT41K128M16JT DDR3) */
  def AlchitryAuV2 = Board(
    name = "alchitry-au-v2",
    fpga = Some(FpgaDevice.XC7A35T),
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K"),   // DDR3 pins managed by MIG IP
      BoardDevice("CLOCK_100MHz", mapping = Map("clock" -> "N14")),
      BoardDevice("FT2232H", mapping = Map(
        "TXD" -> "P16", "RXD" -> "P15")),
      BoardDevice("LED", mapping = Map(
        "led0" -> "K13", "led1" -> "K12", "led2" -> "L14",
        "led3" -> "L13", "led4" -> "M16", "led5" -> "M14",
        "led6" -> "M12", "led7" -> "N16"))))

  /** QMTECH Wukong V3 (Artix-7 XC7A100T + DDR3 + SDR SDRAM + peripherals) */
  def WukongXC7A100T = Board(
    name = "qmtech-wukong-xc7a100t",
    fpga = Some(FpgaDevice.XC7A100T),
    devices = Seq(
      BoardDevice("MT41K128M16JT-125:K", role = Some("ddr3")),  // DDR3 pins managed by MIG IP
      BoardDevice("W9825G6JH6", role = Some("sdr"), mapping = Map(
        "CLK" -> "H4", "CKE" -> "J6",
        "CS_n" -> "G3", "RAS_n" -> "J3",
        "CAS_n" -> "H3", "WE_n" -> "K3",
        "BA0" -> "G6", "BA1" -> "H6",
        "A0" -> "K5", "A1" -> "K6", "A2" -> "M2",
        "A3" -> "M3", "A4" -> "L4", "A5" -> "L5",
        "A6" -> "L6", "A7" -> "N5", "A8" -> "P6",
        "A9" -> "J4", "A10" -> "K4", "A11" -> "P5",
        "A12" -> "N6",
        "DQ0" -> "G1", "DQ1" -> "G2", "DQ2" -> "H1",
        "DQ3" -> "H2", "DQ4" -> "J1", "DQ5" -> "J2",
        "DQ6" -> "K1", "DQ7" -> "K2",
        "DQ8" -> "L1", "DQ9" -> "L2", "DQ10" -> "L3",
        "DQ11" -> "M1", "DQ12" -> "N2", "DQ13" -> "N3",
        "DQ14" -> "N1", "DQ15" -> "P1",
        "DQM0" -> "F3", "DQM1" -> "G4")),
      BoardDevice("RTL8211EG", mapping = Map(
        "MDC" -> "R1", "MDIO" -> "U2",
        "RESET" -> "U1",
        "TX_CLK" -> "T2", "TX_EN" -> "T3",
        "TXD0" -> "R2", "TXD1" -> "P2", "TXD2" -> "P3", "TXD3" -> "N4",
        "RX_CLK" -> "V4", "RX_DV" -> "T5",
        "RXD0" -> "V5", "RXD1" -> "U5", "RXD2" -> "V2", "RXD3" -> "V3")),
      BoardDevice("SD_CARD", mapping = Map(
        "CLK" -> "AB22", "CMD" -> "Y22",
        "DAT0" -> "AA22", "DAT1" -> "Y21",
        "DAT2" -> "W21", "DAT3" -> "V22")),
      BoardDevice("HDMI", mapping = Map(
        "CLK_P" -> "D4", "CLK_N" -> "C4",
        "D0_P" -> "E1", "D0_N" -> "D1",
        "D1_P" -> "F2", "D1_N" -> "E2",
        "D2_P" -> "G4", "D2_N" -> "F4")),
      BoardDevice("CLOCK_50MHz", mapping = Map("clock" -> "F22")),
      BoardDevice("LED", mapping = Map("led0" -> "J26", "led1" -> "H26")),
      BoardDevice("SWITCH", mapping = Map("sw0" -> "H22", "sw1" -> "J22"))))

  // ========================================================================
  // Carrier / daughter boards (no FPGA)
  // ========================================================================

  /** QMTECH DB_FPGA V4 daughter board */
  def QmtechFpgaDbV4 = Board(
    name = "qmtech-fpga-db-v4",
    devices = Seq(
      BoardDevice("CP2102N", mapping = Map(
        "RXD" -> "PIN_AE21", "TXD" -> "PIN_AD20")),
      BoardDevice("RTL8211EG", mapping = Map(
        "MDC" -> "PIN_A20", "MDIO" -> "PIN_A21",
        "RESET" -> "PIN_A15",
        "TX_CLK" -> "PIN_C19", "TX_EN" -> "PIN_B17",
        "TXD0" -> "PIN_B19", "TXD1" -> "PIN_B18", "TXD2" -> "PIN_A18", "TXD3" -> "PIN_A17",
        "RX_CLK" -> "PIN_B20", "RX_DV" -> "PIN_D18",
        "RXD0" -> "PIN_C21", "RXD1" -> "PIN_C20", "RXD2" -> "PIN_D19", "RXD3" -> "PIN_C18")),
      BoardDevice("VGA", mapping = Map(
        "HS" -> "PIN_A6", "VS" -> "PIN_A7",
        "R0" -> "PIN_E1", "R1" -> "PIN_E4", "R2" -> "PIN_D5", "R3" -> "PIN_C6", "R4" -> "PIN_A5",
        "G0" -> "PIN_D6", "G1" -> "PIN_A8", "G2" -> "PIN_B7", "G3" -> "PIN_A9", "G4" -> "PIN_B8", "G5" -> "PIN_C8",
        "B0" -> "PIN_A10", "B1" -> "PIN_A11", "B2" -> "PIN_B10", "B3" -> "PIN_C10", "B4" -> "PIN_A12")),
      BoardDevice("SD_CARD", mapping = Map(
        "CLK" -> "PIN_B21", "CMD" -> "PIN_A22",
        "DAT0" -> "PIN_A23", "DAT1" -> "PIN_B22",
        "DAT2" -> "PIN_B23", "DAT3" -> "PIN_B24")),
      BoardDevice("SEVEN_SEG", mapping = Map(
        "SEL0" -> "PIN_B3", "SEL1" -> "PIN_A3",
        "SEL2" -> "PIN_B4", "SEL3" -> "PIN_A4",
        "SEL4" -> "PIN_B5", "SEL5" -> "PIN_A5",
        "A" -> "PIN_B13", "B" -> "PIN_A13",
        "C" -> "PIN_B14", "D" -> "PIN_C14",
        "E" -> "PIN_A14", "F" -> "PIN_A15",
        "G" -> "PIN_B16", "DP" -> "PIN_A16")),
      BoardDevice("LED", mapping = Map(
        "led2" -> "PIN_AD14", "led3" -> "PIN_AC14",
        "led4" -> "PIN_AD15", "led5" -> "PIN_AE15",
        "led6" -> "PIN_AC17")),
      BoardDevice("PMOD_J10", mapping = Map(
        "pin1" -> "PIN_AD8", "pin2" -> "PIN_AD9",
        "pin3" -> "PIN_AE7", "pin4" -> "PIN_AE8",
        "pin7" -> "PIN_AE9", "pin8" -> "PIN_AF9",
        "pin9" -> "PIN_AF10", "pin10" -> "PIN_AD10")),
      BoardDevice("PMOD_J11", mapping = Map(
        "pin1" -> "PIN_AD11", "pin2" -> "PIN_AF11",
        "pin3" -> "PIN_AE12", "pin4" -> "PIN_AF12",
        "pin7" -> "PIN_AD12", "pin8" -> "PIN_AE13",
        "pin9" -> "PIN_AF13", "pin10" -> "PIN_AD13"))),
    connectors = Seq("J2", "J3"))

  // ========================================================================
  // Composite board aliases
  // ========================================================================

  /** QMTECH EP4CGX150 module + DB_FPGA_V4 daughter board */
  def QmtechEP4CGX150_FPGA_DB_V4: Seq[Board] =
    Seq(QmtechEP4CGX150, QmtechFpgaDbV4)
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

  /** Get pin mapping for a device part (across all boards) */
  def pinMapping(part: String): Map[String, String] =
    findDevice(part).map(_.mapping).getOrElse(Map.empty)
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
}
