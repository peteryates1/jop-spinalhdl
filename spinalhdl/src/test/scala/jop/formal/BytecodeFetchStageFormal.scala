package jop.formal

import spinal.core._
import spinal.core.formal._

import jop.pipeline.{BytecodeFetchStage, BytecodeFetchConfig}

/**
 * Formal verification for the BytecodeFetchStage component.
 *
 * Source: jop/pipeline/BytecodeFetchStage.scala
 *
 * Note: This component contains a 256-entry JumpTable ROM and 2KB JBC RAM,
 * making deep BMC expensive. We use shallow depths and constrained inputs.
 *
 * Properties verified:
 * - No double-ack (ack_irq && ack_exc never both true)
 * - Exception ack requires jfetch
 * - Interrupt ack requires jfetch and ena
 * - IRQ latching regardless of ena
 */
class BytecodeFetchStageFormal extends SpinalFormalFunSuite {

  // 900, not 300. The stall property below takes 2m47s locally and CI's runner
  // is roughly twice as slow, so at 300 it failed as a TIMEOUT ("SymbiYosys
  // failure" after 5m02s, having reached BMC step 5) on some pushes and passed
  // on others — including on commits that touched no RTL at all. That is runner
  // variance against a wall, not a regression, and chasing it as one wasted a
  // CI cycle. The headroom is deliberate: a formal timeout should mean "this
  // property has become intractable", not "the runner was busy".
  val formalConfig = FormalConfig
    .addEngin(SmtBmc(solver = SmtBmcSolver.Z3))
    .withTimeout(900)

  /** Helper to drive all inputs with anyseq */
  def setupAllInputs(dut: BytecodeFetchStage): Unit = {
    anyseq(dut.io.jpc_wr)
    anyseq(dut.io.din)
    anyseq(dut.io.jfetch)
    anyseq(dut.io.jopdfetch)
    anyseq(dut.io.jbr)
    anyseq(dut.io.zf)
    anyseq(dut.io.nf)
    anyseq(dut.io.eq)
    anyseq(dut.io.lt)
    anyseq(dut.io.jbcWrAddr)
    anyseq(dut.io.jbcWrData)
    anyseq(dut.io.jbcWrEn)
    anyseq(dut.io.stall)
    anyseq(dut.io.irq)
    anyseq(dut.io.exc)
    anyseq(dut.io.ena)
  }

  /**
   * THE FREEZE INVARIANT. While `stall` is asserted nothing about the bytecode
   * stream may move: not the Java PC, not the latched bytecode, and not the
   * dispatch address derived from the RAM read.
   *
   * This is the property whose absence cost three cascading bugs. `jfetch` and
   * `jopdfetch` come from the microcode IR, and IR is HELD during a memory-wait
   * freeze, so they stay asserted for the whole stall — anything keyed off them
   * as a per-cycle event fires repeatedly. Two separate fixes each froze SOME of
   * the three and desynchronised the rest:
   *
   *   nothing frozen  -> a bytecode is CONSUMED EVERY CYCLE of the stall
   *   jpc+jinstr only -> jbcAddr prefetches on, jpaddr slides to the NEXT byte's
   *                      handler, and on release the pending instruction is
   *                      skipped while its operand executes as an opcode
   *
   * Neither showed up in JopJvmTestsBramSim, JopJvmTestsMcFallbackSim or
   * JopDcuCacheSim — all three passed with the pipeline skipping a dispatch.
   * A whole-system suite cannot see this: it needs a memory stall to land on an
   * instruction with jfetch set AND something downstream to notice. `stall` is
   * driven by anyseq here, so this covers every jfetch/jopdfetch/jmp
   * combination, which is precisely the space the bug lived in.
   *
   * Note every EXISTING unit test in BytecodeFetchStageTest sets stall = false,
   * so before this the stall path had no behavioural coverage at all.
   */
  test("stall freezes jpc, jinstr and the dispatch address") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupAllInputs(dut)

        // A bytecode-cache FILL legitimately rewrites the RAM under the read,
        // which moves jbcData and hence jpaddr. That is a method being replaced,
        // not the stream advancing, so exclude it rather than weaken the
        // property: with a fill in flight the core is being redirected anyway.
        assume(!dut.io.jbcWrEn)
        // Pinning jbcWrAddr/jbcWrData as well was tried, on the reasoning that
        // with writes disabled they cannot reach anything and so are 19 free
        // bits the BMC need not carry. It made the property SLOWER, 2m47s ->
        // 3m42s: the extra assumes change the solver's search and, here, for the
        // worse. Not re-adding it — the timeout is the real fix.
        // An exception or interrupt arriving during the stall legitimately
        // REDIRECTS the dispatch: JumpTable muxes jpaddr to sysExcAddr/sysIntAddr
        // ahead of the decoded bytecode. That is the trap being taken, not the
        // stream advancing, so exclude it too. Both are latched (excPend/intPend
        // are registers), hence excluding the inputs from reset suffices.
        assume(!dut.io.exc)
        assume(!dut.io.irq)

        // jpaddr needs ONE cycle to settle, and the formal engine found it.
        // Entering a stall from a jfetch cycle, jbcAddr moves from jpc+1 back to
        // jpc; the JBC RAM is synchronous, so jpaddr reflects that a cycle later.
        // That settling is CORRECT — on the release cycle jpaddr is the pending
        // byte's handler either way — but it is a change, so the invariant is
        // "held for two cycles => stable", not "held => stable".
        val stallD  = RegNext(dut.io.stall) init (False)
        val stallD2 = RegNext(stallD) init (False)

        when(pastValidAfterReset() && past(dut.io.stall)) {
          assert(stable(dut.io.jpc_out))
          assert(stable(dut.io.jinstr_out))
        }
        when(pastValidAfterReset() && stallD && stallD2) {
          // FetchStage does `pcMux := io.jpaddr` when jfetch, so a jpaddr that
          // slides during the stall sends the microcode to the WRONG handler on
          // release — the pending instruction is skipped and its operand byte
          // executes as an opcode. That was the 4ba87fc failure exactly.
          assert(stable(dut.io.jpaddr))
        }
      })
  }

  test("no double acknowledge") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupAllInputs(dut)

        when(pastValidAfterReset()) {
          assert(!(dut.io.ack_irq && dut.io.ack_exc))
        }
      })
  }

  test("exception acknowledge requires jfetch") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupAllInputs(dut)

        when(pastValidAfterReset()) {
          when(dut.io.ack_exc) {
            assert(dut.io.jfetch)
          }
        }
      })
  }

  test("interrupt acknowledge requires jfetch and ena") {
    formalConfig
      .withBMC(4)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)
        setupAllInputs(dut)

        when(pastValidAfterReset()) {
          when(dut.io.ack_irq) {
            assert(dut.io.jfetch)
            assert(dut.io.ena)
          }
        }
      })
  }

  test("interrupt latched even when disabled") {
    formalConfig
      .withBMC(6)
      .doVerify(new Component {
        val dut = FormalDut(BytecodeFetchStage())
        assumeInitial(ClockDomain.current.isResetActive)

        // Constrain unused inputs to reduce state space
        dut.io.jpc_wr := False
        dut.io.din := B(0, 32 bits)
        dut.io.jopdfetch := False
        dut.io.jbr := False
        dut.io.zf := False
        dut.io.nf := False
        dut.io.eq := False
        dut.io.lt := False
        dut.io.jbcWrAddr := U(0)
        dut.io.jbcWrData := B(0)
        dut.io.jbcWrEn := False
        dut.io.stall := False

        // Scenario: IRQ arrives while ena=0, then ena goes high with jfetch
        dut.io.ena := False
        dut.io.exc := False
        dut.io.jfetch := False
        dut.io.irq := False

        val cycle = Reg(UInt(4 bits)) init (0)
        when(cycle < 7) { cycle := cycle + 1 }

        // Cycle 1: send IRQ while disabled
        when(cycle === 1) { dut.io.irq := True }

        // Cycle 4: enable interrupts and jfetch
        when(cycle === 4) {
          dut.io.ena := True
          dut.io.jfetch := True
        }

        // By cycle 4, the latched interrupt should fire ack_irq
        when(cycle === 4) {
          assert(dut.io.ack_irq)
        }
      })
  }
}
