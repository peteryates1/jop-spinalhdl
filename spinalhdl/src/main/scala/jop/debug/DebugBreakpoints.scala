package jop.debug

import spinal.core._

/**
 * Per-core hardware breakpoint comparators.
 *
 * Supports both microcode PC and bytecode JPC breakpoints.
 * Each slot stores: enabled flag, type (0=PC, 1=JPC), address.
 *
 * @param numSlots   Number of breakpoint slots (from DebugConfig)
 * @param pcWidth    Microcode PC width
 * @param jpcWidth   Java PC width
 */
case class DebugBreakpoints(
  numSlots: Int,
  pcWidth: Int = 11,
  jpcWidth: Int = 11
) extends Component {
  require(numSlots > 0 && numSlots <= 8)

  val io = new Bundle {
    // Current core state
    val pc      = in UInt(pcWidth bits)
    val jpc     = in UInt((jpcWidth + 1) bits)
    val jfetch  = in Bool()   // Bytecode fetch strobe (for JPC breakpoints)
    val halted  = in Bool()   // Core is halted (suppress hits)

    // Breakpoint management
    val setValid   = in Bool()
    val setType    = in Bits(8 bits)    // 0x00=PC, 0x01=JPC
    val setAddr    = in UInt(32 bits)
    val setSlot    = out UInt(3 bits)   // Assigned slot on success
    val setOk      = out Bool()         // True if slot was available

    val clearValid = in Bool()
    val clearSlot  = in UInt(3 bits)

    // Query
    val queryValid = in Bool()
    val queryData  = out Vec(Bits(48 bits), numSlots)  // SLOT(8) ## TYPE(8) ## ADDR(32)
    val queryCount = out UInt(4 bits)                   // Number of active breakpoints

    // Per-slot enabled status (for query)
    val slotEnabled = out Vec(Bool(), numSlots)

    // Hit output
    val hit      = out Bool()
    val hitSlot  = out UInt(3 bits)
  }

  // Breakpoint storage
  val enabled = Vec(Reg(Bool()) init(False), numSlots)
  val bpType  = Vec(Reg(Bits(8 bits)) init(0), numSlots)   // 0x00=PC, 0x01=JPC
  val bpAddr  = Vec(Reg(UInt(32 bits)) init(0), numSlots)

  // Expose enabled status
  for (i <- 0 until numSlots) io.slotEnabled(i) := enabled(i)

  // ==========================================================================
  // Set Breakpoint
  // ==========================================================================

  // Find first free slot (last-assignment-wins: reverse loop so lowest index has priority)
  val freeSlot = UInt(3 bits)
  val freeFound = Bool()
  freeSlot := 0
  freeFound := False
  for (i <- (numSlots - 1) to 0 by -1) {
    when(!enabled(i)) {
      freeSlot := i
      freeFound := True
    }
  }

  io.setSlot := freeSlot
  io.setOk := False

  when(io.setValid) {
    when(freeFound) {
      enabled(freeSlot.resized) := True
      bpType(freeSlot.resized) := io.setType
      bpAddr(freeSlot.resized) := io.setAddr
      io.setOk := True
    }
  }

  // ==========================================================================
  // Clear Breakpoint
  // ==========================================================================

  when(io.clearValid) {
    when(io.clearSlot < numSlots) {
      enabled(io.clearSlot.resized) := False
    }
  }

  // ==========================================================================
  // Query
  // ==========================================================================

  // Count active breakpoints without combinatorial feedback
  io.queryCount := enabled.map(e => Mux(e, U(1, 4 bits), U(0, 4 bits))).reduce(_ + _)
  for (i <- 0 until numSlots) {
    io.queryData(i) := B(i, 8 bits) ## bpType(i) ## bpAddr(i).asBits
  }

  // ==========================================================================
  // Hit Detection
  // ==========================================================================

  io.hit := False
  io.hitSlot := 0

  when(!io.halted) {
    for (i <- 0 until numSlots) {
      when(enabled(i)) {
        when(bpType(i) === 0x00) {
          // PC breakpoint: compare against current microcode PC
          when(io.pc === bpAddr(i).resized) {
            io.hit := True
            io.hitSlot := i
          }
        }.otherwise {
          // JPC breakpoint: compare against current JPC when jfetch fires
          when(io.jfetch && io.jpc === bpAddr(i).resized) {
            io.hit := True
            io.hitSlot := i
          }
        }
      }
    }
  }
}
