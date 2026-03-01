package jop.ip.fpu

import spinal.core._
import spinal.lib._

case class FpuDivCmd(mantissaWidth : Int) extends Bundle{
  val a,b = UInt(mantissaWidth bits)
}

case class FpuDivRsp(mantissaWidth : Int) extends Bundle{
  val result = UInt(mantissaWidth+1 + 2 bits)
  val remain = UInt(mantissaWidth+1 bits)
}

case class FpuDiv(val mantissaWidth : Int) extends Component {
  assert(mantissaWidth % 2 == 0)
  val io = new Bundle{
    val input = slave Stream(FpuDivCmd(mantissaWidth))
    val output = master Stream(FpuDivRsp(mantissaWidth))
  }

  val iterations = (mantissaWidth+2+2)/2
  val counter = Reg(UInt(log2Up(iterations) bits))
  val busy = RegInit(False) clearWhen(io.output.fire)
  val done = RegInit(False) setWhen(busy && counter === iterations-1) clearWhen(io.output.fire)

  val shifter = Reg(UInt(mantissaWidth + 3 bits))
  val result = Reg(UInt(mantissaWidth+1+2 bits))

  val div1, div3 = Reg(UInt(mantissaWidth+3 bits))
  val div2 = div1 |<< 1

  val sub1 = shifter -^ div1
  val sub2 = shifter -^ div2
  val sub3 = shifter -^ div3

  io.output.valid := done
  io.output.result := (result << 0).resized
  io.output.remain := (shifter >> 2).resized
  io.input.ready := !busy

  when(!done){
    counter := counter + 1
    val sel = CombInit(shifter)
    result := result |<< 2
    when(!sub1.msb){
      sel := sub1.resized
      result(1 downto 0) := 1
    }
    when(!sub2.msb){
      sel := sub2.resized
      result(1 downto 0) := 2
    }
    when(!sub3.msb){
      sel := sub3.resized
      result(1 downto 0) := 3
    }
    shifter := sel |<< 2
  }

  when(!busy){
    counter := 0
    shifter := (U"1" @@ io.input.a @@ U"").resized
    div1    := (U"1" @@ io.input.b).resized
    div3    := (U"1" @@ io.input.b) +^ (((U"1" @@ io.input.b)) << 1)
    busy := io.input.valid
  }
}
