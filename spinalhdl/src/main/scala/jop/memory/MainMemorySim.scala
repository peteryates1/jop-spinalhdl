package jop.memory

import spinal.core._

/**
 * Main Memory Simulation Configuration
 *
 * @param addrWidth Address width (default: 18 = 256KB address space)
 * @param dataWidth Data width (default: 32 bits)
 * @param memSize   Memory size in words (default: 64K words = 256KB)
 */
case class MainMemorySimConfig(
  addrWidth: Int = 18,
  dataWidth: Int = 32,
  memSize: Int = 64 * 1024  // 64K words = 256KB
) {
  require(addrWidth >= 16 && addrWidth <= 24, "Address width must be 16-24 bits")
  require(dataWidth == 32, "Data width must be 32 bits")
  require(memSize <= (1 << addrWidth), "Memory size exceeds address space")
}

/**
 * Simulated Main Memory Component
 *
 * Provides a simple synchronous memory interface for simulation and testing.
 * Can be initialized with data from .jop files or mem_rom.dat files.
 *
 * Memory Interface (active-low control signals matching SRAM conventions):
 * - ncs: Chip select (active low)
 * - noe: Output enable (active low)
 * - nwr: Write enable (active low)
 *
 * Read operation:  ncs=0, noe=0, nwr=1
 * Write operation: ncs=0, noe=1, nwr=0
 *
 * @param config Memory configuration
 * @param init   Optional initialization data
 */
case class MainMemorySim(
  config: MainMemorySimConfig = MainMemorySimConfig(),
  init: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Address bus
    val addr = in UInt(config.addrWidth bits)

    // Data bus (active low control signals match SRAM conventions)
    val din  = in Bits(config.dataWidth bits)
    val dout = out Bits(config.dataWidth bits)

    // Control signals (active low)
    val ncs = in Bool()   // Chip select (active low)
    val noe = in Bool()   // Output enable (active low)
    val nwr = in Bool()   // Write enable (active low)

    // Status signals
    val ready = out Bool()  // Memory access complete (always ready for sync mem)
  }

  // ==========================================================================
  // Memory Array
  // ==========================================================================

  val mem = Mem(Bits(config.dataWidth bits), config.memSize)

  // Initialize from provided data
  init.foreach { data =>
    val paddedData = data.padTo(config.memSize, BigInt(0))
    mem.init(paddedData.map(v => B(v, config.dataWidth bits)))
  }

  // ==========================================================================
  // Read/Write Logic
  // ==========================================================================

  // Registered address for synchronous operation
  val addrReg = Reg(UInt(config.addrWidth bits)) init(0)
  when(!io.ncs) {
    addrReg := io.addr
  }

  // Write logic (synchronous)
  when(!io.ncs && !io.nwr) {
    mem.write(io.addr.resize(log2Up(config.memSize)), io.din)
  }

  // Read logic (synchronous read, but output immediately available after clock)
  val readData = mem.readSync(
    address = io.addr.resize(log2Up(config.memSize)),
    enable = !io.ncs
  )

  // Output enable control
  io.dout := Mux(!io.ncs && !io.noe,
    readData,
    B(0, config.dataWidth bits)
  )

  // Always ready (synchronous memory)
  io.ready := True
}

/**
 * MainMemorySim Companion Object
 */
object MainMemorySim {
  def main(args: Array[String]): Unit = {
    SpinalConfig(
      mode = Verilog,
      targetDirectory = "generated"
    ).generate(MainMemorySim())
  }
}
