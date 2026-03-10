/**
  * ComputeUnitBench — Standalone synthesis wrappers for timing analysis
  *
  * Each wrapper registers all inputs and outputs to isolate the CU's
  * combinational logic from I/O timing. This gives accurate WNS for
  * the unit's internal critical path only.
  *
  * Usage: sbt "runMain jop.core.ComputeUnitBenchVerilog"
  * Generates: spinalhdl/generated/IntegerCuBench.v, FloatCuBench.v, LongCuBench.v, DoubleCuBench.v
  */
package jop.core

import spinal.core._

/** Registered wrapper around IntegerComputeUnit for synthesis benchmarking */
case class IntegerCuBench() extends Component {
  val io = new Bundle {
    val operands    = in Vec(UInt(32 bits), 4)
    val op          = in UInt(4 bits)
    val start       = in Bool()
    val resultLo    = out UInt(32 bits)
    val resultHi    = out UInt(32 bits)
    val resultCount = out UInt(2 bits)
    val busy        = out Bool()
  }

  val opRegs    = Vec(Reg(UInt(32 bits)) init(0), 4)
  val opReg     = Reg(UInt(4 bits)) init(0)
  val startReg  = Reg(Bool()) init(False)
  for (i <- 0 until 4) opRegs(i) := io.operands(i)
  opReg    := io.op
  startReg := io.start

  val icu = IntegerComputeUnit(IntegerComputeUnitConfig(
    withMul = true, withDiv = true, withRem = true
  ))
  icu.io.operands := opRegs
  icu.io.op       := opReg
  icu.io.start    := startReg

  io.resultLo    := RegNext(icu.io.resultLo) init(0)
  io.resultHi    := RegNext(icu.io.resultHi) init(0)
  io.resultCount := RegNext(icu.io.resultCount) init(0)
  io.busy        := RegNext(icu.io.busy) init(False)
}

/** Registered wrapper around FloatComputeUnit for synthesis benchmarking */
case class FloatCuBench() extends Component {
  val io = new Bundle {
    val operands    = in Vec(UInt(32 bits), 4)
    val op          = in UInt(4 bits)
    val start       = in Bool()
    val resultLo    = out UInt(32 bits)
    val resultHi    = out UInt(32 bits)
    val resultCount = out UInt(2 bits)
    val busy        = out Bool()
  }

  // Register all inputs
  val opRegs    = Vec(Reg(UInt(32 bits)) init(0), 4)
  val opReg     = Reg(UInt(4 bits)) init(0)
  val startReg  = Reg(Bool()) init(False)
  for (i <- 0 until 4) opRegs(i) := io.operands(i)
  opReg    := io.op
  startReg := io.start

  // Instantiate FCU with all features enabled
  val fcu = FloatComputeUnit(FloatComputeUnitConfig(
    withAdd = true, withMul = true, withDiv = true,
    withI2F = true, withF2I = true, withFcmp = true
  ))
  fcu.io.operands := opRegs
  fcu.io.op       := opReg
  fcu.io.start    := startReg

  // Register all outputs
  io.resultLo    := RegNext(fcu.io.resultLo) init(0)
  io.resultHi    := RegNext(fcu.io.resultHi) init(0)
  io.resultCount := RegNext(fcu.io.resultCount) init(0)
  io.busy        := RegNext(fcu.io.busy) init(False)
}

/** Registered wrapper around LongComputeUnit for synthesis benchmarking */
case class LongCuBench() extends Component {
  val io = new Bundle {
    val operands    = in Vec(UInt(32 bits), 4)
    val op          = in UInt(4 bits)
    val start       = in Bool()
    val resultLo    = out UInt(32 bits)
    val resultHi    = out UInt(32 bits)
    val resultCount = out UInt(2 bits)
    val busy        = out Bool()
  }

  val opRegs    = Vec(Reg(UInt(32 bits)) init(0), 4)
  val opReg     = Reg(UInt(4 bits)) init(0)
  val startReg  = Reg(Bool()) init(False)
  for (i <- 0 until 4) opRegs(i) := io.operands(i)
  opReg    := io.op
  startReg := io.start

  val lcu = LongComputeUnit(LongComputeUnitConfig(
    withMul = true, withDiv = true, withRem = true, withShift = true
  ))
  lcu.io.operands := opRegs
  lcu.io.op       := opReg
  lcu.io.start    := startReg

  io.resultLo    := RegNext(lcu.io.resultLo) init(0)
  io.resultHi    := RegNext(lcu.io.resultHi) init(0)
  io.resultCount := RegNext(lcu.io.resultCount) init(0)
  io.busy        := RegNext(lcu.io.busy) init(False)
}

/** Registered wrapper around DoubleComputeUnit for synthesis benchmarking */
case class DoubleCuBench() extends Component {
  val io = new Bundle {
    val operands    = in Vec(UInt(32 bits), 4)
    val op          = in UInt(4 bits)
    val start       = in Bool()
    val resultLo    = out UInt(32 bits)
    val resultHi    = out UInt(32 bits)
    val resultCount = out UInt(2 bits)
    val busy        = out Bool()
  }

  val opRegs    = Vec(Reg(UInt(32 bits)) init(0), 4)
  val opReg     = Reg(UInt(4 bits)) init(0)
  val startReg  = Reg(Bool()) init(False)
  for (i <- 0 until 4) opRegs(i) := io.operands(i)
  opReg    := io.op
  startReg := io.start

  val dcu = DoubleComputeUnit(DoubleComputeUnitConfig(
    withAdd = true, withMul = true, withDiv = true,
    withI2D = true, withD2I = true, withL2D = true,
    withD2L = true, withF2D = true, withD2F = true,
    withDcmp = true
  ))
  dcu.io.operands := opRegs
  dcu.io.op       := opReg
  dcu.io.start    := startReg

  io.resultLo    := RegNext(dcu.io.resultLo) init(0)
  io.resultHi    := RegNext(dcu.io.resultHi) init(0)
  io.resultCount := RegNext(dcu.io.resultCount) init(0)
  io.busy        := RegNext(dcu.io.busy) init(False)
}

/** Verilog generator for all three benchmarks */
object ComputeUnitBenchVerilog {
  def main(args: Array[String]): Unit = {
    val cfg = SpinalConfig(
      mode = Verilog,
      targetDirectory = "spinalhdl/generated"
    )
    cfg.generate(IntegerCuBench()).printPruned()
    cfg.generate(FloatCuBench()).printPruned()
    cfg.generate(LongCuBench()).printPruned()
    cfg.generate(DoubleCuBench()).printPruned()
    println("INFO: Generated IntegerCuBench.v, FloatCuBench.v, LongCuBench.v, DoubleCuBench.v")
  }
}
