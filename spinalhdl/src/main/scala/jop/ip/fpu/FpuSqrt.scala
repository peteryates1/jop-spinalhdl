package jop.ip.fpu

import spinal.core._
import spinal.lib._

case class FpuSqrtCmd(mantissaWidth : Int) extends Bundle{
  val a = UInt(mantissaWidth+2 bits)
}

case class FpuSqrtRsp(mantissaWidth : Int) extends Bundle{
  val result = UInt(mantissaWidth+1 bits)
  val remain = UInt(mantissaWidth+5 bits)
}

case class FpuSqrt(val mantissaWidth : Int) extends Component {
  val io = new Bundle{
    val input = slave Stream(FpuSqrtCmd(mantissaWidth))
    val output = master Stream(FpuSqrtRsp(mantissaWidth))
  }

  val iterations = mantissaWidth+2
  val counter = Reg(UInt(log2Up(iterations ) bits))
  val busy = RegInit(False) clearWhen(io.output.fire)
  val done = RegInit(False) setWhen(busy && counter === iterations-1) clearWhen(io.output.fire)

  val a = Reg(UInt(mantissaWidth+5 bits))
  val x = Reg(UInt(mantissaWidth bits))
  val q = Reg(UInt(mantissaWidth+1 bits))
  val t = a-(q @@ U"01")


  io.output.valid := done
  io.output.result := (q << 0).resized
  io.output.remain := a
  io.input.ready := !busy

  when(!done){
    counter := counter + 1
    val sel = CombInit(a)
    when(!t.msb){
      sel := t.resized
    }
    q := (q @@ !t.msb).resized
    a := (sel @@ x(widthOf(x)-2,2 bits)).resized
    x := x |<< 2
  }

  when(!busy){
    q := 0
    a := io.input.a(widthOf(io.input.a)-2,2 bits).resized
    x := (io.input.a).resized
    counter := 0
    when(io.input.valid){
      busy := True
    }
  }
}
