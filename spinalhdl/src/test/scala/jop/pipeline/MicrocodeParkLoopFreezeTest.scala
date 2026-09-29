package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.JopPipeline
import jop.config.{BootMode, JopCoreConfig, MemoryStyle, MicrocodePaths}
import jop.memory.JopMemoryConfig
import jop.utils.{JopFileLoader, JopSimDefaults}

/**
 * CAN A STOP-THE-WORLD FREEZE KNOCK A PARKED CORE OUT OF ITS PARK LOOP? —
 * status item 133, the startup fault.
 *
 * THE FAULT, as measured on hardware. On the Wukong SMP + stack-cache build, a
 * minor GC taken while core 1 is still parked in the microcode `cpux_loop` stops
 * it ever reaching `main()` — 6 of 7 runs in the gc-then-release arm, 0 of 11 in
 * release-then-gc. Reading core 1's live microcode pc through the cluster probe
 * banks showed where it goes: `rdy_poll`, inside `cpu0_load`, the SERIAL
 * DOWNLOADER. It is executing, not frozen; it never faulted; it lost no bus
 * response; and it got there BEFORE core 0 wrote the release signal.
 *
 * THE MICROCODE, `asm/src/jvm.asm:340-356`, ROM 0x0e..0x22 in the serial build:
 *
 *   cpux_loop:  read io_signal;  bz cpux_loop    // spin while zero
 *               read io_signal;  bz cpu0_load    // read AGAIN — if zero, BECOME CORE 0
 *               jmp cpux_boot
 *
 * so a core that leaves the spin while the signal is STILL ZERO walks into the
 * downloader and never comes out. `cpu0_load` is reachable no other way: the
 * only other branch to it tests `io_cpu_id`, which is `B(cpuId, 32 bits)`, a
 * literal in the read mux (`Sys.scala:459`), so core 1 cannot take it.
 *
 * WHY A FREEZE IS THE SUSPECT, and why it needs no corrupted data:
 *
 *   - The halt reaches the core as `bsy` (`JopPipeline.scala:203`; `memBusy`
 *     carries `sys.io.halted`, `JopCore.scala:340`), and `bsy` freezes the fetch
 *     stage ONLY at a `wait` — the condition is `pcwait && io.bsy`
 *     (`FetchStage.scala:229`). A halted core keeps executing until it reaches
 *     one.
 *   - `extStall := stackRotBusy` (`JopPipeline.scala:204`) freezes on ANY
 *     instruction. Its own comment says so, and adds that "the frozen
 *     instruction replays and the pipeline resumes normally" — an assertion
 *     with no test behind it until this one.
 *   - `gcFlushReq := gcHaltActive && halted`, so the flush — and therefore
 *     `extStall` — begins the SAME cycle the halt lands, not after the core has
 *     parked on a `wait`.
 *
 * The loop's `wait`s are at 0x10/0x11, so a core halted anywhere in 0x12..0x16
 * is still walking toward them while `extStall` comes up, and five of the loop's
 * nine slots are `ldmrd`/`nop`/`bz`/its two delay slots. Lose that taken
 * backward branch and the core falls through 0x15, 0x16 to 0x17, re-reads
 * `io_signal`, gets the TRUE zero, and `bz cpu0_load` at 0x1d is CORRECTLY
 * taken. Same destination, no bad data anywhere — which is what the hardware
 * says, having verified 0 bad reads in ~2.7M checks across the same halts.
 *
 * WHY THIS TEST AND NOT THE LAST ONE. `StackCacheFlushTest` drives the same
 * flush and passes every case, because its DUT is StackStage + DMA + RAM: no
 * fetch stage and no branches at all. It can only ever show that the flush
 * terminates and moves the right words. The defect is what the flush does to a
 * PIPELINE EXECUTING A BRANCH, so the DUT has to contain one.
 *
 * WHAT THIS DRIVES. The real `JopPipeline`, with the REAL serial microcode ROM,
 * from reset: `io_cpu_id` answers 1 so it takes the cores-above-zero path, and
 * `io_signal` answers 0 forever, so a correct machine spins in `cpux_loop` for
 * as long as the test runs. Then `gcFlushReq` and the halt are raised at every
 * cycle offset around the loop, with the DMA faked so the freeze can be held for
 * a chosen length.
 *
 * THE PROPERTY, and it names no addresses. The set of PCs the loop visits is
 * LEARNED from a few clean iterations first; afterwards the pc must never leave
 * that set. An escape to `cpu0_load` fails, and so does an escape anywhere else,
 * without this test having to be updated when the microcode moves. A failure
 * reports the address it escaped to, which is what makes it diagnosable.
 */
class MicrocodeParkLoopFreezeTest extends AnyFunSuite {

  /** The real pipeline, real microcode, with the stack cache on. */
  case class ParkTb() extends Component {
    val coreCfg = JopCoreConfig(
      memConfig = JopMemoryConfig(mainMemSize = 256 * 1024),
      useStackCache = true,
      // A spill area at 0 keeps this independent of the per-core region
      // arithmetic, which is item 133's other half and not what is under test.
      spillBaseAddrOverride = Some(0),
      memoryStyle = Some(MemoryStyle.Generic))

    // THE REAL MICROCODE, the serial variant, because that is what the failing
    // hardware runs and what puts cpux_loop at 0x0e and cpu0_load at 0x23. A
    // hand-written ROM would test a park loop of the test's own design; the
    // point is the one that ships.
    val rom = JopFileLoader.loadMicrocodeRom(MicrocodePaths.rom(BootMode.Serial))
    val ram = JopFileLoader.loadStackRam(MicrocodePaths.ram(BootMode.Serial))

    val pipeline = JopPipeline(coreCfg, Some(rom), Some(ram))

    val io = new Bundle {
      val memRdData   = in Bits(32 bits)
      val memBusy     = in Bool()
      val gcFlushReq  = in Bool()
      val dmaBusy     = in Bool()
      val dmaDone     = in Bool()
      val pc          = out UInt(coreCfg.pcWidth bits)
      val aout        = out Bits(32 bits)
      val memRd       = out Bool()
      val rotBusy     = out Bool()
      val bankDirty   = out Bits(3 bits)
      val dmaStart    = out Bool()
    }

    pipeline.io.memRdData  := io.memRdData
    pipeline.io.memBusy    := io.memBusy
    pipeline.io.gcFlushReq := io.gcFlushReq
    pipeline.io.dmaBusy.get := io.dmaBusy
    pipeline.io.dmaDone.get := io.dmaDone

    io.pc        := pipeline.io.pc
    io.aout      := pipeline.io.aout
    io.memRd     := pipeline.io.memCtrl.rd
    io.rotBusy   := pipeline.io.scDebugRotState.get =/= 0
    io.bankDirty := pipeline.io.scDebugBankDirty.get
    io.dmaStart  := pipeline.io.dmaStart.get
    // THE BRANCH-DECODE SIGNALS, observed with simPublic rather than wired out.
    // Driving a top-level port from `pipeline.decode.io.br` is a HIERARCHY
    // VIOLATION -- a grandchild's output is not readable two levels up, the same
    // trap JopCore.scala:118-123 records for cores(i).sys.io.halted. simPublic
    // keeps them in the Verilator model without inventing RTL ports for a
    // measurement.
    pipeline.decode.io.br.simPublic()
    pipeline.decode.io.jmp.simPublic()
    pipeline.fetch.io.frozen.simPublic()
    // THE BRANCH TARGET ITSELF. `brdly := pc + ir(5..0)` is recomputed every
    // cycle, so what it holds during a freeze depends on the HELD pc and ir --
    // which is the whole defect.
    pipeline.fetch.brdly.simPublic()
    pipeline.fetch.jpdly.simPublic()
    pipeline.decode.io.jmp.simPublic()

    // Everything else the pipeline needs tied off. The bank write ports belong
    // to the DMA, which is faked in the testbench, so a spill reads the banks
    // (dmaBankRdData, an output) and writes nothing back.
    pipeline.io.memBcStart := 0
    pipeline.io.jbcWrAddr  := 0
    pipeline.io.jbcWrData  := 0
    pipeline.io.jbcWrEn    := False
    pipeline.io.irq        := False
    pipeline.io.irqEna     := False
    pipeline.io.exc        := False
    pipeline.io.debugRamAddr := 0
    pipeline.io.dmaBankRdAddr.get := 0
    pipeline.io.dmaBankWrAddr.get := 0
    pipeline.io.dmaBankWrData.get := 0
    pipeline.io.dmaBankWrEn.get   := False
    pipeline.io.dmaBankSelect.get := 0
  }

  /** `io_cpu_id` and `io_signal`, as the microcode spells them (jvm.asm). */
  private val IO_CPU_ID = -10
  private val IO_SIGNAL = -9

  /**
   * Drive the machine until it is demonstrably spinning, then return the set of
   * PCs the loop visits.
   *
   * SERVING THE READS. Every memory and I/O read in a pipeline-only bench comes
   * back through `memRdData`, and the address is in A when `memCtrl.rd` fires.
   * `io_cpu_id` answers 1 — a core above zero — and everything else answers 0,
   * which is what keeps `io_signal` false and the loop spinning.
   */
  private def runTo(dut: ParkTb, cycles: Int, observe: Boolean): Set[Int] = {
    val seen = scala.collection.mutable.Set[Int]()
    var served = 0
    for (_ <- 0 until cycles) {
      if (dut.io.memRd.toBoolean) {
        val addr = dut.io.aout.toLong.toInt
        dut.io.memRdData #= (if (addr == IO_CPU_ID) BigInt(1) else BigInt(0))
        served += 1
      }
      if (observe) seen += dut.io.pc.toInt
      dut.clockDomain.waitRisingEdge()
    }
    seen.toSet
  }

  /**
   * The DMA, faked, so the freeze can be held for exactly `busyCycles`.
   *
   * `StackCacheDma` emits `done` as a ONE-CYCLE PULSE and `FLUSH_WAIT` is what
   * samples it, so the model has to pulse rather than hold — holding it would
   * paper over precisely the handshake bug the other testbench looks for.
   */
  private def stepDma(dut: ParkTb, st: Array[Int], busyCycles: Int): Unit = {
    // st(0) = countdown, 0 = idle
    if (st(0) > 0) {
      st(0) -= 1
      dut.io.dmaBusy #= st(0) > 0
      dut.io.dmaDone #= st(0) == 0
    } else {
      dut.io.dmaDone #= false
      if (dut.io.dmaStart.toBoolean) {
        st(0) = busyCycles
        dut.io.dmaBusy #= true
      } else {
        dut.io.dmaBusy #= false
      }
    }
  }

  /**
   * COMPILED ONCE FOR THE WHOLE SUITE, and that is not a detail.
   *
   * A `compile()` per trial builds a separate Verilator model of the whole
   * pipeline: 145 MB and ~40 s each. The first version of this file did exactly
   * that and the sweeps below are 124 trials, so it wrote 8.9 GB into
   * `build/simWorkspace` and filled the disk before finishing — the failure
   * arrived as `No space left on device` inside g++, which reads as a toolchain
   * problem rather than as a testbench that asked for 124 builds of one DUT.
   *
   * `SimCompiled` takes any number of `doSim(name)` runs, each with its own
   * reset, so one build serves every phase and length.
   */
  private lazy val compiled = JopSimDefaults.config.compile(ParkTb())

  /**
   * One trial: spin, raise the halt and the flush at `phase` cycles into the
   * spin, hold the freeze for `dmaBusyCycles` per bank, release, and require the
   * pc never to have left the loop.
   *
   * Returns None on success, or Some(escapeAddress).
   */
  private def trial(phase: Int, dmaBusyCycles: Int,
                    withFlush: Boolean = true, withHalt: Boolean = true): Option[Int] = {
    var escaped: Option[Int] = None
    compiled.doSim(s"park_p${phase}_d${dmaBusyCycles}_f${withFlush}_h$withHalt") { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.memRdData #= 0
      dut.io.memBusy   #= false
      dut.io.gcFlushReq #= false
      dut.io.dmaBusy   #= false
      dut.io.dmaDone   #= false
      dut.clockDomain.waitRisingEdge(5)

      // Reach the loop, then learn it. 400 cycles is far more than the boot
      // prologue needs (0x00..0x0d) and several loop iterations beyond it.
      runTo(dut, 400, observe = false)
      val loopPcs = runTo(dut, 200, observe = true)

      // A TEST THAT CANNOT FAIL IS WORSE THAN NO TEST. Two ways this one could
      // be vacuous, both checked rather than assumed: the machine might not be
      // in a tight loop at all, and the flush only runs when a bank is DIRTY
      // (`gcFlushReq && anyDirty`, StackStage.scala:872) — a clean cache reports
      // done immediately with rotBusy never asserted, so nothing would freeze.
      assert(loopPcs.size <= 16,
        s"not in a tight loop: ${loopPcs.size} distinct PCs, " +
        loopPcs.toSeq.sorted.map(p => f"0x$p%03x").mkString(" "))
      assert(dut.io.bankDirty.toInt != 0,
        "no bank is dirty, so no flush would run and this test would be vacuous")

      // Line the freeze up with the loop.
      runTo(dut, phase, observe = false)

      // THE HALT AND THE FLUSH TOGETHER, which is how the machine does it:
      // gcFlushReq is gcHaltActive && halted, so extStall comes up while the
      // core is still walking toward its `wait`.
      dut.io.memBusy    #= withHalt
      dut.io.gcFlushReq #= withFlush

      val st = Array(0)
      var guard = 0
      // Hold until the flush has finished every dirty bank, bounded so a stuck
      // FSM fails as a timeout rather than hanging the suite. With no flush
      // requested there is nothing to wait for, so the control arms hold the
      // stimulus for a fixed spell instead -- long enough to cover the same
      // number of cycles the flush would have taken.
      if (withFlush) {
        while (dut.io.bankDirty.toInt != 0 && guard < 4000) {
          stepDma(dut, st, dmaBusyCycles)
          if (dut.io.memRd.toBoolean) dut.io.memRdData #= 0
          dut.clockDomain.waitRisingEdge()
          guard += 1
        }
        assert(guard < 4000, "flush never finished — the FSM is stuck")
      } else {
        for (_ <- 0 until (dmaBusyCycles + 4)) {
          if (dut.io.memRd.toBoolean) dut.io.memRdData #= 0
          dut.clockDomain.waitRisingEdge()
        }
      }

      dut.io.memBusy    #= false
      dut.io.gcFlushReq #= false

      // Let it run well past the freeze and watch where it goes. io_signal is
      // still zero, so a correct machine is still in the loop.
      for (_ <- 0 until 600) {
        stepDma(dut, st, dmaBusyCycles)
        if (dut.io.memRd.toBoolean) {
          val addr = dut.io.aout.toLong.toInt
          dut.io.memRdData #= (if (addr == IO_CPU_ID) BigInt(1) else BigInt(0))
        }
        val pc = dut.io.pc.toInt
        if (!loopPcs.contains(pc) && escaped.isEmpty) escaped = Some(pc)
        dut.clockDomain.waitRisingEdge()
      }
    }
    escaped
  }

  /** `cpux_boot` and `cpu0_load` in the serial ROM — the jump's two outcomes. */
  private val CPUX_BOOT = 0x11f
  private val CPU0_LOAD = 0x23

  /**
   * THE JUMP HALF OF THE FIX, on the real microcode.
   *
   * The fix holds TWO registers and only `brdly` was red-proved: the park loop's
   * backward edge is a `bz`, so every other test here exercises the branch half
   * and none touches the jump half. `jpdly := jpdly` went in BY ANALOGY, which is
   * how a fix acquires an untested half — and "identical by symmetry" is the
   * reasoning that produced two wrong conclusions in this item already.
   *
   * A SYNTHETIC ROM CANNOT TEST IT. A nop/jmp loop makes no stack writes, so
   * `anyDirty` is never true, `gcFlushReq && anyDirty` never fires, `rotBusy`
   * never rises and nothing freezes. That version of this test reported "the
   * fetch stage never froze, so nothing was tested" at all 20 phases, which is
   * the only honest thing it could say.
   *
   * THE REAL MICROCODE HAS A BETTER JUMP. The park loop's own exit is
   * `jmp cpux_boot` at 0x20, and its fall-through is 0x21, 0x22, then **0x23 =
   * cpu0_load** — the same serial downloader the branch bug ended in. So a
   * destroyed `jpdly` reaches the identical hardware symptom by a second path,
   * and the two outcomes are far apart and unambiguous.
   *
   * Serve `io_signal` as 1 and the machine leaves the loop through both `bz`s and
   * executes that jump. Everything here is deterministic, so the freeze can be
   * swept across the cycles around it; the assertion is on the FIRST of
   * {cpux_boot, cpu0_load} reached, because after `cpux_boot` the machine reads a
   * main memory this bench serves as zeroes and then wanders.
   */
  private def jumpTrial(phase: Int): Either[String, Int] = {
    var firstOutcome = -1
    var armed = false
    compiled.doSim(s"jump_p$phase") { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.memRdData #= 0
      dut.io.memBusy #= false
      dut.io.gcFlushReq #= false
      dut.io.dmaBusy #= false
      dut.io.dmaDone #= false
      dut.clockDomain.waitRisingEdge(5)

      // Reach the park loop with the signal still zero, so a bank is dirtied and
      // the flush has something to do -- the arming the synthetic ROM lacked.
      runTo(dut, 400, observe = false)
      if (dut.io.bankDirty.toInt != 0) armed = true

      // NOW RELEASE IT, and serve the value UNCONDITIONALLY.
      //
      // The first version of this served memRdData only on the cycle `memCtrl.rd`
      // fired, which is what `runTo` does. Across a freeze that is wrong: the
      // `stmra` fires, then the core sits in `wait` for the whole flush, and the
      // `ldmrd` lands ~90 cycles later reading whatever the input happens to hold
      // by then. The second `io_signal` read came back ZERO, `bz cpu0_load` was
      // CORRECTLY taken, and the test reported it as the freeze destroying a jump
      // target. The core did exactly the right thing with the data it was given.
      //
      // Both values this program reads want to be 1 -- io_cpu_id (a core above
      // zero) and io_signal (released) -- so driving 1 flat removes the address
      // decode and the timing question together.
      dut.io.memRdData #= 1
      def serve(): Unit = ()
      for (_ <- 0 until phase) { serve(); dut.clockDomain.waitRisingEdge() }

      dut.io.memBusy #= true
      dut.io.gcFlushReq #= true
      val st = Array(0)
      var guard = 0
      while (dut.io.bankDirty.toInt != 0 && guard < 2000) {
        stepDma(dut, st, 6); serve(); dut.clockDomain.waitRisingEdge(); guard += 1
      }
      dut.io.memBusy #= false
      dut.io.gcFlushReq #= false

      for (_ <- 0 until 900 if firstOutcome < 0) {
        val pc = dut.io.pc.toInt
        if (pc == CPUX_BOOT || pc == CPU0_LOAD) firstOutcome = pc
        stepDma(dut, st, 6); serve(); dut.clockDomain.waitRisingEdge()
      }
    }
    if (!armed) Left("no bank was dirty, so no flush ran and nothing was tested")
    else if (firstOutcome < 0) Left("reached neither cpux_boot nor cpu0_load")
    else Right(firstOutcome)
  }

  /**
   * The jump trial, ARMED — the freeze must arrive while the jump is IN FLIGHT.
   *
   * `jumpTrial` above cannot do that, and the trace says why: the halt parks the
   * core on the `wait` at 0x1a and it only reaches 0x20 AFTER the flush has
   * finished, so the freeze and the jump never overlap. Removing
   * `jpdly := jpdly` left that test green, which is a test with no teeth.
   *
   * So this one asserts `gcFlushReq` WITHOUT the halt. That decouples two signals
   * the real machine couples (`gcFlushReq := gcHaltActive && halted`), and it is
   * deliberate: the property under test belongs to the FETCH STAGE — "a freeze
   * must not destroy a pending jump target" — and it is testable independently of
   * how the freeze came to be asserted. The real machine can still reach it: the
   * exposed window is between `halted` rising and the core reaching its next
   * `wait`, and a `jmp` anywhere in that window is as vulnerable as the `bz` at
   * 0x14 was.
   *
   * ARMING IS CHECKED, not assumed: the trial records whether the freeze was up
   * while the pc sat on the jump, and reports failure to arm rather than passing.
   */
  private def armedJumpTrial(phase: Int): Either[String, Int] = {
    var firstOutcome = -1
    var frozenOnJump = false
    compiled.doSim(s"armedjump_p$phase") { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.memRdData #= 0
      dut.io.memBusy #= false; dut.io.gcFlushReq #= false
      dut.io.dmaBusy #= false; dut.io.dmaDone #= false
      dut.clockDomain.waitRisingEdge(5)
      runTo(dut, 400, observe = false)
      dut.io.memRdData #= 1          // io_cpu_id and io_signal both want 1

      val st = Array(0)
      for (_ <- 0 until phase) { stepDma(dut, st, 6); dut.clockDomain.waitRisingEdge() }
      dut.io.gcFlushReq #= true      // freeze WITHOUT the halt

      for (_ <- 0 until 140) {
        if (dut.pipeline.fetch.io.frozen.toBoolean && dut.io.pc.toInt == 0x20) frozenOnJump = true
        stepDma(dut, st, 6); dut.clockDomain.waitRisingEdge()
      }
      dut.io.gcFlushReq #= false
      for (_ <- 0 until 400 if firstOutcome < 0) {
        val pc = dut.io.pc.toInt
        if (pc == CPUX_BOOT || pc == CPU0_LOAD) firstOutcome = pc
        stepDma(dut, st, 6); dut.clockDomain.waitRisingEdge()
      }
    }
    if (!frozenOnJump) Left("the freeze never overlapped the jump")
    else if (firstOutcome < 0) Left("reached neither cpux_boot nor cpu0_load")
    else Right(firstOutcome)
  }

  /**
   * IGNORED, AND THE REASON IS THE POINT — two vehicles could not arm this.
   *
   * `jpdly := jpdly` is held BY SYMMETRY with `brdly` and is NOT red-proved.
   * Removing it leaves both jump tests here green, which means neither has teeth,
   * and an ignored test that says so is worth more than a green one that does not.
   *
   * WHY IT WOULD NOT ARM. The flush is a LONG event (~86 cycles) and the park
   * loop's only `jmp` is passed ONCE, so landing the freeze's first cycle on that
   * instruction needs cycle-exact alignment. With the halt asserted the core parks
   * on the `wait` at 0x1a and reaches 0x20 only after the flush has finished;
   * without the halt it runs past 0x20 before `rotState` leaves IDLE. A 26-phase
   * sweep hit neither.
   *
   * THE RIGHT VEHICLE drives `extStall` DIRECTLY on a FetchStage-level DUT, where
   * the freeze can be raised for exactly one cycle on exactly the chosen
   * instruction, instead of through the flush FSM's latency. That is a different
   * testbench, not a tweak to this one.
   *
   * WHAT IS AND IS NOT AT RISK. The line cannot BREAK anything: without it the
   * register is overwritten with `pc + offset` of a frozen instruction, which is
   * never a meaningful target, so holding it is strictly no worse. The open
   * question is only whether it FIXES anything, i.e. whether a freeze can land on
   * a jump in the real machine. The exposed window is between `halted` rising and
   * the core reaching its next `wait`, and any `jmp` inside that window is as
   * vulnerable as the `bz` at 0x14 was.
   */
  ignore("the JUMP half, ARMED: a freeze ON the jump must not lose its target") {
    val results = (0 until 26).map(p => p -> armedJumpTrial(p))
    val armed = results.collect { case (p, Right(pc)) => p -> pc }
    val wrong = armed.collect { case (p, pc) if pc == CPU0_LOAD =>
      f"  phase $p%2d -> fell through to cpu0_load" }
    assert(armed.nonEmpty,
      "no phase put the freeze on the jump, so this sweep tested nothing:\n" +
        results.collect { case (p, Left(m)) => s"  phase $p: $m" }.take(4).mkString("\n"))
    assert(wrong.isEmpty,
      s"${armed.size} phase(s) armed; the freeze destroyed the pending JUMP target:\n" +
        wrong.mkString("\n"))
  }

  /** Kept as a regression on the jump PATH, not on `jpdly` — see the note above:
    * it is green with and without the hold, so it proves the path is walked, not
    * that the target survives a freeze. */
  test("the jump path: `jmp cpux_boot` reaches cpux_boot, not cpu0_load") {
    val results = (0 until 30).map(p => p -> jumpTrial(p))
    val unarmed = results.collect { case (p, Left(m)) => s"  phase $p: $m" }
    val wrong = results.collect {
      case (p, Right(pc)) if pc == CPU0_LOAD => f"  phase $p%2d -> fell through to cpu0_load"
    }
    // An unarmed phase is not a pass. Say so, the way the synthetic-ROM version
    // did, rather than counting silence as success.
    assert(unarmed.size < results.size,
      "no phase armed, so this sweep tested nothing:\n" + unarmed.take(5).mkString("\n"))
    assert(wrong.isEmpty,
      "the freeze destroyed the pending JUMP target (jpdly):\n" + wrong.mkString("\n"))
  }

  test("a parked core stays parked across a stop-the-world flush, at every phase") {
    // The loop is nine instructions and the freeze can land on any of them, so
    // the sweep has to cover more than one lap — 40 cycles is four.
    val failures = (0 until 40).flatMap { phase =>
      trial(phase, dmaBusyCycles = 6).map(pc => (phase, pc))
    }
    assert(failures.isEmpty,
      "the pc left the park loop after a flush freeze:\n" +
        failures.map { case (p, pc) => f"  phase $p%2d -> escaped to 0x$pc%03x" }.mkString("\n"))
  }

  test("the freeze DESTROYS the pending branch target: brdly must survive it") {
    // WHAT EXACTLY IS DROPPED. `brReg` is a register gated by `when(!io.stall)`
    // (DecodeStage.scala:410), `io.br := brReg`, and `decode.io.stall` and
    // `fetch.io.extStall` are THE SAME SIGNAL (`stackRotBusy`,
    // JopPipeline.scala:204-205). So they release together, and on the release
    // cycle `brReg` is recomputed from the HELD `ir` -- which by then is the
    // delay-slot instruction, not the `bz`. The freeze suppressed
    // `pcMux := brdly` while it was asserted (`pcMux := pc` wins, last
    // assignment) and the un-freeze recomputes the condition that would have
    // reapplied it. The branch is dropped between the two.
    //
    // This test asserts the SHAPE of that, not just the outcome: `br` must be
    // high at some point during the freeze and low on the cycle after it lifts,
    // with the pc having advanced sequentially rather than to the target.
    val trace = scala.collection.mutable.ArrayBuffer[String]()
    var sawBrDuringFreeze = false
    var brdlyBefore: Option[Int] = None
    var brdlyAfter: Option[Int] = None
    compiled.doSim("park_trace_p8") { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.memRdData #= 0
      dut.io.memBusy #= false
      dut.io.gcFlushReq #= false
      dut.io.dmaBusy #= false
      dut.io.dmaDone #= false
      dut.clockDomain.waitRisingEdge(5)
      runTo(dut, 400, observe = false)
      runTo(dut, 8, observe = false)

      dut.io.memBusy #= true
      dut.io.gcFlushReq #= true
      val st = Array(0)
      var guard = 0
      var released = -1
      while (guard < 120) {
        stepDma(dut, st, 6)
        if (dut.io.memRd.toBoolean) dut.io.memRdData #= 0
        val frozen = dut.pipeline.fetch.io.frozen.toBoolean
        if (frozen && dut.pipeline.decode.io.br.toBoolean) {
          // First cycle of the freeze with a branch in flight: this is the
          // target that must survive.
          if (!sawBrDuringFreeze) brdlyBefore = Some(dut.pipeline.fetch.brdly.toInt)
          sawBrDuringFreeze = true
        }
        if (released < 0 && guard > 0 && !frozen && dut.io.bankDirty.toInt == 0) {
          released = guard
          if (brdlyAfter.isEmpty) brdlyAfter = Some(dut.pipeline.fetch.brdly.toInt)
          dut.io.memBusy #= false
          dut.io.gcFlushReq #= false
        }
        trace += f"  ${guard}%3d pc 0x${dut.io.pc.toInt}%03x ir 0x${dut.pipeline.fetch.ir.toLong}%03x " +
                 f"frozen ${if (frozen) 1 else 0} br ${if (dut.pipeline.decode.io.br.toBoolean) 1 else 0} " +
                 f"jmp ${if (dut.pipeline.decode.io.jmp.toBoolean) 1 else 0} dirty ${dut.io.bankDirty.toInt}"
        dut.clockDomain.waitRisingEdge()
        guard += 1
      }
    }
    assert(sawBrDuringFreeze,
      "no taken branch was in flight during the freeze, so this trace does not\n" +
      "show the mechanism (the phase may have drifted):\n" + trace.take(40).mkString("\n"))
    // THE PROPERTY. A branch target latched before a freeze must still be that
    // target when the freeze lifts. `brdly` is recomputed from the held `pc` and
    // held `ir` every cycle, and a held `ir` is whatever instruction was in
    // flight -- for the park loop a `nop`, offset 0 -- so an unheld `brdly`
    // becomes `pc + 0`, i.e. "here". The branch then applies to where the core
    // already is and the next cycle falls through.
    assert(brdlyBefore.isDefined && brdlyAfter.isDefined,
      "did not capture brdly either side of the freeze:\n" + trace.mkString("\n"))
    assert(brdlyBefore == brdlyAfter,
      f"the freeze DESTROYED the pending branch target: brdly was " +
      f"0x${brdlyBefore.get}%03x before and 0x${brdlyAfter.get}%03x after.\n" +
      trace.mkString("\n"))
  }

  test("CONTROL: with neither stimulus the loop never escapes on its own") {
    // IS THE TESTBENCH MANUFACTURING THE ESCAPE? A sweep that fails everywhere
    // proves nothing about the DUT, and the previous flush testbench's real
    // lesson was that a bench can be confidently wrong about what it covers.
    // With no halt and no flush, the park loop must be a closed cycle.
    val failures = (0 until 40).flatMap { phase =>
      trial(phase, 6, withFlush = false, withHalt = false).map(pc => (phase, pc))
    }
    assert(failures.isEmpty,
      "the loop escaped with NO stimulus, so the sweep above proves nothing:\n" +
        failures.map { case (p, pc) => f"  phase $p%2d -> 0x$pc%03x" }.mkString("\n"))
  }

  test("CONTROL: the HALT alone does not do it — so it is the flush's extStall") {
    // WHICH FREEZE IS IT? Two different mechanisms reach the fetch stage:
    //
    //   the halt   -> memBusy -> bsy, and `pcwait && io.bsy` freezes ONLY at a
    //                 `wait` instruction, so it can never catch a branch.
    //   the flush  -> rotBusy -> extStall, which freezes on ANY instruction.
    //
    // If the halt alone loses the branch then the defect is in the wait-stall
    // path, it has nothing to do with the stack cache, and EVERY board has it.
    // If only the flush arm fails, `extStall` is the mechanism and the fault is
    // confined to stack-cache builds -- which is what the hardware says, and
    // this is the arm that says it without the board confound the EP4CGX150
    // control carried (different board, toolchain, clock and memory controller).
    val failures = (0 until 40).flatMap { phase =>
      trial(phase, 6, withFlush = false, withHalt = true).map(pc => (phase, pc))
    }
    assert(failures.isEmpty,
      "the HALT alone knocked the core out of its park loop, so this is NOT\n" +
      "stack-cache specific and every board is affected:\n" +
        failures.map { case (p, pc) => f"  phase $p%2d -> 0x$pc%03x" }.mkString("\n"))
  }

  test("and at every freeze LENGTH, since the un-freeze cycle is the suspect") {
    // The length matters independently of the phase: what the freeze comment
    // claims is that the frozen instruction REPLAYS on the un-stall cycle, so
    // the bug — if it is there — is in how the freeze ENDS, and its end lands on
    // a different instruction for each length.
    val failures = for {
      len <- Seq(1, 2, 3, 4, 8, 16, 32)
      phase <- 0 until 12
      pc <- trial(phase, len)
    } yield (phase, len, pc)
    assert(failures.isEmpty,
      "the pc left the park loop after a flush freeze:\n" +
        failures.map { case (p, l, pc) =>
          f"  phase $p%2d len $l%2d -> escaped to 0x$pc%03x"
        }.mkString("\n"))
  }
}
