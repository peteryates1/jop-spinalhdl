package jop.io

import spinal.core._

/**
 * Sync input from a core to CmpSync (lock request).
 */
case class SyncIn() extends Bundle {
  val req    = Bool()    // Lock request (held high while lock needed)
  val s_in   = Bool()    // Boot synchronization signal
  val gcHalt = Bool()    // GC halt request (halts all OTHER cores)
}

/**
 * Sync output from CmpSync to a core (lock status).
 */
case class SyncOut() extends Bundle {
  val halted = Bool()  // Core is halted (waiting for lock)
  val s_out  = Bool()  // Boot synchronization broadcast
}

/**
 * Global lock synchronization for CMP (Chip Multi-Processor).
 *
 * Direct translation of VHDL cmpsync.vhd by Christof Pitter.
 * Provides mutual exclusion: when any core holds the lock, all other
 * cores are halted. Uses round-robin fairness for lock arbitration.
 *
 * Protocol:
 *   - Core sets req=1 to request lock (via BmbSys IO_LOCK write)
 *   - CmpSync grants to one core (halted=0), halts all others (halted=1)
 *   - Owner clears req=0 to release (via BmbSys IO_UNLOCK write)
 *   - Next requester gets lock immediately (no idle gap)
 *
 * @param cpuCnt Number of CPU cores
 */
case class CmpSync(cpuCnt: Int) extends Component {
  require(cpuCnt >= 2, "CmpSync requires at least 2 cores")

  val io = new Bundle {
    val syncIn  = in Vec(SyncIn(), cpuCnt)
    val syncOut = out Vec(SyncOut(), cpuCnt)
  }

  object State extends SpinalEnum {
    val IDLE, LOCKED = newElement()
  }

  val state    = Reg(State()) init(State.IDLE)
  val lockedId = Reg(UInt(log2Up(cpuCnt) bits)) init(0)
  val rrIndex  = Reg(UInt(log2Up(cpuCnt) bits)) init(cpuCnt - 1)

  // Combinational next-state logic
  val nextState    = State()
  val nextLockedId = UInt(log2Up(cpuCnt) bits)
  val nextRrIndex  = UInt(log2Up(cpuCnt) bits)

  nextState    := state
  nextLockedId := lockedId
  nextRrIndex  := rrIndex

  /**
   * Round-robin arbiter: find requesting core with priority.
   *
   * VHDL uses two downto loops: first checks i <= rr_index, then i > rr_index.
   * The last assignment wins (in both VHDL and SpinalHDL when blocks).
   * In a downto loop, last match = smallest index.
   * Second loop overrides first, so priority is:
   *   1. Smallest i > rrIndex (if any)
   *   2. Smallest i <= rrIndex (otherwise)
   * This implements fair round-robin rotation.
   */
  def arbitrate(): Unit = {
    // First pass: i <= rrIndex (lower priority, will be overridden)
    for (i <- cpuCnt - 1 to 0 by -1) {
      when(U(i, log2Up(cpuCnt) bits) <= rrIndex && io.syncIn(i).req) {
        nextState    := State.LOCKED
        nextLockedId := U(i, log2Up(cpuCnt) bits)
        nextRrIndex  := U(i, log2Up(cpuCnt) bits)
      }
    }
    // Second pass: i > rrIndex (higher priority, overrides first pass)
    for (i <- cpuCnt - 1 to 0 by -1) {
      when(U(i, log2Up(cpuCnt) bits) > rrIndex && io.syncIn(i).req) {
        nextState    := State.LOCKED
        nextLockedId := U(i, log2Up(cpuCnt) bits)
        nextRrIndex  := U(i, log2Up(cpuCnt) bits)
      }
    }
  }

  switch(state) {
    is(State.IDLE) {
      nextLockedId := cpuCnt - 1  // No valid owner
      arbitrate()
    }
    is(State.LOCKED) {
      // Owner releases lock (req='0')
      when(!io.syncIn(lockedId).req) {
        nextState    := State.IDLE
        nextLockedId := cpuCnt - 1
        // Immediately check for new requesters (no idle gap)
        arbitrate()
      }
    }
  }

  // Register update
  state    := nextState
  lockedId := nextLockedId
  rrIndex  := nextRrIndex

  // Combinational output (based on next_state, matching VHDL)
  for (i <- 0 until cpuCnt) {
    // Boot synchronization: broadcast core 0's s_in to all
    io.syncOut(i).s_out := io.syncIn(0).s_in

    // GC halt: if any OTHER core has gcHalt set, this core is halted
    val gcHaltFromOthers = (0 until cpuCnt).filter(_ != i)
      .map(j => io.syncIn(j).gcHalt).reduce(_ || _)

    // Halted output: lock OR gcHalt from another core
    io.syncOut(i).halted := gcHaltFromOthers
    when(nextState === State.LOCKED) {
      io.syncOut(i).halted := (U(i, log2Up(cpuCnt) bits) =/= nextLockedId) || gcHaltFromOthers
    }
  }
}
