package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.bus.bmb._
import jop.io.CmpSync

/**
 * JOP Cluster: N JopCores with shared bus arbitration and synchronization.
 *
 * Encapsulates the common SMP wiring that was duplicated across FPGA top-levels:
 *   - Core instantiation with per-core cpuId/cpuCnt/hasUart override
 *   - BMB parameter calculation (single-core direct vs. arbiter output)
 *   - Single-core path: direct BMB, sync tie-offs
 *   - SMP path: BmbArbiter + CmpSync
 *   - Common tie-offs: irq, irqEna, debugRamAddr
 *   - UART routing: core 0 TXD/RXD to io; cores 1+ RXD tied to True
 *
 * All per-core signals are routed through io as Vecs to avoid hierarchy
 * violations when top-levels or test harnesses read them. Unused outputs
 * are pruned by SpinalHDL during Verilog generation.
 *
 * For simulation, cores and cmpSync are public vals for simPublic() access.
 *
 * @param cpuCnt     Number of CPU cores (1 = single-core, 2+ = SMP)
 * @param baseConfig Base JopCoreConfig (cpuId/cpuCnt/hasUart overridden per core)
 * @param romInit    Optional microcode ROM initialization data
 * @param ramInit    Optional stack RAM initialization data
 * @param jbcInit    Optional JBC RAM initialization data
 */
case class JopCluster(
  cpuCnt: Int,
  baseConfig: JopCoreConfig,
  romInit: Option[Seq[BigInt]] = None,
  ramInit: Option[Seq[BigInt]] = None,
  jbcInit: Option[Seq[BigInt]] = None
) extends Component {
  require(cpuCnt >= 1, "cpuCnt must be at least 1")

  // BMB parameter: passthrough for single-core, arbiter output for SMP
  val inputParam = baseConfig.memConfig.bmbParameter

  val bmbParameter: BmbParameter = if (cpuCnt == 1) {
    inputParam
  } else {
    val sourceRouteWidth = log2Up(cpuCnt)
    val outputSourceCount = 1 << sourceRouteWidth
    val inputSourceParam = inputParam.access.sources.values.head
    BmbParameter(
      access = BmbAccessParameter(
        addressWidth = inputParam.access.addressWidth,
        dataWidth = inputParam.access.dataWidth
      ).addSources(outputSourceCount, BmbSourceParameter(
        contextWidth = inputSourceParam.contextWidth,
        lengthWidth = inputSourceParam.lengthWidth,
        canWrite = true,
        canRead = true,
        alignment = BmbParameter.BurstAlignement.WORD
      )),
      invalidation = BmbInvalidationParameter()
    )
  }

  val io = new Bundle {
    val bmb = master(Bmb(bmbParameter))
    val txd = out Bool()
    val rxd = in Bool()
    val wd  = out Vec(Bits(32 bits), cpuCnt)

    // Per-core debug outputs (routed through to avoid hierarchy violations)
    val pc      = out Vec(UInt(baseConfig.pcWidth bits), cpuCnt)
    val jpc     = out Vec(UInt((baseConfig.jpcWidth + 1) bits), cpuCnt)
    val aout    = out Vec(Bits(baseConfig.dataWidth bits), cpuCnt)
    val bout    = out Vec(Bits(baseConfig.dataWidth bits), cpuCnt)
    val memBusy = out Vec(Bool(), cpuCnt)
    val halted  = out Vec(Bool(), cpuCnt)

    // Core 0 debug signals
    val debugMemState = out UInt(5 bits)
    val uartTxData    = out Bits(8 bits)
    val uartTxValid   = out Bool()
    val debugExc      = out Bool()
  }

  // ==================================================================
  // Instantiate N JOP Cores
  // ==================================================================

  val cores = (0 until cpuCnt).map { i =>
    val coreConfig = baseConfig.copy(
      cpuId = i,
      cpuCnt = cpuCnt,
      hasUart = (i == 0)
    )
    JopCore(
      config = coreConfig,
      romInit = romInit,
      ramInit = ramInit,
      jbcInit = jbcInit
    )
  }

  // ==================================================================
  // Memory Bus: direct (single-core) or arbitrated (SMP)
  // ==================================================================

  val cmpSync: Option[CmpSync] = if (cpuCnt == 1) {
    // Single-core: direct BMB connection, no CmpSync needed
    io.bmb <> cores(0).io.bmb
    cores(0).io.syncIn.halted := False
    cores(0).io.syncIn.s_out  := False
    None
  } else {
    // SMP: arbiter + CmpSync
    val arbiter = BmbArbiter(
      inputsParameter = Seq.fill(cpuCnt)(inputParam),
      outputParameter = bmbParameter,
      lowerFirstPriority = false  // Round-robin
    )
    for (i <- 0 until cpuCnt) {
      arbiter.io.inputs(i) << cores(i).io.bmb
    }
    io.bmb <> arbiter.io.output

    val sync = CmpSync(cpuCnt)
    for (i <- 0 until cpuCnt) {
      sync.io.syncIn(i) := cores(i).io.syncOut
      cores(i).io.syncIn := sync.io.syncOut(i)
    }
    Some(sync)
  }

  // ==================================================================
  // Common Tie-offs
  // ==================================================================

  for (i <- 0 until cpuCnt) {
    cores(i).io.debugRamAddr := 0
  }

  // ==================================================================
  // UART (core 0 only)
  // ==================================================================

  io.txd := cores(0).io.txd
  cores(0).io.rxd := io.rxd
  for (i <- 1 until cpuCnt) {
    cores(i).io.rxd := True  // No UART on cores 1+
  }

  // ==================================================================
  // Per-core Output Routing
  // ==================================================================

  for (i <- 0 until cpuCnt) {
    io.wd(i)      := cores(i).io.wd
    io.pc(i)      := cores(i).io.pc
    io.jpc(i)     := cores(i).io.jpc
    io.aout(i)    := cores(i).io.aout
    io.bout(i)    := cores(i).io.bout
    io.memBusy(i) := cores(i).io.memBusy
    io.halted(i)  := cores(i).io.debugHalted
  }

  // ==================================================================
  // Core 0 Debug Outputs
  // ==================================================================

  io.debugMemState := cores(0).io.debugMemState
  io.uartTxData    := cores(0).io.uartTxData
  io.uartTxValid   := cores(0).io.uartTxValid
  io.debugExc      := cores(0).io.debugExc
}
