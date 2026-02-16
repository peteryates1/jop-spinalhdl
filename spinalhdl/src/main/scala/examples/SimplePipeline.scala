package examples

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/**
 * Simple 2-Stage Pipeline Prototype
 *
 * Purpose: Learn SpinalHDL Pipeline API basics (v1.12.2)
 *
 * Pipeline Structure:
 * - Node 0 (Fetch): Read two values from memory
 * - Node 1 (Execute): Add the two values
 *
 * This demonstrates:
 * - Creating pipeline nodes using Node()
 * - Defining payloads (data flowing through pipeline)
 * - Connecting nodes with StageLink (automatic register insertion)
 * - Accessing payloads in different nodes
 *
 * References:
 * - https://spinalhdl.github.io/SpinalDoc-RTD/master/SpinalHDL/Libraries/Pipeline/introduction.html
 * - https://blog.hai-hs.in/posts/2023-02-23-notes-on-spinal-hdl-pipeline-api/
 *
 * Original location: Phase 0 prototype for learning Pipeline API
 */
case class SimplePipeline() extends Component {
  val io = new Bundle {
    val start = in Bool()
    val addrA = in UInt(8 bits)
    val addrB = in UInt(8 bits)
    val result = out UInt(32 bits)
    val valid = out Bool()
  }

  // Create a simple memory for demonstration
  val mem = Mem(UInt(32 bits), 256)
  // Initialize with some test data (address * 2)
  mem.init(List.tabulate(256)(i => i * 2))

  // ============================================
  // Pipeline Definition
  // ============================================

  // --------------------------------------------
  // Payload Definitions (data flowing through)
  // --------------------------------------------

  // Payloads are "keys" to access signals at different pipeline stages
  // Named in UPPERCASE to make it explicit they're not hardware signals
  val ADDR_A = Payload(UInt(8 bits))
  val ADDR_B = Payload(UInt(8 bits))
  val DATA_A = Payload(UInt(32 bits))
  val DATA_B = Payload(UInt(32 bits))
  val RESULT = Payload(UInt(32 bits))

  // --------------------------------------------
  // Node Definitions
  // --------------------------------------------

  // Nodes host the valid/ready arbitration and payload signals
  val fetchNode = Node()
  val executeNode = Node()

  // --------------------------------------------
  // Stage Connection with Automatic Register
  // --------------------------------------------

  // StageLink creates a pipeline stage between nodes
  // Automatically inserts registers for all payloads
  val fetchToExecute = StageLink(fetchNode, executeNode)

  // --------------------------------------------
  // Fetch Node Logic
  // --------------------------------------------

  // Access payloads using node(PAYLOAD) syntax
  fetchNode(ADDR_A) := io.addrA
  fetchNode(ADDR_B) := io.addrB

  // Read from memory synchronously
  // Note: readSync adds 1 cycle latency
  fetchNode(DATA_A) := mem.readSync(fetchNode(ADDR_A), enable = io.start)
  fetchNode(DATA_B) := mem.readSync(fetchNode(ADDR_B), enable = io.start)

  // --------------------------------------------
  // Execute Node Logic
  // --------------------------------------------

  // At executeNode, DATA_A and DATA_B have automatically
  // propagated through the StageLink register
  executeNode(RESULT) := executeNode(DATA_A) + executeNode(DATA_B)

  // Connect to output
  io.result := executeNode(RESULT)
  io.valid := executeNode.valid

  // ============================================
  // Valid/Ready Handshaking
  // ============================================

  // Set fetchNode valid when start is asserted
  fetchNode.valid := io.start

  // For this simple example, executeNode is always ready
  // In a real design, you'd connect this to downstream logic
  executeNode.ready := True

  // Build the pipeline (required step)
  Builder(fetchToExecute)
}

/**
 * Generate Verilog for inspection
 */
object SimplePipelineVerilog extends App {
  SpinalConfig(
    targetDirectory = "core/spinalhdl/generated/verilog",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  ).generateVerilog(SimplePipeline())

  println("✓ SimplePipeline Verilog generated at: core/spinalhdl/generated/verilog/SimplePipeline.v")
}

/**
 * Generate VHDL for inspection
 */
object SimplePipelineVhdl extends App {
  SpinalConfig(
    targetDirectory = "core/spinalhdl/generated/vhdl",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  ).generateVhdl(SimplePipeline())

  println("✓ SimplePipeline VHDL generated at: core/spinalhdl/generated/vhdl/SimplePipeline.vhd")
}
