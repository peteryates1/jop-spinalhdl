package jop.system

import org.scalatest.funsuite.AnyFunSuite

class JopConfigTest extends AnyFunSuite {

  test("qmtechSerial preset is valid") {
    val config = JopConfig.qmtechSerial
    assert(config.systems.length == 1)
    assert(config.system.name == "main")
    assert(config.system.memory == "W9825G6JH6")
    assert(config.system.bootMode == BootMode.Serial)
    assert(config.system.clkFreqHz == 80000000L)
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
    assert(config.system.clkFreqHz == 100000000L)
  }

  test("invalid memory reference fails") {
    assertThrows[IllegalArgumentException] {
      JopConfig(
        assembly = SystemAssembly.qmtechWithDb,
        systems = Seq(JopSystem(
          name = "bad",
          memory = "NONEXISTENT_RAM",
          bootMode = BootMode.Serial,
          clkFreqHz = 100000000L)))
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
          clkFreqHz = 100000000L,
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
        clkFreqHz = 100000000L,
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
        clkFreqHz = 80000000L,
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
        clkFreqHz = 100000000L)))
    assert(custom.system.cpuCnt == 8)
    assert(custom.system.clkFreqHz == 100000000L)
    assert(custom.assembly.name == "qmtech-ep4cgx150-db-v4")  // unchanged
  }
}
