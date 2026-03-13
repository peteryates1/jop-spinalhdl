package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class JopConfigTest extends AnyFunSuite {

  test("ep4cgx150Serial preset is valid") {
    val config = JopConfig.ep4cgx150Serial
    assert(config.systems.length == 1)
    assert(config.system.name == "main")
    assert(config.system.memory == "W9825G6JH6")
    assert(config.system.bootMode == BootMode.Serial)
    assert(config.system.clkFreq == (80 MHz))
    assert(config.system.cpuCnt == 1)
    assert(config.fpgaFamily == FpgaFamily.CycloneIV)
    assert(config.resolveMemory(config.system).isDefined)
    assert(config.resolveMemory(config.system).get.memType == MemoryType.SDRAM_SDR)
  }

  test("ep4cgx150Smp preset is valid") {
    val config = JopConfig.ep4cgx150Smp(4)
    assert(config.system.cpuCnt == 4)
    assert(config.system.name == "smp4")
  }

  test("ep4cgx150HwMath preset has IntegerComputeUnit") {
    val config = JopConfig.ep4cgx150HwMath
    assert(config.system.coreConfig.needsIntegerCompute)
    assert(config.system.coreConfig.needsIntDiv)
    assert(!config.system.coreConfig.needsFloatCompute)
  }

  test("ep4cgx150HwFloat preset has both compute units") {
    val config = JopConfig.ep4cgx150HwFloat
    assert(config.system.coreConfig.needsIntegerCompute)
    assert(config.system.coreConfig.needsFloatCompute)
  }

  test("cyc5000 preset resolves memory") {
    val config = JopConfig.cyc5000Serial
    val mem = config.resolveMemory(config.system)
    assert(mem.isDefined)
    assert(mem.get.name == "W9864G6JT")
    assert(mem.get.sizeBytes == 8L * 1024 * 1024)
  }

  test("CYC5000 pin mappings match QSF (5CEBA2U15C8)") {
    val config = JopConfig.cyc5000Serial
    assert(config.fpga.name == "5CEBA2U15C8")
    assert(config.fpgaFamily == FpgaFamily.CycloneV)
    val asm = config.assembly
    // Clock (12 MHz on-board oscillator)
    assert(asm.pinMapping("CLOCK_12MHz")("clock") == "PIN_F14")
    // UART (FT2232H on-board USB)
    assert(asm.pinMapping("FT2232H")("TXD") == "PIN_F16")
    assert(asm.pinMapping("FT2232H")("RXD") == "PIN_E18")
    // SDRAM — spot-check key signals
    val sdram = asm.pinMapping("W9864G6JT")
    assert(sdram("CLK") == "PIN_P16")
    assert(sdram("CS_n") == "PIN_L13")
    assert(sdram("A0") == "PIN_R13")
    assert(sdram("DQ0") == "PIN_U4")
    // 8 LEDs
    val leds = asm.allPinMappings("LED")
    assert(leds.size == 8)
    assert(leds("led0") == "PIN_P4")
    assert(leds("led7") == "PIN_K1")
  }

  test("auSerial preset resolves DDR3 memory") {
    val config = JopConfig.auSerial
    val mem = config.resolveMemory(config.system)
    assert(mem.isDefined)
    assert(mem.get.memType == MemoryType.SDRAM_DDR3)
    assert(mem.get.sizeBytes == 256L * 1024 * 1024)
  }

  test("wukongDual has two systems with different memories") {
    val config = JopConfig.wukongDual
    assert(config.systems.length == 2)
    val compute = config.systems.find(_.name == "compute").get
    val io = config.systems.find(_.name == "io").get
    assert(compute.memory == "ddr3")
    assert(io.memory == "sdr")
    assert(compute.cpuCnt == 4)
    assert(io.cpuCnt == 2)
    assert(compute.coreConfig.needsFloatCompute)
    assert(!io.coreConfig.needsFloatCompute)
    assert(config.interconnect.isDefined)
    assert(config.monitors.length == 1)
  }

  test("wukongDual resolves memories by role") {
    val config = JopConfig.wukongDual
    val compute = config.systems.find(_.name == "compute").get
    val io = config.systems.find(_.name == "io").get
    val ddr3 = config.resolveMemory(compute)
    val sdr = config.resolveMemory(io)
    assert(ddr3.isDefined)
    assert(ddr3.get.memType == MemoryType.SDRAM_DDR3)
    assert(sdr.isDefined)
    assert(sdr.get.memType == MemoryType.SDRAM_SDR)
  }

  test("minimum preset has no compute units") {
    val config = JopConfig.minimum
    // imul: Microcode uses pure-microcode shift-and-add (imul_sw), no IntegerComputeUnit.
    assert(config.system.coreConfig.impl("imul") == Implementation.Microcode)
    assert(config.system.coreConfig.impl("idiv") == Implementation.Java)
    assert(config.system.coreConfig.impl("irem") == Implementation.Java)
    assert(!config.system.coreConfig.needsIntegerCompute)
    assert(!config.system.coreConfig.needsFloatCompute)
    assert(!config.system.coreConfig.needsIntMul)
    assert(!config.system.coreConfig.needsIntDiv)
  }

  test("simulation preset is valid") {
    val config = JopConfig.simulation
    assert(config.system.bootMode == BootMode.Simulation)
    assert(config.system.clkFreq == (100 MHz))
  }

  test("invalid memory reference fails") {
    assertThrows[IllegalArgumentException] {
      JopConfig(
        assembly = SystemAssembly.qmtechWithDb,
        systems = Seq(JopSystem(
          name = "bad",
          memory = "NONEXISTENT_RAM",
          bootMode = BootMode.Serial,
          clkFreq = 100 MHz)))
    }
  }

  test("invalid devicePart fails") {
    assertThrows[IllegalArgumentException] {
      JopConfig(
        assembly = SystemAssembly.cyc5000,   // no CP2102N on CYC5000
        systems = Seq(JopSystem(
          name = "bad",
          memory = "W9864G6JT",
          bootMode = BootMode.Serial,
          clkFreq = 100 MHz,
          devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))
    }
  }

  test("SystemAssembly finds devices across boards") {
    val asm = SystemAssembly.qmtechWithDb
    assert(asm.findDevice("W9825G6JH6").isDefined)   // on FPGA board
    assert(asm.findDevice("CP2102N").isDefined)       // on daughter board
    assert(asm.findDevice("RTL8211EG").isDefined)     // on daughter board
    assert(asm.findDevice("NONEXISTENT").isEmpty)
  }

  test("SystemAssembly finds devices by role") {
    val asm = SystemAssembly.wukong
    assert(asm.findDeviceByRole("ddr3").isDefined)
    assert(asm.findDeviceByRole("sdr").isDefined)
    assert(asm.findDeviceByRole("nonexistent").isEmpty)
  }

  test("Board memory device resolution") {
    val board = Board.QmtechEP4CGX150
    val mems = board.memoryDevices
    assert(mems.length == 1)
    assert(mems.head._2.name == "W9825G6JH6")
    assert(mems.head._2.memType == MemoryType.SDRAM_SDR)
  }

  test("Wukong board has two memory devices") {
    val board = Board.WukongXC7A100T
    val mems = board.memoryDevices
    assert(mems.length == 2)
    val types = mems.map(_._2.memType).toSet
    assert(types.contains(MemoryType.SDRAM_SDR))
    assert(types.contains(MemoryType.SDRAM_DDR3))
  }

  test("perCoreConfigs length mismatch fails") {
    assertThrows[IllegalArgumentException] {
      JopSystem(
        name = "bad",
        memory = "W9825G6JH6",
        bootMode = BootMode.Serial,
        clkFreq = 100 MHz,
        cpuCnt = 2,
        perCoreConfigs = Some(Seq(JopCoreConfig())))  // length 1 != cpuCnt 2
    }
  }

  test("heterogeneous cores") {
    val config = JopConfig(
      assembly = SystemAssembly.qmtechWithDb,
      systems = Seq(JopSystem(
        name = "hetero",
        memory = "W9825G6JH6",
        bootMode = BootMode.Serial,
        clkFreq = 80 MHz,
        cpuCnt = 2,
        perCoreConfigs = Some(Seq(
          JopCoreConfig(bytecodes = Map("idiv" -> "hw", "irem" -> "hw")),
          JopCoreConfig())),
        devices = Map("uart" -> DeviceInstance("uart", devicePart = Some("CP2102N"))))))
    assert(config.system.coreConfigs.length == 2)
    assert(config.system.coreConfigs(0).needsIntegerCompute)  // idiv=Hardware
    assert(!config.system.coreConfigs(1).needsIntegerCompute) // all Microcode/Java, no Hardware
    // System-level: needs ICU because core 0 needs it
    assert(config.system.needsIntegerCompute)
  }

  test("memoryTypes returns distinct types") {
    val config = JopConfig.wukongDual
    val types = config.memoryTypes
    assert(types.contains(MemoryType.SDRAM_DDR3))
    assert(types.contains(MemoryType.SDRAM_SDR))
    assert(types.length == 2)
  }

  test("builder pattern with copy") {
    val base = JopConfig.ep4cgx150Serial
    val custom = base.copy(
      systems = Seq(base.system.copy(
        cpuCnt = 8,
        clkFreq = 100 MHz)))
    assert(custom.system.cpuCnt == 8)
    assert(custom.system.clkFreq == (100 MHz))
    assert(custom.assembly.name == "qmtech-ep4cgx150-db-v4")  // unchanged
  }

  // ==========================================================================
  // Connector pin resolution tests
  // ==========================================================================

  test("QMTECH connector pin resolution (EP4CGX150 + DB)") {
    val asm = SystemAssembly.qmtechWithDb
    // CP2102N UART on daughter board, via J3 connector
    val uart = asm.pinMapping("CP2102N")
    assert(uart("TXD") == "PIN_AD20")   // J3:13 → PIN_AD20
    assert(uart("RXD") == "PIN_AE21")   // J3:14 → PIN_AE21
    // Ethernet PHY via J2 connector
    val eth = asm.pinMapping("RTL8211EG")
    assert(eth("MDC") == "PIN_A20")     // J2:14 → PIN_A20
    assert(eth("RX_CLK") == "PIN_B10")  // J2:35 → PIN_B10
  }

  test("QMTECH connector pin resolution (XC7A100T + DB)") {
    val asm = SystemAssembly.xc7a100tWithDb
    val uart = asm.pinMapping("CP2102N")
    // J3:13 → C2, J3:14 → B2 on XC7A100T core board
    assert(uart("TXD") == "C2")
    assert(uart("RXD") == "B2")
  }

  test("direct FPGA pin passes through unchanged") {
    val asm = SystemAssembly.qmtechWithDb
    // SDRAM is on the FPGA board with direct pin references
    val sdram = asm.pinMapping("W9825G6JH6")
    assert(sdram("CLK") == "PIN_E22")  // direct, not via connector
    assert(sdram("DQ0") == "PIN_B25")
  }

  test("Alchitry Au V2 connector pin resolution") {
    val asm = SystemAssembly.alchitryAuV2WithIo
    // Io V2 seven segment via J4 connector
    val seg = asm.pinMapping("SEVEN_SEG_X4")
    assert(seg("A") == "J1")     // J4:9 → J1 (FPGA pin)
    assert(seg("B") == "N6")     // J4:3 → N6
    assert(seg("SEL0") == "P9")  // J4:4 → P9
    // Io V2 button via J4
    val btn = asm.pinMapping("BUTTON")
    assert(btn("btn0") == "J3")  // J4:24 → J3 (FPGA pin)
    // Io V2 DIP switch crossing to J5
    val dip = asm.pinMapping("DIP_SWITCH")
    assert(dip("dip11") == "R13")  // J5:18 → R13
    assert(dip("dip16") == "T10")  // J5:4 → T10
  }

  test("Au V2 LED pin values match XDC") {
    val au = Board.AlchitryAuV2
    val leds = au.findDevice("LED").get.mapping
    assert(leds("led0") == "K13")
    assert(leds("led4") == "M15")  // was wrong (M16), fixed
    assert(leds("led7") == "P14")  // was wrong (N16), fixed
  }

  test("unresolvable connector reference returns None") {
    val asm = SystemAssembly.alchitryAuV2  // standalone, no Io
    assert(asm.resolvePin("J4:3").contains("N6"))   // valid connector pin
    assert(asm.resolvePin("J4:1").isEmpty)           // GND pin, not in map
    assert(asm.resolvePin("J6:1").isEmpty)            // nonexistent connector
  }

  // ==========================================================================
  // PinResolver.devicePins tests
  // ==========================================================================

  test("devicePins resolves UART from devicePart") {
    import jop.generate.PinResolver
    val config = JopConfig.ep4cgx150Serial
    val pins = PinResolver.devicePins(config.assembly, config.system.effectiveDevices)
    val pinMap = pins.map(p => p.verilogPort -> p.fpgaPin).toMap
    assert(pinMap("ser_txd") == "PIN_AD20")
    assert(pinMap("ser_rxd") == "PIN_AE21")
  }

  test("devicePins resolves full Wukong (UART + Eth + SD)") {
    import jop.generate.PinResolver
    val config = JopConfig.wukongFull
    val pins = PinResolver.devicePins(config.assembly, config.system.effectiveDevices)
    val pinMap = pins.map(p => p.verilogPort -> p.fpgaPin).toMap
    // UART (CH340N on Wukong)
    assert(pinMap("ser_txd") == "E3")
    assert(pinMap("ser_rxd") == "F3")
    // Ethernet GMII
    assert(pinMap("e_gtxc") == "U1")
    assert(pinMap("e_mdc") == "H2")
    assert(pinMap("e_txd[7]") == "K1")  // GMII 8-bit
    // SD Native
    assert(pinMap("sd_clk") == "L4")
    assert(pinMap("sd_cmd") == "J8")
  }

  test("devicePins resolves all peripherals for wukongFull") {
    import jop.generate.PinResolver
    val config = JopConfig.wukongFull
    val pins = PinResolver.devicePins(config.assembly, config.system.effectiveDevices)
    val pinMap = pins.map(p => p.verilogPort -> p.fpgaPin).toMap
    // UART + Ethernet (GMII) + SD Native all resolved
    assert(pinMap.contains("ser_txd"))
    assert(pinMap.contains("e_gtxc"))
    assert(pinMap.contains("sd_clk"))
    assert(pins.size > 30)  // UART(2) + GMII(~20) + SD(7) = ~29 pins
  }
}
