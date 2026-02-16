package jop.memory

import spinal.core._

/**
 * MainMemorySim Testbench
 *
 * Wraps MainMemorySim for CocoTB testing with:
 * - Standard clock/reset interface
 * - Simple test patterns for verification
 */
case class MainMemorySimTb(
  config: MainMemorySimConfig = MainMemorySimConfig(),
  testData: Option[Seq[BigInt]] = None
) extends Component {

  val io = new Bundle {
    // Clock and reset (exposed for CocoTB)
    val clk = in Bool()
    val reset = in Bool()

    // Memory interface
    val addr = in UInt(config.addrWidth bits)
    val din  = in Bits(config.dataWidth bits)
    val dout = out Bits(config.dataWidth bits)
    val ncs  = in Bool()
    val noe  = in Bool()
    val nwr  = in Bool()

    // Status
    val ready = out Bool()
  }

  // Create clock domain from external signals
  val tbClockDomain = ClockDomain(
    clock = io.clk,
    reset = io.reset,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // Memory under test
  val memArea = new ClockingArea(tbClockDomain) {
    val mem = MainMemorySim(config, testData)

    // Connect IO
    mem.io.addr := io.addr
    mem.io.din := io.din
    io.dout := mem.io.dout
    mem.io.ncs := io.ncs
    mem.io.noe := io.noe
    mem.io.nwr := io.nwr
    io.ready := mem.io.ready
  }
}

/**
 * MainMemorySimTb with pre-initialized test data
 */
object MainMemorySimTb {

  /** Create testbench with a simple ascending pattern */
  def withAscendingPattern(memSize: Int = 256): MainMemorySimTb = {
    val testData = (0 until memSize).map(i => BigInt(i))
    MainMemorySimTb(
      config = MainMemorySimConfig(memSize = memSize),
      testData = Some(testData)
    )
  }

  /** Create testbench with specific test values */
  def withTestValues(values: Seq[BigInt], memSize: Int = 1024): MainMemorySimTb = {
    MainMemorySimTb(
      config = MainMemorySimConfig(memSize = memSize),
      testData = Some(values.padTo(memSize, BigInt(0)))
    )
  }

  def main(args: Array[String]): Unit = {
    // Generate with simple ascending pattern
    val testData = (0 until 256).map(i => BigInt(i))

    SpinalConfig(
      mode = VHDL,
      targetDirectory = "generated"
    ).generate(MainMemorySimTb(
      config = MainMemorySimConfig(memSize = 256),
      testData = Some(testData)
    ))
  }
}
