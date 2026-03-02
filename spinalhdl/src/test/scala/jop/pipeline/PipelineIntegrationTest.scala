package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.TestVectorUtils

/** Test harness wiring FetchStage → DecodeStage. */
case class FetchDecodeHarness() extends Component {
  val fetch = FetchStage()
  val decode = DecodeStage()

  val io = new Bundle {
    // Fetch control inputs
    val bsy    = in Bool()
    val jpaddr = in UInt(10 bits)
    val extStall = in Bool()

    // Decode condition inputs
    val zf    = in Bool()
    val nf    = in Bool()
    val eq    = in Bool()
    val lt    = in Bool()
    val bcopd = in Bits(16 bits)
    val stall = in Bool()

    // Observable outputs from fetch
    val pc_out = out UInt(10 bits)
    val ir_out = out Bits(10 bits)
    val nxt    = out Bool()
    val opd    = out Bool()
    val dout   = out Bits(10 bits)

    // Observable outputs from decode
    val br     = out Bool()
    val jmp    = out Bool()
    val jbr    = out Bool()
    val selSub = out Bool()
    val enaA   = out Bool()
    val wrEna  = out Bool()
    val selLmux = out Bits(3 bits)
  }

  // FetchStage → DecodeStage instruction path
  decode.io.instr := fetch.io.dout

  // DecodeStage → FetchStage feedback
  fetch.io.br  := decode.io.br
  fetch.io.jmp := decode.io.jmp

  // External inputs to fetch
  fetch.io.bsy      := io.bsy
  fetch.io.jpaddr   := io.jpaddr
  fetch.io.extStall := io.extStall

  // External inputs to decode
  decode.io.zf    := io.zf
  decode.io.nf    := io.nf
  decode.io.eq    := io.eq
  decode.io.lt    := io.lt
  decode.io.bcopd := io.bcopd
  decode.io.stall := io.stall

  // Wire outputs
  io.pc_out  := fetch.io.pc_out
  io.ir_out  := fetch.io.ir_out
  io.nxt     := fetch.io.nxt
  io.opd     := fetch.io.opd
  io.dout    := fetch.io.dout
  io.br      := decode.io.br
  io.jmp     := decode.io.jmp
  io.jbr     := decode.io.jbr
  io.selSub  := decode.io.selSub
  io.enaA    := decode.io.enaA
  io.wrEna   := decode.io.wrEna
  io.selLmux := decode.io.selLmux
}

class PipelineIntegrationTest extends AnyFunSuite {

  val simConfig = TestVectorUtils.simWave(SimConfig
    .withConfig(SpinalConfig(
      defaultConfigForClockDomains = ClockDomainConfig(
        resetKind = BOOT
      ),
      defaultClockDomainFrequency = FixedFrequency(100 MHz)
    ))
    .workspacePath("simWorkspace"))

  lazy val compiled = simConfig.compile(FetchDecodeHarness())

  /** Initialize all harness inputs to safe defaults */
  def initInputs(dut: FetchDecodeHarness): Unit = {
    dut.io.bsy      #= false
    dut.io.jpaddr   #= 0
    dut.io.extStall #= false
    dut.io.zf       #= false
    dut.io.nf       #= false
    dut.io.eq       #= false
    dut.io.lt       #= false
    dut.io.bcopd    #= 0
    dut.io.stall    #= false
  }

  test("pipeline_integration: fetch drives decode instruction") {
    compiled.doSim("fetch_drives_decode") { dut =>
      dut.clockDomain.forkStimulus(10)
      initInputs(dut)

      // Let pipeline run for a few cycles; fetch starts at PC=0
      // and increments each cycle, feeding instructions to decode
      for (_ <- 0 until 5) {
        dut.clockDomain.waitRisingEdge()
      }

      // After 5 cycles, PC should have advanced and dout should be
      // whatever the ROM has at the current address
      val pc = dut.io.pc_out.toInt
      assert(pc > 0, s"PC should advance from 0, got $pc")

      // dout (= IR) should equal ir_out (they're the same signal)
      val dout = dut.io.dout.toLong
      val ir = dut.io.ir_out.toLong
      assert(dout == ir, s"dout ($dout) should equal ir_out ($ir)")
    }
  }

  test("pipeline_integration: decode br feeds back to fetch") {
    compiled.doSim("decode_br_feedback") { dut =>
      dut.clockDomain.forkStimulus(10)
      initInputs(dut)

      // Run a few cycles to let pipeline stabilize
      dut.clockDomain.waitRisingEdge(5)

      // Record current PC
      val pcBefore = dut.io.pc_out.toInt

      // Wait several more cycles — decode br/jmp feedback should affect PC.
      // The default ROM pattern includes a branch from 0x000 to 0x001 in normal
      // sequencing. We just verify the pipeline doesn't freeze.
      for (_ <- 0 until 10) {
        dut.clockDomain.waitRisingEdge()
      }

      val pcAfter = dut.io.pc_out.toInt
      assert(pcAfter != pcBefore,
        s"PC should have changed after 10 cycles (was $pcBefore, now $pcAfter)")
    }
  }

  test("pipeline_integration: extStall freezes both stages") {
    compiled.doSim("extStall_freeze") { dut =>
      dut.clockDomain.forkStimulus(10)
      initInputs(dut)

      // Let pipeline run to a stable state
      dut.clockDomain.waitRisingEdge(5)

      // Assert extStall
      dut.io.extStall #= true
      dut.clockDomain.waitRisingEdge()

      // Record state
      val pcFrozen = dut.io.pc_out.toInt
      val irFrozen = dut.io.ir_out.toLong

      // Stay stalled for 5 cycles
      for (_ <- 0 until 5) {
        dut.clockDomain.waitRisingEdge()
        assert(dut.io.pc_out.toInt == pcFrozen,
          s"PC should be frozen at $pcFrozen during extStall, got ${dut.io.pc_out.toInt}")
        assert(dut.io.ir_out.toLong == irFrozen,
          s"IR should be frozen at $irFrozen during extStall, got ${dut.io.ir_out.toLong}")
      }

      // Release stall
      dut.io.extStall #= false
      dut.clockDomain.waitRisingEdge(2)

      // PC should resume advancing
      val pcAfterRelease = dut.io.pc_out.toInt
      assert(pcAfterRelease != pcFrozen,
        s"PC should advance after releasing extStall (was $pcFrozen, now $pcAfterRelease)")
    }
  }

  test("pipeline_integration: decode stall holds registered outputs") {
    compiled.doSim("decode_stall") { dut =>
      dut.clockDomain.forkStimulus(10)
      initInputs(dut)

      // Let pipeline run to get some non-zero decode outputs
      dut.clockDomain.waitRisingEdge(5)

      // Assert decode stall
      dut.io.stall #= true
      dut.clockDomain.waitRisingEdge()

      // Record decode registered outputs
      val brFrozen = dut.io.br.toBoolean
      val jmpFrozen = dut.io.jmp.toBoolean
      val selSubFrozen = dut.io.selSub.toBoolean
      val enaAFrozen = dut.io.enaA.toBoolean
      val selLmuxFrozen = dut.io.selLmux.toLong

      // Stay stalled for 3 cycles — registered decode outputs should hold
      for (i <- 0 until 3) {
        dut.clockDomain.waitRisingEdge()
        assert(dut.io.br.toBoolean == brFrozen,
          s"Cycle $i: br should hold at $brFrozen during stall")
        assert(dut.io.jmp.toBoolean == jmpFrozen,
          s"Cycle $i: jmp should hold at $jmpFrozen during stall")
        assert(dut.io.selSub.toBoolean == selSubFrozen,
          s"Cycle $i: selSub should hold at $selSubFrozen during stall")
        assert(dut.io.enaA.toBoolean == enaAFrozen,
          s"Cycle $i: enaA should hold at $enaAFrozen during stall")
        assert(dut.io.selLmux.toLong == selLmuxFrozen,
          s"Cycle $i: selLmux should hold at $selLmuxFrozen during stall")
      }

      // Release stall
      dut.io.stall #= false
      dut.clockDomain.waitRisingEdge()
    }
  }

  test("pipeline_integration: fetch and decode run without deadlock") {
    compiled.doSim("no_deadlock") { dut =>
      dut.clockDomain.forkStimulus(10)
      initInputs(dut)

      // Run the pipeline for many cycles. With decode br/jmp feedback,
      // the PC may loop through a subset of addresses. Verify it doesn't
      // deadlock (PC must change at least once in 100 cycles).
      val pcValues = scala.collection.mutable.Set[Int]()
      for (_ <- 0 until 100) {
        dut.clockDomain.waitRisingEdge()
        pcValues += dut.io.pc_out.toInt
      }

      assert(pcValues.size > 1,
        s"PC should visit multiple addresses in 100 cycles, only visited: ${pcValues.toSeq.sorted}")
    }
  }
}
