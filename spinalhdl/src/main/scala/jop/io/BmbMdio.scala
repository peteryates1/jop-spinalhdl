package jop.io

import spinal.core._
import spinal.lib._

/**
 * MDIO Management Controller — IEEE 802.3 clause 22
 *
 * Generates MDIO frames for PHY register access via MDC/MDIO pins.
 * Also provides PHY hardware reset control and interrupt enable registers.
 *
 * Address map (sub-addresses relative to assigned I/O device slot):
 *   0x0 read  — bit 0 = busy
 *   0x0 write — bit 0 = go, bit 1 = write(1)/read(0)
 *   0x1 read  — MDIO read data [15:0]
 *   0x1 write — MDIO write data [15:0]
 *   0x2 write — [4:0] = reg addr, [9:5] = PHY addr
 *   0x3 write — bit 0 = PHY reset (active-low output, active-high register)
 *   0x4 read  — interrupt pending: bit 0=ETH RX, bit 1=ETH TX, bit 2=MDIO done
 *   0x4 write — interrupt enable mask: bit 0=ETH RX, bit 1=ETH TX, bit 2=MDIO done
 *
 * MDIO frame format (clause 22):
 *   Preamble(32x'1') + ST('01') + OP('10'=read/'01'=write) +
 *   PHYAD(5) + REGAD(5) + TA(2) + DATA(16)
 *
 * @param clkDivider MDC clock divider (MDC = sysClk / (2 * clkDivider))
 */
case class BmbMdio(clkDivider: Int = 40) extends Component {
  val io = new Bundle {
    val addr   = in UInt(4 bits)
    val rd     = in Bool()
    val wr     = in Bool()
    val wrData = in Bits(32 bits)
    val rdData = out Bits(32 bits)

    // MDIO pins
    val mdc      = out Bool()
    val mdioOut  = out Bool()
    val mdioOe   = out Bool()
    val mdioIn   = in Bool()

    // PHY reset (directly active-low output)
    val phyReset = out Bool()

    // Interrupt inputs from BmbEth (active-high pulses)
    val ethRxInt = in Bool()
    val ethTxInt = in Bool()

    // Combined interrupt output to BmbSys
    val interrupt = out Bool()
  }

  // ========================================================================
  // Registers
  // ========================================================================

  val phyAddr  = Reg(UInt(5 bits)) init(0)
  val regAddr  = Reg(UInt(5 bits)) init(0)
  val wrData   = Reg(Bits(16 bits)) init(0)
  val rdData   = Reg(Bits(16 bits)) init(0)
  val isWrite  = Reg(Bool()) init(False)
  val phyRstReg = Reg(Bool()) init(True)  // Start with PHY in reset

  // Interrupt enable and pending
  val intEnableRx   = Reg(Bool()) init(False)
  val intEnableTx   = Reg(Bool()) init(False)
  val intEnableMdio = Reg(Bool()) init(False)

  // MDIO done interrupt: pulse on falling edge of busy
  val mdioDoneReg   = Reg(Bool()) init(False)

  // PHY reset output (active-low)
  io.phyReset := ~phyRstReg

  // ========================================================================
  // MDC Clock Divider
  // ========================================================================

  val clkCnt = Reg(UInt(log2Up(clkDivider) bits)) init(0)
  val mdcReg = Reg(Bool()) init(False)
  val mdcRise = False  // Pulse on MDC rising edge (work happens here)

  when(clkCnt === (clkDivider - 1)) {
    clkCnt := 0
    mdcReg := ~mdcReg
    mdcRise := ~mdcReg  // Rising edge when transitioning 0->1
  } otherwise {
    clkCnt := clkCnt + 1
  }
  io.mdc := mdcReg

  // ========================================================================
  // MDIO State Machine
  // ========================================================================

  object MdioState extends SpinalEnum {
    val IDLE, PREAMBLE, START, OPCODE, PHY_ADDR, REG_ADDR, TURNAROUND, DATA = newElement()
  }

  val state   = Reg(MdioState()) init(MdioState.IDLE)
  val bitCnt  = Reg(UInt(6 bits)) init(0)
  val busy    = state =/= MdioState.IDLE
  val shiftReg = Reg(Bits(16 bits)) init(0)

  // Default MDIO output
  io.mdioOut := True
  io.mdioOe  := False

  when(mdcRise) {
    switch(state) {
      is(MdioState.IDLE) {
        // Do nothing, waiting for go
      }

      is(MdioState.PREAMBLE) {
        io.mdioOut := True
        io.mdioOe  := True
        bitCnt := bitCnt + 1
        when(bitCnt === 31) {
          state  := MdioState.START
          bitCnt := 0
        }
      }

      is(MdioState.START) {
        io.mdioOe := True
        when(bitCnt === 0) {
          io.mdioOut := False  // ST bit 0 = '0'
        } otherwise {
          io.mdioOut := True   // ST bit 1 = '1'
        }
        bitCnt := bitCnt + 1
        when(bitCnt === 1) {
          state  := MdioState.OPCODE
          bitCnt := 0
        }
      }

      is(MdioState.OPCODE) {
        io.mdioOe := True
        when(bitCnt === 0) {
          // OP[1]: read='1', write='0'
          io.mdioOut := ~isWrite
        } otherwise {
          // OP[0]: read='0', write='1'
          io.mdioOut := isWrite
        }
        bitCnt := bitCnt + 1
        when(bitCnt === 1) {
          state  := MdioState.PHY_ADDR
          bitCnt := 0
        }
      }

      is(MdioState.PHY_ADDR) {
        io.mdioOe  := True
        io.mdioOut := phyAddr(4 - bitCnt.resized)
        bitCnt := bitCnt + 1
        when(bitCnt === 4) {
          state  := MdioState.REG_ADDR
          bitCnt := 0
        }
      }

      is(MdioState.REG_ADDR) {
        io.mdioOe  := True
        io.mdioOut := regAddr(4 - bitCnt.resized)
        bitCnt := bitCnt + 1
        when(bitCnt === 4) {
          state  := MdioState.TURNAROUND
          bitCnt := 0
        }
      }

      is(MdioState.TURNAROUND) {
        when(isWrite) {
          // Write: drive TA = '10'
          io.mdioOe := True
          when(bitCnt === 0) {
            io.mdioOut := True
          } otherwise {
            io.mdioOut := False
          }
        } otherwise {
          // Read: release bus (tristate), PHY drives TA
          io.mdioOe := False
        }
        bitCnt := bitCnt + 1
        when(bitCnt === 1) {
          state  := MdioState.DATA
          bitCnt := 0
          when(isWrite) {
            shiftReg := wrData
          }
        }
      }

      is(MdioState.DATA) {
        when(isWrite) {
          io.mdioOe  := True
          io.mdioOut := shiftReg(15)
          shiftReg   := shiftReg |<< 1
        } otherwise {
          io.mdioOe := False
          shiftReg  := shiftReg(14 downto 0) ## io.mdioIn
        }
        bitCnt := bitCnt + 1
        when(bitCnt === 15) {
          when(!isWrite) {
            rdData := shiftReg(14 downto 0) ## io.mdioIn
          }
          state  := MdioState.IDLE
          bitCnt := 0
          mdioDoneReg := True
        }
      }
    }
  }

  // When not on MDC edge, hold outputs based on current state
  when(!mdcRise && busy) {
    switch(state) {
      is(MdioState.PREAMBLE) {
        io.mdioOe  := True
        io.mdioOut := True
      }
      is(MdioState.START) {
        io.mdioOe := True
        when(bitCnt === 0) {
          io.mdioOut := False
        } otherwise {
          io.mdioOut := True
        }
      }
      is(MdioState.OPCODE) {
        io.mdioOe := True
        when(bitCnt === 0) {
          io.mdioOut := ~isWrite
        } otherwise {
          io.mdioOut := isWrite
        }
      }
      is(MdioState.PHY_ADDR) {
        io.mdioOe  := True
        io.mdioOut := phyAddr(4 - bitCnt.resized)
      }
      is(MdioState.REG_ADDR) {
        io.mdioOe  := True
        io.mdioOut := regAddr(4 - bitCnt.resized)
      }
      is(MdioState.TURNAROUND) {
        when(isWrite) {
          io.mdioOe := True
          when(bitCnt === 0) {
            io.mdioOut := True
          } otherwise {
            io.mdioOut := False
          }
        } otherwise {
          io.mdioOe := False
        }
      }
      is(MdioState.DATA) {
        when(isWrite) {
          io.mdioOe  := True
          io.mdioOut := shiftReg(15)
        } otherwise {
          io.mdioOe := False
        }
      }
      default {}
    }
  }

  // ========================================================================
  // Interrupt Logic
  // ========================================================================

  // Capture interrupt sources (edge detect on ETH, level on MDIO done)
  val ethRxIntPending = Reg(Bool()) init(False)
  val ethTxIntPending = Reg(Bool()) init(False)
  val mdioIntPending  = Reg(Bool()) init(False)

  // Set on source pulse, clear on interrupt pending register read
  when(io.ethRxInt) { ethRxIntPending := True }
  when(io.ethTxInt) { ethTxIntPending := True }
  when(mdioDoneReg) { mdioIntPending := True; mdioDoneReg := False }

  val intPending = B(0, 29 bits) ## mdioIntPending ## ethTxIntPending ## ethRxIntPending
  val intEnabled = (ethRxIntPending && intEnableRx) ||
                   (ethTxIntPending && intEnableTx) ||
                   (mdioIntPending  && intEnableMdio)

  // Generate single-cycle interrupt pulse on rising edge of intEnabled
  val intEnabledDly = RegNext(intEnabled) init(False)
  io.interrupt := intEnabled && !intEnabledDly

  // ========================================================================
  // Register Read/Write
  // ========================================================================

  io.rdData := 0
  switch(io.addr) {
    is(0) {
      io.rdData(0) := busy
    }
    is(1) {
      io.rdData(15 downto 0) := rdData
    }
    is(4) {
      io.rdData := intPending
      // Clear pending on read
      when(io.rd) {
        ethRxIntPending := False
        ethTxIntPending := False
        mdioIntPending  := False
      }
    }
  }

  when(io.wr) {
    switch(io.addr) {
      is(0) {
        // Command: bit 0 = go, bit 1 = write(1)/read(0)
        when(io.wrData(0) && !busy) {
          isWrite := io.wrData(1)
          state   := MdioState.PREAMBLE
          bitCnt  := 0
        }
      }
      is(1) {
        wrData := io.wrData(15 downto 0)
      }
      is(2) {
        regAddr := io.wrData(4 downto 0).asUInt
        phyAddr := io.wrData(9 downto 5).asUInt
      }
      is(3) {
        phyRstReg := io.wrData(0)
      }
      is(4) {
        intEnableRx   := io.wrData(0)
        intEnableTx   := io.wrData(1)
        intEnableMdio := io.wrData(2)
      }
    }
  }
}
