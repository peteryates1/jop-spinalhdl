package jop.system

import spinal.core._
import spinal.lib._
import spinal.lib.com.uart._
import spinal.lib.io.InOutWrapper
import spinal.lib.memory.sdram.sdr._
import jop.memory.{BmbSdramCtrl32, JopMemoryConfig}

/**
 * SDRAM Exerciser FPGA Top
 *
 * Directly exercises BmbSdramCtrl32 with JOP-like memory access patterns
 * and reports results via UART at 1 Mbaud. No JOP processor involved.
 *
 * Tests:
 *   T1: Sequential fill + readback (1024 words)
 *   T2: memCopy pattern — fill src, copy to dst, verify (512 words)
 *   T3: Write-then-read same address back-to-back (256 words)
 *   All tests loop continuously, reporting pass/fail via UART.
 */
case class SdramExerciserTop() extends Component {

  val io = new Bundle {
    val clk_in    = in Bool()
    val ser_txd   = out Bool()
    val led       = out Bits(2 bits)
    val sdram_clk = out Bool()
    val sdram     = master(SdramInterface(W9825G6JH6.layout))
  }

  noIoPrefix()

  // PLL: 50 MHz -> 100 MHz system, -3ns SDRAM clock
  val pll = DramPll()
  pll.io.inclk0 := io.clk_in
  pll.io.areset := False
  io.sdram_clk := pll.io.c2

  // Reset generator
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
    frequency = FixedFrequency(100 MHz),
    config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)
  )

  // ========================================================================
  // Main Design
  // ========================================================================

  val mainArea = new ClockingArea(mainClockDomain) {

    // Same BMB parameters as JOP SDRAM config
    val memConfig = JopMemoryConfig(burstLen = 4)

    // SDRAM controller — identical chain to JOP
    val sdramCtrl = BmbSdramCtrl32(
      bmbParameter = memConfig.bmbParameter,
      layout = W9825G6JH6.layout,
      timing = W9825G6JH6.timingGrade7,
      CAS = 3
    )
    io.sdram <> sdramCtrl.io.sdram

    // UART TX (1 Mbaud)
    val uartCtrl = new UartCtrl(UartCtrlGenerics(
      preSamplingSize = 1, samplingSize = 3, postSamplingSize = 1
    ))
    uartCtrl.io.config.setClockDivider(1000000 Hz)
    uartCtrl.io.config.frame.dataLength := 7
    uartCtrl.io.config.frame.parity := UartParityType.NONE
    uartCtrl.io.config.frame.stop := UartStopType.ONE
    uartCtrl.io.writeBreak := False
    uartCtrl.io.uart.rxd := True
    io.ser_txd := uartCtrl.io.uart.txd

    val txFifo = StreamFifo(Bits(8 bits), 256)
    uartCtrl.io.write.valid := txFifo.io.pop.valid
    uartCtrl.io.write.payload := txFifo.io.pop.payload
    txFifo.io.pop.ready := uartCtrl.io.write.ready

    // Default: don't push
    txFifo.io.push.valid := False
    txFifo.io.push.payload := 0

    // ====================================================================
    // Message ROM
    // ====================================================================
    val msgBytes: Seq[Int] = {
      //  0: "SDRAM TEST\r\n"   (12)
      val m0 = "SDRAM TEST\r\n"
      // 12: "T1:SEQWR "        (9)
      val m1 = "T1:SEQWR "
      // 21: "T2:COPY  "        (9)
      val m2 = "T2:COPY  "
      // 30: "T3:WRTRD "        (9)
      val m3 = "T3:WRTRD "
      // 39: "PASS\r\n"         (6)
      val m4 = "PASS\r\n"
      // 45: "FAIL "             (5)
      val m5 = "FAIL "
      // 50: " E="               (3)
      val m6 = " E="
      // 53: " G="               (3)
      val m7 = " G="
      // 56: "\r\n"              (2)
      val m8 = "\r\n"
      // 58: "LOOP "             (5)
      val m9 = "LOOP "
      (m0 + m1 + m2 + m3 + m4 + m5 + m6 + m7 + m8 + m9).map(_.toInt)
    }
    val msgRom = Mem(Bits(8 bits), initialContent = msgBytes.map(c => B(c & 0xFF, 8 bits)))

    // ====================================================================
    // State Machine
    // ====================================================================
    object S extends SpinalEnum {
      val INIT_WAIT = newElement()
      // Generic print sub-states
      val PRINT_MSG, PRINT_HEX = newElement()
      // Test 1: Sequential write/read
      val T1_LABEL, T1_W, T1_W_RSP, T1_R, T1_R_RSP = newElement()
      // Test 2: memCopy
      val T2_LABEL, T2_F, T2_F_RSP, T2_CR, T2_CR_RSP, T2_CW, T2_CW_RSP, T2_V, T2_V_RSP = newElement()
      // Test 3: Write-then-read
      val T3_LABEL, T3_W, T3_W_RSP, T3_R, T3_R_RSP = newElement()
      // Result printing
      val RESULT, RESULT_SEQ = newElement()
      // Loop
      val LOOP_MSG, LOOP_HEX, LOOP_CRLF = newElement()
    }

    val state = RegInit(S.INIT_WAIT)
    val retState = Reg(S())

    // Init counter
    val initCnt = Reg(UInt(24 bits)) init (0)

    // Print state
    val msgBase = Reg(UInt(7 bits))
    val msgLen = Reg(UInt(7 bits))
    val msgIdx = Reg(UInt(7 bits))

    // Hex print state
    val hexVal = Reg(Bits(32 bits))
    val hexNib = Reg(UInt(4 bits))

    // BMB address/data
    val addr = Reg(UInt(24 bits)) init (0)
    val wrData = Reg(Bits(32 bits)) init (0)
    val rdData = Reg(Bits(32 bits)) init (0)
    val expected = Reg(Bits(32 bits)) init (0)

    // Test iteration
    val idx = Reg(UInt(12 bits)) init (0)
    val cnt = Reg(UInt(12 bits)) init (0)

    // Test results
    val failCnt = Reg(UInt(16 bits)) init (0)
    val failAddr = Reg(UInt(24 bits)) init (0)
    val failExp = Reg(Bits(32 bits)) init (0)
    val failGot = Reg(Bits(32 bits)) init (0)
    val testNum = Reg(UInt(4 bits)) init (0)
    val loopCnt = Reg(UInt(16 bits)) init (0)
    val resultSeq = Reg(UInt(4 bits)) init (0)

    // Copy buffer
    val copyBuf = Reg(Bits(32 bits))

    // LED
    val ledReg = Reg(Bits(2 bits)) init (0)
    io.led := ~ledReg

    // Test regions
    def T1_BASE = U(0x000, 24 bits)
    def T1_CNT  = U(1024, 12 bits)
    def T2_SRC  = U(0x1000, 24 bits)
    def T2_DST  = U(0x2000, 24 bits)
    def T2_CNT  = U(512, 12 bits)
    def T3_BASE = U(0x3000, 24 bits)
    def T3_CNT  = U(256, 12 bits)

    // Pattern: addr XOR 0xA5A5A5A5
    def pat(a: UInt): Bits = (a.resize(32).asBits ^ B(0xA5A5A5A5L, 32 bits))

    // ====================================================================
    // BMB Interface
    // ====================================================================
    val bmb = sdramCtrl.io.bmb
    val bmbIsWrite = Reg(Bool()) init (False)

    bmb.cmd.valid := False
    bmb.cmd.last := True
    bmb.cmd.fragment.opcode := Mux(bmbIsWrite, B"1", B"0")
    bmb.cmd.fragment.address := (addr << 2).resized
    bmb.cmd.fragment.length := 3
    bmb.cmd.fragment.source := 0
    bmb.cmd.fragment.context := 0
    bmb.cmd.fragment.data := wrData
    bmb.cmd.fragment.mask := B"1111"
    bmb.rsp.ready := True

    // Helper: record failure
    def recordFail(got: Bits): Unit = {
      when(failCnt === 0) {
        failAddr := addr
        failExp := expected
        failGot := got
      }
      failCnt := failCnt + 1
    }

    // ====================================================================
    // State Machine Body
    // ====================================================================
    switch(state) {

      // ================================================================
      // Init: wait for SDRAM boot
      // ================================================================
      is(S.INIT_WAIT) {
        initCnt := initCnt + 1
        when(initCnt.andR) {
          msgBase := 0; msgLen := 12; msgIdx := 0
          retState := S.T1_LABEL; state := S.PRINT_MSG
        }
      }

      // ================================================================
      // Print sub-state: msgRom[msgBase..+msgLen)
      // ================================================================
      is(S.PRINT_MSG) {
        when(txFifo.io.push.ready) {
          txFifo.io.push.valid := True
          txFifo.io.push.payload := msgRom.readAsync((msgBase + msgIdx).resized)
          msgIdx := msgIdx + 1
          when(msgIdx + 1 >= msgLen) {
            state := retState
          }
        }
      }

      // ================================================================
      // Hex print sub-state: 8 hex digits
      // ================================================================
      is(S.PRINT_HEX) {
        when(txFifo.io.push.ready) {
          val nib = hexVal(31 downto 28).asUInt
          val ch = Bits(8 bits)
          when(nib < 10) {
            ch := (nib + 0x30).asBits.resized
          } otherwise {
            ch := (nib + 0x57).asBits.resized
          }
          txFifo.io.push.valid := True
          txFifo.io.push.payload := ch
          hexVal := hexVal |<< 4
          hexNib := hexNib + 1
          when(hexNib === 7) {
            state := retState
          }
        }
      }

      // ================================================================
      // Test 1: Sequential write + readback (1024 words)
      // ================================================================
      is(S.T1_LABEL) {
        testNum := 1; idx := 0; cnt := T1_CNT; failCnt := 0
        msgBase := 12; msgLen := 9; msgIdx := 0
        retState := S.T1_W; state := S.PRINT_MSG
      }
      is(S.T1_W) {
        val a = T1_BASE + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := pat(a)
        when(bmb.cmd.fire) { state := S.T1_W_RSP }
      }
      is(S.T1_W_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx + 1 >= cnt) {
            idx := 0
            state := S.T1_R
          } otherwise {
            state := S.T1_W
          }
        }
      }
      is(S.T1_R) {
        val a = T1_BASE + idx.resized
        addr := a
        expected := pat(a)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T1_R_RSP }
      }
      is(S.T1_R_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx + 1 >= cnt) {
            state := S.RESULT
          } otherwise {
            state := S.T1_R
          }
        }
      }

      // ================================================================
      // Test 2: memCopy (fill src → copy → verify dst)
      // ================================================================
      is(S.T2_LABEL) {
        testNum := 2; idx := 0; cnt := T2_CNT; failCnt := 0
        msgBase := 21; msgLen := 9; msgIdx := 0
        retState := S.T2_F; state := S.PRINT_MSG
      }
      // Fill source region
      is(S.T2_F) {
        val a = T2_SRC + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := pat(a)
        when(bmb.cmd.fire) { state := S.T2_F_RSP }
      }
      is(S.T2_F_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx + 1 >= cnt) {
            idx := 0
            state := S.T2_CR
          } otherwise {
            state := S.T2_F
          }
        }
      }
      // Copy: read from src
      is(S.T2_CR) {
        val a = T2_SRC + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T2_CR_RSP }
      }
      is(S.T2_CR_RSP) {
        when(bmb.rsp.fire) {
          copyBuf := bmb.rsp.fragment.data
          state := S.T2_CW
        }
      }
      // Copy: write to dst
      is(S.T2_CW) {
        val a = T2_DST + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := copyBuf
        when(bmb.cmd.fire) { state := S.T2_CW_RSP }
      }
      is(S.T2_CW_RSP) {
        when(bmb.rsp.fire) {
          idx := idx + 1
          when(idx + 1 >= cnt) {
            idx := 0
            state := S.T2_V
          } otherwise {
            state := S.T2_CR
          }
        }
      }
      // Verify destination
      is(S.T2_V) {
        val a = T2_DST + idx.resized
        addr := a
        expected := pat(T2_SRC + idx.resized)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T2_V_RSP }
      }
      is(S.T2_V_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx + 1 >= cnt) {
            state := S.RESULT
          } otherwise {
            state := S.T2_V
          }
        }
      }

      // ================================================================
      // Test 3: Write-then-read same address (256 addrs)
      // ================================================================
      is(S.T3_LABEL) {
        testNum := 3; idx := 0; cnt := T3_CNT; failCnt := 0
        msgBase := 30; msgLen := 9; msgIdx := 0
        retState := S.T3_W; state := S.PRINT_MSG
      }
      is(S.T3_W) {
        val a = T3_BASE + idx.resized
        addr := a
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"1"
        bmb.cmd.fragment.address := (a << 2).resized
        bmb.cmd.fragment.data := pat(a)
        when(bmb.cmd.fire) { state := S.T3_W_RSP }
      }
      is(S.T3_W_RSP) {
        when(bmb.rsp.fire) {
          // Immediately read same address
          state := S.T3_R
        }
      }
      is(S.T3_R) {
        val a = T3_BASE + idx.resized
        addr := a
        expected := pat(a)
        bmb.cmd.valid := True
        bmb.cmd.fragment.opcode := B"0"
        bmb.cmd.fragment.address := (a << 2).resized
        when(bmb.cmd.fire) { state := S.T3_R_RSP }
      }
      is(S.T3_R_RSP) {
        when(bmb.rsp.fire) {
          when(bmb.rsp.fragment.data =/= expected) {
            recordFail(bmb.rsp.fragment.data)
          }
          idx := idx + 1
          when(idx + 1 >= cnt) {
            state := S.RESULT
          } otherwise {
            state := S.T3_W
          }
        }
      }

      // ================================================================
      // Result: print PASS or FAIL with details, then next test
      // ================================================================
      is(S.RESULT) {
        resultSeq := 0
        state := S.RESULT_SEQ
      }
      is(S.RESULT_SEQ) {
        switch(resultSeq) {
          is(0) {
            when(failCnt === 0) {
              msgBase := 39; msgLen := 6; msgIdx := 0 // "PASS\r\n"
              retState := S.RESULT_SEQ; resultSeq := 10
              state := S.PRINT_MSG
            } otherwise {
              msgBase := 45; msgLen := 5; msgIdx := 0 // "FAIL "
              retState := S.RESULT_SEQ; resultSeq := 1
              state := S.PRINT_MSG
            }
          }
          is(1) { // hex(failAddr)
            hexVal := failAddr.asBits.resized; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 2
            state := S.PRINT_HEX
          }
          is(2) { // " E="
            msgBase := 50; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 3
            state := S.PRINT_MSG
          }
          is(3) { // hex(failExp)
            hexVal := failExp; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 4
            state := S.PRINT_HEX
          }
          is(4) { // " G="
            msgBase := 53; msgLen := 3; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 5
            state := S.PRINT_MSG
          }
          is(5) { // hex(failGot)
            hexVal := failGot; hexNib := 0
            retState := S.RESULT_SEQ; resultSeq := 6
            state := S.PRINT_HEX
          }
          is(6) { // "\r\n"
            msgBase := 56; msgLen := 2; msgIdx := 0
            retState := S.RESULT_SEQ; resultSeq := 10
            state := S.PRINT_MSG
          }
          is(10) { // Advance to next test
            switch(testNum) {
              is(1) { state := S.T2_LABEL }
              is(2) { state := S.T3_LABEL }
              is(3) { state := S.LOOP_MSG }
              default { state := S.LOOP_MSG }
            }
          }
          default {}
        }
      }

      // ================================================================
      // Loop: print count and restart
      // ================================================================
      is(S.LOOP_MSG) {
        loopCnt := loopCnt + 1
        ledReg := loopCnt.asBits.resized
        msgBase := 58; msgLen := 5; msgIdx := 0 // "LOOP "
        retState := S.LOOP_HEX
        state := S.PRINT_MSG
      }
      is(S.LOOP_HEX) {
        hexVal := loopCnt.asBits.resized; hexNib := 0
        retState := S.LOOP_CRLF
        state := S.PRINT_HEX
      }
      is(S.LOOP_CRLF) {
        msgBase := 56; msgLen := 2; msgIdx := 0 // "\r\n"
        retState := S.T1_LABEL
        state := S.PRINT_MSG
      }
    }
  }
}

/**
 * Generate Verilog for SdramExerciserTop
 */
object SdramExerciserTopVerilog extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated",
    defaultClockDomainFrequency = FixedFrequency(100 MHz)
  ).generate(InOutWrapper(SdramExerciserTop()))

  println("Generated: spinalhdl/generated/SdramExerciserTop.v")
}
