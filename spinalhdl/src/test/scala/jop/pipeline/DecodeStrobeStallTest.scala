package jop.pipeline

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import jop.utils.JopSimDefaults

/**
 * A REGISTERED COMMAND FIRES ONCE PER INSTRUCTION, HOWEVER LONG THE STALL —
 * status items 160 and 133.
 *
 * DecodeStage registers each instruction's controls and applies them in the
 * next cycle. During a stack-cache stall (`io.stall`, a rotation or a word
 * served from the spill region) it used to HOLD them -- "hold previous values"
 * -- so the controls of the instruction BEFORE the stalled one stayed asserted
 * for the whole stall. The stack stage protects its own registers from that
 * with `rotBusyDly`. Nothing protected the other consumers, and they act on a
 * command in EVERY cycle it is high:
 *
 *   - the memory controller re-latched `addrReg := A` for a held `stmwa` --
 *     after `stmwa`'s own pop had changed A. In `int2extMem`'s loop,
 *     `stmwa; ldmi; stmwd` with the `ldmi` stalling, every saved stack word was
 *     then written to address ~= the loop counter: the bottom of memory, where
 *     the program image is. Found by jvm.ThreadAll on a stack-cache build: the
 *     first context switch away from a deep thread ran into "not implemented"
 *     bytecodes. A held `stmra`/`stmwd`/`putfield` re-issues the access.
 *   - the compute unit re-pushed / re-started / re-popped.
 *
 * The original VHDL pipeline never stalled like this, so every consumer was
 * written for one-shot commands. This pins that contract at its source.
 *
 * `jpc_wr` (from `stjpc`) is the one consumer that was gated the OTHER way --
 * the bytecode fetch ignored it in a frozen cycle -- and is checked where it is
 * consumed, in the pipeline-level tests.
 */
class DecodeStrobeStallTest extends AnyFunSuite {

  case class StrobeTb() extends Component {
    val dec = DecodeStage()
    val io = new Bundle {
      val instr = in Bits(10 bits)
      val stall = in Bool()
      val addrWr  = out Bool()
      val rd      = out Bool()
      val wr      = out Bool()
      val putfield = out Bool()
      val iastore = out Bool()
      val getstatic = out Bool()
      val cuStart = out Bool()
      val cuPush  = out Bool()
      val cuPop   = out Bool()
      val enaJpc  = out Bool()
    }
    dec.io.instr := io.instr
    dec.io.stall := io.stall
    dec.io.zf := False
    dec.io.nf := False
    dec.io.eq := False
    dec.io.lt := False
    dec.io.bcopd := 0
    io.addrWr := dec.io.memIn.addrWr
    io.rd := dec.io.memIn.rd
    io.wr := dec.io.memIn.wr
    io.putfield := dec.io.memIn.putfield
    io.iastore := dec.io.memIn.iastore
    io.getstatic := dec.io.memIn.getstatic
    io.cuStart := dec.io.cuStart
    io.cuPush := dec.io.cuPush
    io.cuPop := dec.io.cuPop
    io.enaJpc := dec.io.enaJpc
  }

  private lazy val compiled = JopSimDefaults.config.compile(StrobeTb())

  private val NOP  = 0x100
  private val LDMI = 0x0ED   // the instruction that stalls (a word served from the spill region)

  /** (name, instruction encoding, the strobe it raises) */
  private val cases: Seq[(String, Int, StrobeTb => Bool)] = Seq(
    ("stmwa -> addrWr",    0x041, _.io.addrWr),
    ("stmra -> rd",        0x042, _.io.rd),
    ("stmwd -> wr",        0x043, _.io.wr),
    ("stast -> iastore",   0x045, _.io.iastore),
    ("stpf -> putfield",   0x047, _.io.putfield),
    ("stgs -> getstatic",  0x110, _.io.getstatic),
    ("sthw -> cuStart",    0x140, _.io.cuStart),
    ("stop -> cuPush",     0x01F, _.io.cuPush),
    ("ldop -> cuPop",      0x0E1, _.io.cuPop),
    ("stjpc -> enaJpc",    0x019, _.io.enaJpc))

  test("every registered command fires exactly once across a 10-cycle stall") {
    val failures = scala.collection.mutable.ArrayBuffer[String]()
    compiled.doSim("strobe-stall") { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.instr #= NOP
      dut.io.stall #= false
      dut.clockDomain.waitRisingEdge(3)

      for ((name, enc, strobe) <- cases) {
        // Decode cycle of the command.
        dut.io.instr #= enc
        dut.io.stall #= false
        dut.clockDomain.waitRisingEdge()
        // Its effect cycle -- and the NEXT instruction stalls in decode.
        dut.io.instr #= LDMI
        dut.io.stall #= true
        var high = if (strobe(dut).toBoolean) 1 else 0
        for (_ <- 0 until 10) {
          dut.clockDomain.waitRisingEdge()
          if (strobe(dut).toBoolean) high += 1
        }
        // The retry cycle, then a nop.
        dut.io.stall #= false
        dut.clockDomain.waitRisingEdge()
        if (strobe(dut).toBoolean) high += 1
        dut.io.instr #= NOP
        dut.clockDomain.waitRisingEdge(2)
        if (high != 1) failures += s"$name: asserted for $high cycles, not 1"
      }
    }
    assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
  }

  /** The control: without a stall each command is already one-shot. */
  test("CONTROL: without a stall every command fires exactly once") {
    val failures = scala.collection.mutable.ArrayBuffer[String]()
    compiled.doSim("strobe-nostall") { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.instr #= NOP
      dut.io.stall #= false
      dut.clockDomain.waitRisingEdge(3)
      for ((name, enc, strobe) <- cases) {
        dut.io.instr #= enc
        dut.clockDomain.waitRisingEdge()
        dut.io.instr #= NOP
        var high = if (strobe(dut).toBoolean) 1 else 0
        for (_ <- 0 until 10) {
          dut.clockDomain.waitRisingEdge()
          if (strobe(dut).toBoolean) high += 1
        }
        if (high != 1) failures += s"$name: asserted for $high cycles, not 1"
      }
    }
    assert(failures.isEmpty, failures.mkString("\n  ", "\n  ", ""))
  }
}
