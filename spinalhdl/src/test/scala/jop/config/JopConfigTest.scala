package jop.config

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class JopConfigTest extends AnyFunSuite {

  test("qmtechSerial preset is valid") {
    val config = JopConfig.qmtechSerial
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

  test("qmtechSmp preset is valid") {
    val config = JopConfig.qmtechSmp(4)
    assert(config.system.cpuCnt == 4)
    assert(config.system.name == "smp4")
  }

  test("qmtechHwMath preset has IntegerComputeUnit") {
    val config = JopConfig.qmtechHwMath
    assert(config.system.coreConfig.needsIntegerCompute)
    assert(config.system.coreConfig.needsIntDiv)
    assert(!config.system.coreConfig.needsFloatCompute)
  }

  test("qmtechHwFloat preset has both compute units") {
    val config = JopConfig.qmtechHwFloat
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

  test("minimum preset has no float compute, minimal integer") {
    val config = JopConfig.minimum
    // imul: Microcode currently means IntCU radix-4 multiply (needsIntegerCompute = true)
    // When superset ROM has both imul handlers, Microcode will mean pure software
    // and needsIntegerCompute will check for Hardware only.
    assert(config.system.coreConfig.imul == Implementation.Microcode)
    assert(config.system.coreConfig.idiv == Implementation.Java)
    assert(config.system.coreConfig.irem == Implementation.Java)
    assert(!config.system.coreConfig.needsFloatCompute)
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

  test("invalid driver device fails") {
    assertThrows[IllegalArgumentException] {
      JopConfig(
        assembly = SystemAssembly.cyc5000,   // no CP2102N on CYC5000
        systems = Seq(JopSystem(
          name = "bad",
          memory = "W9864G6JT",
          bootMode = BootMode.Serial,
          clkFreq = 100 MHz,
          drivers = Seq(DeviceDriver.Uart)))) // CP2102N driver, but board has FT2232H
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
          JopCoreConfig(imul = Implementation.Microcode, idiv = Implementation.Hardware, irem = Implementation.Hardware),
          JopCoreConfig(imul = Implementation.Java, idiv = Implementation.Java, irem = Implementation.Java))),
        drivers = Seq(DeviceDriver.Uart))))
    assert(config.system.coreConfigs.length == 2)
    assert(config.system.coreConfigs(0).needsIntegerCompute)  // imul=Microcode + idiv=Hardware
    assert(!config.system.coreConfigs(1).needsIntegerCompute) // all Java
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
    val base = JopConfig.qmtechSerial
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
}
