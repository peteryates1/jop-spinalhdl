package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._

/**
 * Minimal UART TX test — just outputs "HELLO\r\n" repeatedly.
 * Used to debug FlashWriterTop UART issue.
 */
case class UartTestTop() extends Component {

  val io = new Bundle {
    val clk_in  = in Bool()
    val ser_txd = out Bool()
    val ser_rxd = in Bool()
    val led     = out Bits(2 bits)
  }

  noIoPrefix()

  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False

  val rawClockDomain = ClockDomain(
    clock = pll.io.c1,
    config = ClockDomainConfig(resetKind = BOOT)
  )
  val resetGen = new ClockingArea(rawClockDomain) {
    val res_cnt = Reg(UInt(3 bits)) init (0)
    when(pll.io.locked && res_cnt =/= 7) {
      res_cnt := res_cnt + 1
    }
    val int_res = !pll.io.locked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
  }
  val mainClockDomain = ClockDomain(
    clock = pll.io.c1,
    reset = resetGen.int_res,
    frequency = FixedFrequency(80 MHz),
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )

  val mainArea = new ClockingArea(mainClockDomain) {

    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
    ))
    uartCtrl.io.config.setClockDivider(1000000 Hz)
    uartCtrl.io.config.frame.dataLength := 7
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False
    uartCtrl.io.uart.rxd := io.ser_rxd
    io.ser_txd := uartCtrl.io.uart.txd

    // Always consume RX
    uartCtrl.io.read.ready := True

    // Simple message: "HELLO\r\n"
    val msg = Vec(
      B(0x48, 8 bits), B(0x45, 8 bits), B(0x4C, 8 bits), B(0x4C, 8 bits),
      B(0x4F, 8 bits), B(0x0D, 8 bits), B(0x0A, 8 bits)
    )

    val initCnt = Reg(UInt(28 bits)) init (0)
    val msgIdx  = Reg(UInt(3 bits)) init (0)
    val sending = Reg(Bool()) init (False)
    val loopCnt = Reg(UInt(16 bits)) init (0)

    uartCtrl.io.write.valid := False
    uartCtrl.io.write.payload := msg(msgIdx)

    io.led := ~loopCnt(1 downto 0).asBits

    when(!sending) {
      initCnt := initCnt + 1
      when(initCnt.andR) {
        sending := True
        msgIdx := 0
      }
    } otherwise {
      uartCtrl.io.write.valid := True
      when(uartCtrl.io.write.ready) {
        msgIdx := msgIdx + 1
        when(msgIdx === 6) {
          msgIdx := 0
          loopCnt := loopCnt + 1
        }
      }
    }
  }
}

/** The design behind the EP4CGX150 UART bring-up test.
  *
  * This one had a hand-written uart_test.qsf and NO Makefile target -- the
  * project file and the RTL both survived while nothing could build either.
  * Converted and given a target rather than deleted: it is the smallest thing
  * on this board that proves a console end to end, which is worth having when
  * a board looks dead. */
object UartTestDesign extends jop.config.BoardDesign {
  import jop.config._
  val assembly   = SystemAssembly.qmtechWithDb
  val entityName = "UartTestTop"
  val designName = "uart-test"
  val uartBaud   = 1000000
  val devices    = Map(
    "uart" -> DeviceInstance(DeviceType.Uart, devicePart = Some("CP2102N"),
                             params = Map("baudRate" -> uartBaud)))
  val resetInput = None
  val usesSdr    = false
  val memType    = None
  val fpga       = assembly.fpga
  val fpgaFamily = assembly.fpgaFamily
  val clkMhz     = 80
}

/** Emit everything Quartus needs for the UART test. */
object UartTestBuild extends App {
  import jop.generate._
  val design   = UartTestDesign
  val cfgName  = "uartTest"
  val revision = "uart_test"
  val layout   = BuildLayout.default
  val cfgDir   = layout.configDir(cfgName, Seq.empty)

  SpinalConfig(
    mode = Verilog,
    targetDirectory = layout.rtlDir(cfgName, Seq.empty),
    defaultClockDomainFrequency = FixedFrequency(HertzNumber(design.clkMhz * 1000000L))
  ).generate(UartTestTop())

  jop.config.DramPllGen.emit("fpga/qmtech-ep4cgx150-sdram", design.clkMhz, cfgDir)

  StandaloneBuild.summary(cfgName, design.entityName,
    board = design.assembly.fpgaBoard.name, fpga = design.fpga.name,
    clkMhz = design.clkMhz, uartBaud = Some(design.uartBaud))

  def write(path: String, body: String): Unit = {
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new java.io.PrintWriter(f)
    try w.print(body) finally w.close()
    println(s"Wrote $path")
  }
  write(s"$cfgDir/quartus/$revision.sdc", TimingConstraints.forConfig(design).toSdc)
  write(s"$cfgDir/quartus/setup_proj.tcl",
        QuartusProject.generate(design, revision, cfgName, Seq.empty))
}
