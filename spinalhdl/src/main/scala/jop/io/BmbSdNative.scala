package jop.io

import spinal.core._
import spinal.lib._

/**
 * SD Native Mode Controller — 4-bit SD with JOP I/O register interface
 *
 * Hardware handles clock generation, CMD shift register with CRC7,
 * DATA shift register with per-line CRC16, and a 512-byte block FIFO.
 * Software drives the SD protocol (CMD sequences).
 *
 * Register map:
 *   0x0 R: Status  W: Control (sendCmd, abort, openDrain)
 *   0x1 R: CMD response[31:0]  W: CMD argument[31:0]
 *   0x2 R: CMD response[63:32]  W: CMD index/flags
 *   0x3 R: CMD response[95:64] (R2)
 *   0x4 R: CMD response[127:96] (R2)
 *   0x5 R: Data FIFO pop  W: Data FIFO push
 *   0x6 R: RX FIFO occupancy  W: Clock divider
 *   0x7 R: CRC error flags  W: Data xfer control (startRead/startWrite)
 *   0x8 R: cardPresent|busWidth4  W: busWidth4
 *   0x9 W: Block length in bytes
 *
 * @param clkDivInit Initial clock divider (default 99 -> 400kHz at 80MHz)
 */
case class BmbSdNative(clkDivInit: Int = 99) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)

    // SD card pins
    val sdClk = out Bool()
    val sdCmd = new Bundle {
      val write       = out Bool()
      val writeEnable = out Bool()
      val read        = in Bool()
    }
    val sdDat = new Bundle {
      val write       = out Bits(4 bits)
      val writeEnable = out Bits(4 bits)
      val read        = in Bits(4 bits)
    }
    val sdCd = in Bool() // Card detect (active low)

    val interrupt = out Bool()
  }

  // ========================================================================
  // CRC helpers
  // ========================================================================

  /** CRC7 update: polynomial x^7 + x^3 + 1 (0x09) */
  def crc7Update(crc: Bits, bit: Bool): Bits = {
    val inv = crc(6) ^ bit
    val next = Bits(7 bits)
    next(0) := inv
    next(1) := crc(0)
    next(2) := crc(1)
    next(3) := crc(2) ^ inv
    next(4) := crc(3)
    next(5) := crc(4)
    next(6) := crc(5)
    next
  }

  /** CRC16-CCITT update: polynomial x^16 + x^12 + x^5 + 1 (0x1021) */
  def crc16Update(crc: Bits, bit: Bool): Bits = {
    val inv = crc(15) ^ bit
    val next = Bits(16 bits)
    next(0) := inv
    next(1) := crc(0)
    next(2) := crc(1)
    next(3) := crc(2)
    next(4) := crc(3)
    next(5) := crc(4) ^ inv
    next(6) := crc(5)
    next(7) := crc(6)
    next(8) := crc(7)
    next(9) := crc(8)
    next(10) := crc(9)
    next(11) := crc(10)
    next(12) := crc(11) ^ inv
    next(13) := crc(12)
    next(14) := crc(13)
    next(15) := crc(14)
    next
  }

  // ========================================================================
  // Clock Generator
  // ========================================================================

  val clkDivider = Reg(UInt(10 bits)) init(clkDivInit)
  val clkCounter = Reg(UInt(10 bits)) init(0)
  val sdClkReg   = Reg(Bool()) init(False)
  val sdClkRise  = False
  val sdClkFall  = False

  when(clkCounter === clkDivider) {
    clkCounter := 0
    sdClkReg := !sdClkReg
    when(!sdClkReg) {
      sdClkRise := True
    } otherwise {
      sdClkFall := True
    }
  } otherwise {
    clkCounter := clkCounter + 1
  }

  io.sdClk := sdClkReg

  // ========================================================================
  // Configuration Registers
  // ========================================================================

  val openDrain   = Reg(Bool()) init(False)
  val busWidth4   = Reg(Bool()) init(False)
  val blockLength = Reg(UInt(16 bits)) init(512)

  // ========================================================================
  // Data FIFO (128 x 32-bit = 512 bytes)
  // ========================================================================
  // Centralized push/pop signals to avoid multiple drivers.
  // Sources: CPU write (addr 5), data RX FSM push, CPU read (addr 5 pop),
  // data TX FSM pop, abort/startRead reset.

  val fifoMem       = Mem(Bits(32 bits), 128)
  val fifoWrPtr     = Reg(UInt(7 bits)) init(0)
  val fifoRdPtr     = Reg(UInt(7 bits)) init(0)
  val fifoOccupancy = Reg(UInt(8 bits)) init(0)

  // Push request signals (exactly one source active per cycle)
  val fifoPushValid = False
  val fifoPushData  = B(0, 32 bits)

  // Pop request signals
  val fifoPopValid = False

  // Reset request (abort or startRead)
  val fifoReset = False

  // FIFO occupancy and pointer update (single driver)
  when(fifoReset) {
    fifoWrPtr := 0
    fifoRdPtr := 0
    fifoOccupancy := 0
  } elsewhen(fifoPushValid && fifoPopValid) {
    fifoMem.write(fifoWrPtr, fifoPushData)
    fifoWrPtr := fifoWrPtr + 1
    fifoRdPtr := fifoRdPtr + 1
    // occupancy unchanged
  } elsewhen(fifoPushValid) {
    fifoMem.write(fifoWrPtr, fifoPushData)
    fifoWrPtr := fifoWrPtr + 1
    fifoOccupancy := fifoOccupancy + 1
  } elsewhen(fifoPopValid) {
    fifoRdPtr := fifoRdPtr + 1
    fifoOccupancy := fifoOccupancy - 1
  }

  val fifoRdData = fifoMem.readSync(fifoRdPtr)

  val fifoEmpty = fifoOccupancy === 0
  val fifoFull  = fifoOccupancy === 128

  // ========================================================================
  // CMD State Machine
  // ========================================================================

  object CmdState extends SpinalEnum {
    val IDLE, SENDING, WAIT_RSP, RECEIVING, DONE = newElement()
  }

  val cmdState         = Reg(CmdState()) init(CmdState.IDLE)
  val cmdArgument      = Reg(Bits(32 bits)) init(0)
  val cmdIndex         = Reg(UInt(6 bits)) init(0)
  val cmdExpectResp    = Reg(Bool()) init(False)
  val cmdLongResp      = Reg(Bool()) init(False)
  val cmdBusy          = Reg(Bool()) init(False)
  val cmdResponseValid = Reg(Bool()) init(False)
  val cmdCrcError      = Reg(Bool()) init(False)
  val cmdTimeout       = Reg(Bool()) init(False)

  // CMD shift register: 48 bits for normal, 136 bits for R2
  val cmdShiftReg = Reg(Bits(136 bits)) init(0)
  val cmdBitCount = Reg(UInt(8 bits)) init(0)
  val cmdCrc7     = Reg(Bits(7 bits)) init(0)

  // Response storage: 4 x 32-bit
  val cmdResponse = Vec(Reg(Bits(32 bits)) init(0), 4)

  // CMD output
  val cmdOutBit = Reg(Bool()) init(True) // idle high
  val cmdOutEn  = Reg(Bool()) init(False)

  // Timeout counter for response wait
  val cmdTimeoutCounter = Reg(UInt(7 bits)) init(0)

  when(sdClkFall) {
    switch(cmdState) {
      is(CmdState.IDLE) {
        cmdOutBit := True
        cmdOutEn  := False
      }

      is(CmdState.SENDING) {
        cmdOutEn := True
        cmdOutBit := cmdShiftReg(47)

        // CRC7 computed over content: direction + index + argument = 39 bits
        // (bitCount 1 through 39)
        when(cmdBitCount >= 1 && cmdBitCount <= 39) {
          cmdCrc7 := crc7Update(cmdCrc7, cmdShiftReg(47))
        }

        // At bitCount 39 (last content bit being sent), compute final CRC
        // and load CRC7[6:0]+stop into shift register top for bits 40-47.
        // After this cycle's output, the shift register will have:
        //   [47:41] = CRC7[6:0], [40] = stop(1)
        when(cmdBitCount === 39) {
          val finalCrc = crc7Update(cmdCrc7, cmdShiftReg(47))
          cmdShiftReg(47 downto 41) := finalCrc
          cmdShiftReg(40) := True // stop bit
        } otherwise {
          cmdShiftReg := (cmdShiftReg |<< 1)
        }

        cmdBitCount := cmdBitCount + 1
        when(cmdBitCount === 47) {
          // Let the stop bit be output this cycle (cmdOutEn stays True from above).
          // WAIT_RSP / DONE state will disable output on the next sdClkFall.
          when(cmdExpectResp) {
            cmdState := CmdState.WAIT_RSP
            cmdBitCount := 0
            cmdTimeoutCounter := 0
          } otherwise {
            cmdState := CmdState.DONE
          }
        }
      }

      is(CmdState.WAIT_RSP) {
        cmdOutEn := False
        cmdOutBit := True
      }

      is(CmdState.RECEIVING) {
        cmdOutEn := False
        cmdOutBit := True
      }

      is(CmdState.DONE) {
        cmdOutEn := False
        cmdOutBit := True
      }
    }
  }

  // Sample CMD input on rising edge
  when(sdClkRise) {
    switch(cmdState) {
      is(CmdState.WAIT_RSP) {
        when(!io.sdCmd.read) {
          cmdState := CmdState.RECEIVING
          cmdBitCount := 1
          cmdCrc7 := 0
          cmdShiftReg := 0
        } otherwise {
          cmdTimeoutCounter := cmdTimeoutCounter + 1
          when(cmdTimeoutCounter === 64) {
            cmdTimeout := True
            cmdState := CmdState.DONE
          }
        }
      }

      is(CmdState.RECEIVING) {
        val totalBits = Mux(cmdLongResp, U(136, 8 bits), U(48, 8 bits))

        cmdShiftReg := (cmdShiftReg |<< 1)
        cmdShiftReg(0) := io.sdCmd.read

        // Feed content + CRC bits through CRC engine for self-check (residue = 0)
        // Normal: bits 1..46 (39 content + 7 CRC), Long: bits 1..134 (127 content + 7 CRC)
        val crcEnd = Mux(cmdLongResp, U(134, 8 bits), U(46, 8 bits))
        when(cmdBitCount >= 1 && cmdBitCount <= crcEnd) {
          cmdCrc7 := crc7Update(cmdCrc7, io.sdCmd.read)
        }

        cmdBitCount := cmdBitCount + 1
        when(cmdBitCount === totalBits - 1) {
          cmdState := CmdState.DONE
        }
      }

      is(CmdState.IDLE) { /* nothing */ }
      is(CmdState.SENDING) { /* nothing */ }
      is(CmdState.DONE) { /* nothing */ }
    }
  }

  // DONE state: extract response and return to IDLE
  when(cmdState === CmdState.DONE) {
    cmdBusy := False
    when(cmdExpectResp && !cmdTimeout) {
      cmdResponseValid := True
      when(cmdCrc7 =/= 0) {
        cmdCrcError := True
      }
      when(cmdLongResp) {
        cmdResponse(0) := cmdShiftReg(31 downto 0)
        cmdResponse(1) := cmdShiftReg(63 downto 32)
        cmdResponse(2) := cmdShiftReg(95 downto 64)
        cmdResponse(3) := cmdShiftReg(127 downto 96)
      } otherwise {
        cmdResponse(0) := cmdShiftReg(39 downto 8)
        cmdResponse(1) := cmdShiftReg(47 downto 40).resized
      }
    }
    cmdState := CmdState.IDLE
  }

  // CMD pin control
  io.sdCmd.write := cmdOutBit
  when(openDrain) {
    io.sdCmd.writeEnable := cmdOutEn && !cmdOutBit
  } otherwise {
    io.sdCmd.writeEnable := cmdOutEn
  }

  // ========================================================================
  // Data State Machine
  // ========================================================================

  object DataState extends SpinalEnum {
    val IDLE, WAIT_START, RECEIVING, RECV_CRC,
        SEND_START, SENDING, SEND_CRC, SEND_STOP,
        WAIT_CRC_STATUS, WAIT_BUSY, DONE = newElement()
  }

  val dataState    = Reg(DataState()) init(DataState.IDLE)
  val dataBusy     = Reg(Bool()) init(False)
  val dataCrcError = Reg(Bool()) init(False)
  val dataTimeout  = Reg(Bool()) init(False)
  val dataCrcFlags = Reg(Bits(4 bits)) init(0)

  // Per-line CRC16 (4 instances)
  val dataCrc16 = Vec(Reg(Bits(16 bits)) init(0), 4)

  // Counters
  val dataBitCount   = Reg(UInt(16 bits)) init(0)
  val dataWordAcc    = Reg(Bits(32 bits)) init(0)
  val dataBitsInWord = Reg(UInt(6 bits)) init(0)
  val dataTimeoutCnt = Reg(UInt(20 bits)) init(0)

  // DAT output registers
  val datOutBits = Reg(Bits(4 bits)) init(B"1111")
  val datOutEn   = Reg(Bits(4 bits)) init(B"0000")

  // Shift register for TX data output
  val datTxShift    = Reg(Bits(32 bits)) init(0)
  val datTxBitsLeft = Reg(UInt(6 bits)) init(0)

  // CRC bit counter
  val datCrcBitCnt = Reg(UInt(5 bits)) init(0)

  // CRC status (write path)
  val datCrcStatus    = Reg(Bits(3 bits)) init(0)
  val datCrcStatusCnt = Reg(UInt(2 bits)) init(0)

  // Data read: sample on rising edge
  when(sdClkRise) {
    switch(dataState) {
      is(DataState.WAIT_START) {
        val startDetected = Mux(busWidth4, io.sdDat.read === B"0000", !io.sdDat.read(0))
        when(startDetected) {
          dataState := DataState.RECEIVING
          dataBitCount := 0
          dataWordAcc := 0
          dataBitsInWord := 0
          for (i <- 0 until 4) { dataCrc16(i) := 0 }
        } otherwise {
          dataTimeoutCnt := dataTimeoutCnt + 1
          when(dataTimeoutCnt === U(0xFFFFF, 20 bits)) {
            dataTimeout := True
            dataState := DataState.DONE
          }
        }
      }

      is(DataState.RECEIVING) {
        when(busWidth4) {
          for (i <- 0 until 4) {
            dataCrc16(i) := crc16Update(dataCrc16(i), io.sdDat.read(i))
          }
          dataWordAcc := (dataWordAcc |<< 4) | io.sdDat.read.resized
          dataBitsInWord := dataBitsInWord + 4
          when(dataBitsInWord === 28) {
            fifoPushValid := True
            fifoPushData := (dataWordAcc |<< 4) | io.sdDat.read.resized
            dataBitsInWord := 0
            dataWordAcc := 0
          }
          dataBitCount := dataBitCount + 4
          when(dataBitCount === ((blockLength << 3) - 4).resized) {
            dataState := DataState.RECV_CRC
            datCrcBitCnt := 0
          }
        } otherwise {
          dataCrc16(0) := crc16Update(dataCrc16(0), io.sdDat.read(0))
          dataWordAcc := (dataWordAcc |<< 1) | io.sdDat.read(0).asBits.resized
          dataBitsInWord := dataBitsInWord + 1
          when(dataBitsInWord === 31) {
            fifoPushValid := True
            fifoPushData := (dataWordAcc |<< 1) | io.sdDat.read(0).asBits.resized
            dataBitsInWord := 0
            dataWordAcc := 0
          }
          dataBitCount := dataBitCount + 1
          when(dataBitCount === ((blockLength << 3) - 1).resized) {
            dataState := DataState.RECV_CRC
            datCrcBitCnt := 0
          }
        }
      }

      is(DataState.RECV_CRC) {
        when(busWidth4) {
          for (i <- 0 until 4) {
            dataCrc16(i) := crc16Update(dataCrc16(i), io.sdDat.read(i))
          }
        } otherwise {
          dataCrc16(0) := crc16Update(dataCrc16(0), io.sdDat.read(0))
        }
        datCrcBitCnt := datCrcBitCnt + 1
        when(datCrcBitCnt === 15) {
          dataState := DataState.DONE
          when(busWidth4) {
            for (i <- 0 until 4) {
              when(crc16Update(dataCrc16(i), io.sdDat.read(i)) =/= 0) {
                dataCrcError := True
                dataCrcFlags(i) := True
              }
            }
          } otherwise {
            when(crc16Update(dataCrc16(0), io.sdDat.read(0)) =/= 0) {
              dataCrcError := True
              dataCrcFlags(0) := True
            }
          }
        }
      }

      is(DataState.WAIT_CRC_STATUS) {
        // Wait for start bit (DAT0 low) before sampling CRC status.
        // Card needs Ncrc SD clocks after end bit before driving status.
        when(datCrcStatusCnt === 0 && io.sdDat.read(0)) {
          // Still waiting for start bit — don't advance counter
          dataTimeoutCnt := dataTimeoutCnt + 1
          when(dataTimeoutCnt === U(0xFFFFF, 20 bits)) {
            dataTimeout := True
            dataState := DataState.DONE
          }
        } otherwise {
          datCrcStatus := (datCrcStatus |<< 1) | io.sdDat.read(0).asBits.resized
          datCrcStatusCnt := datCrcStatusCnt + 1
          when(datCrcStatusCnt === 2) {
            val status = (datCrcStatus |<< 1) | io.sdDat.read(0).asBits.resized
            when(status =/= B"010") {
              dataCrcError := True
            }
            dataState := DataState.WAIT_BUSY
          }
        }
      }

      is(DataState.WAIT_BUSY) {
        when(io.sdDat.read(0)) {
          dataState := DataState.DONE
        }
      }

      is(DataState.IDLE) { /* nothing */ }
      is(DataState.SEND_START) { /* nothing */ }
      is(DataState.SENDING) { /* nothing */ }
      is(DataState.SEND_CRC) { /* nothing */ }
      is(DataState.SEND_STOP) { /* nothing */ }
      is(DataState.DONE) { /* nothing */ }
    }
  }

  // Data write: drive on falling edge
  when(sdClkFall) {
    switch(dataState) {
      is(DataState.SEND_START) {
        when(busWidth4) {
          datOutBits := B"0000"
          datOutEn := B"1111"
        } otherwise {
          datOutBits := B"0000"
          datOutEn := B"0001"
        }
        dataState := DataState.SENDING
        dataBitCount := 0
        dataBitsInWord := 0
        for (i <- 0 until 4) { dataCrc16(i) := 0 }
        // Load first word from FIFO (readSync result available this cycle
        // because fifoRdPtr was set when startWrite was issued)
        datTxShift := fifoRdData
        fifoPopValid := True
        datTxBitsLeft := 32
      }

      is(DataState.SENDING) {
        when(busWidth4) {
          datOutBits := datTxShift(31 downto 28)
          datOutEn := B"1111"
          for (i <- 0 until 4) {
            dataCrc16(i) := crc16Update(dataCrc16(i), datTxShift(28 + i))
          }
          datTxShift := (datTxShift |<< 4)
          datTxBitsLeft := datTxBitsLeft - 4
          dataBitCount := dataBitCount + 4

          when(datTxBitsLeft === 4) {
            when(dataBitCount < ((blockLength << 3) - 4).resized) {
              datTxShift := fifoRdData
              fifoPopValid := True
              datTxBitsLeft := 32
            }
          }

          when(dataBitCount === ((blockLength << 3) - 4).resized) {
            dataState := DataState.SEND_CRC
            datCrcBitCnt := 0
          }
        } otherwise {
          datOutBits(0) := datTxShift(31)
          datOutEn := B"0001"
          dataCrc16(0) := crc16Update(dataCrc16(0), datTxShift(31))
          datTxShift := (datTxShift |<< 1)
          datTxBitsLeft := datTxBitsLeft - 1
          dataBitCount := dataBitCount + 1

          when(datTxBitsLeft === 1) {
            when(dataBitCount < ((blockLength << 3) - 1).resized) {
              datTxShift := fifoRdData
              fifoPopValid := True
              datTxBitsLeft := 32
            }
          }

          when(dataBitCount === ((blockLength << 3) - 1).resized) {
            dataState := DataState.SEND_CRC
            datCrcBitCnt := 0
          }
        }
      }

      is(DataState.SEND_CRC) {
        when(busWidth4) {
          for (i <- 0 until 4) {
            datOutBits(i) := dataCrc16(i)(15)
            dataCrc16(i) := (dataCrc16(i) |<< 1)
          }
          datOutEn := B"1111"
        } otherwise {
          datOutBits(0) := dataCrc16(0)(15)
          dataCrc16(0) := (dataCrc16(0) |<< 1)
          datOutEn := B"0001"
        }
        datCrcBitCnt := datCrcBitCnt + 1
        when(datCrcBitCnt === 15) {
          dataState := DataState.SEND_STOP
        }
      }

      is(DataState.SEND_STOP) {
        when(busWidth4) {
          datOutBits := B"1111"
          datOutEn := B"1111"
        } otherwise {
          datOutBits(0) := True
          datOutEn := B"0001"
        }
        dataState := DataState.WAIT_CRC_STATUS
        datCrcStatusCnt := 0
        datCrcStatus := 0
      }

      is(DataState.WAIT_CRC_STATUS) {
        datOutEn := B"0000"
        datOutBits := B"1111"
      }

      is(DataState.WAIT_BUSY) {
        datOutEn := B"0000"
        datOutBits := B"1111"
      }

      is(DataState.IDLE) {
        datOutBits := B"1111"
        datOutEn := B"0000"
      }
      is(DataState.WAIT_START) { /* nothing */ }
      is(DataState.RECEIVING) { /* nothing */ }
      is(DataState.RECV_CRC) { /* nothing */ }
      is(DataState.DONE) {
        datOutBits := B"1111"
        datOutEn := B"0000"
      }
    }
  }

  // DONE state processing
  when(dataState === DataState.DONE) {
    dataBusy := False
    dataState := DataState.IDLE
    datOutEn := B"0000"
    datOutBits := B"1111"
  }

  // DAT pin outputs
  io.sdDat.write := datOutBits
  io.sdDat.writeEnable := datOutEn

  // ========================================================================
  // Card detect (active low, synchronized)
  // ========================================================================

  val cardPresent = !BufferCC(io.sdCd, True)

  // ========================================================================
  // Interrupt: pulse when cmdBusy or dataBusy falls
  // ========================================================================

  val cmdBusyDly  = RegNext(cmdBusy) init(False)
  val dataBusyDly = RegNext(dataBusy) init(False)
  io.interrupt := (!cmdBusy && cmdBusyDly) || (!dataBusy && dataBusyDly)

  // ========================================================================
  // Register Read Mux
  // ========================================================================

  io.rdData := 0
  switch(io.addr) {
    is(0) {
      // Status register
      io.rdData(0) := cmdBusy
      io.rdData(1) := cmdResponseValid
      io.rdData(2) := cmdCrcError
      io.rdData(3) := cmdTimeout
      io.rdData(4) := dataBusy
      io.rdData(5) := dataCrcError
      io.rdData(6) := dataTimeout
      io.rdData(7) := cardPresent
      io.rdData(8) := fifoEmpty   // txFifoEmpty
      io.rdData(9) := fifoFull    // txFifoFull
      io.rdData(10) := fifoEmpty  // rxFifoEmpty
      io.rdData(11) := !fifoEmpty // rxFifoHasData
    }
    is(1) {
      io.rdData := cmdResponse(0)
    }
    is(2) {
      io.rdData := cmdResponse(1)
    }
    is(3) {
      io.rdData := cmdResponse(2)
    }
    is(4) {
      io.rdData := cmdResponse(3)
    }
    is(5) {
      // Data FIFO read (pop on read)
      io.rdData := fifoRdData
      when(io.rd) {
        fifoPopValid := True
      }
    }
    is(6) {
      // RX FIFO occupancy
      io.rdData(15 downto 0) := fifoOccupancy.asBits.resized
    }
    is(7) {
      // CRC error flags
      io.rdData(3 downto 0) := dataCrcFlags
    }
    is(8) {
      io.rdData(0) := cardPresent
      io.rdData(1) := busWidth4
    }
  }

  // ========================================================================
  // Register Write Handling
  // ========================================================================

  when(io.wr) {
    switch(io.addr) {
      is(0) {
        // Control register
        when(io.wrData(0)) {
          // sendCmd
          cmdBusy := True
          cmdResponseValid := False
          cmdCrcError := False
          cmdTimeout := False
          cmdCrc7 := 0
          cmdBitCount := 0

          cmdShiftReg := 0
          cmdShiftReg(47) := False          // start bit
          cmdShiftReg(46) := True           // direction: host->card
          cmdShiftReg(45 downto 40) := cmdIndex.asBits
          cmdShiftReg(39 downto 8) := cmdArgument
          cmdShiftReg(0) := True            // stop bit placeholder

          cmdState := CmdState.SENDING
        }
        when(io.wrData(1)) {
          // abort
          cmdState := CmdState.IDLE
          cmdBusy := False
          dataState := DataState.IDLE
          dataBusy := False
          fifoReset := True
          datOutEn := B"0000"
          cmdOutEn := False
        }
        when(io.wrData(2)) {
          openDrain := True
        } otherwise {
          openDrain := False
        }
      }
      is(1) {
        cmdArgument := io.wrData
      }
      is(2) {
        cmdIndex := io.wrData(5 downto 0).asUInt
        cmdExpectResp := io.wrData(6)
        cmdLongResp := io.wrData(7)
      }
      is(5) {
        // Data FIFO write (push)
        when(!fifoFull) {
          fifoPushValid := True
          fifoPushData := io.wrData
        }
      }
      is(6) {
        clkDivider := io.wrData(9 downto 0).asUInt
      }
      is(7) {
        when(io.wrData(0)) {
          // startRead
          dataBusy := True
          dataCrcError := False
          dataTimeout := False
          dataCrcFlags := 0
          dataTimeoutCnt := 0
          dataState := DataState.WAIT_START
          fifoReset := True
        }
        when(io.wrData(1)) {
          // startWrite
          dataBusy := True
          dataCrcError := False
          dataTimeout := False
          dataCrcFlags := 0
          dataTimeoutCnt := 0
          dataState := DataState.SEND_START
        }
      }
      is(8) {
        busWidth4 := io.wrData(0)
      }
      is(9) {
        blockLength := io.wrData(15 downto 0).asUInt
      }
    }
  }
}
