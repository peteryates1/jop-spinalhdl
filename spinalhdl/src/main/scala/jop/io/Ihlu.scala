package jop.io

import spinal.core._
import spinal.core.sim._   // simPublic on the lock table; see below

/**
 * IHLU Configuration.
 *
 * @param cpuCnt        Number of CPU cores (>= 2)
 * @param lockSlots     Number of hardware lock slots (CAM entries)
 * @param reentrantBits Bits for reentrant counter (8 = max depth 255)
 */
case class IhluConfig(
  cpuCnt: Int = 4,
  lockSlots: Int = 32,
  reentrantBits: Int = 8
) {
  require(cpuCnt >= 2, "IHLU requires at least 2 cores")
  require(lockSlots >= 1, "IHLU requires at least 1 lock slot")
  require(reentrantBits >= 1, "IHLU requires at least 1 reentrant bit")

  val cpuIdWidth: Int = log2Up(cpuCnt)
  val slotIdWidth: Int = log2Up(lockSlots)
  val queueDepth: Int = cpuCnt  // Each lock can have at most cpuCnt waiters
  val queuePtrWidth: Int = log2Up(queueDepth + 1) // +1 for wrap-around (modular arithmetic)
  val queueRamSize: Int = lockSlots * cpuCnt
  val queueRamAddrWidth: Int = log2Up(queueRamSize)
}

/**
 * Integrated Hardware Lock Unit (IHLU) -- fine-grained per-object locking.
 *
 * Provides 32 (configurable) independent lock slots, each identified by a
 * 32-bit lock key (the Java object handle address). Cores can acquire and
 * release locks on individual objects without serialising all other cores.
 *
 * Direct SpinalHDL reimplementation of Torur Biskopsto Strom's VHDL ihlu.vhd
 * (2014), with the following enhancements:
 *   - GC halt integration with drain mechanism (lock owners exempt from gcHalt)
 *   - Same SyncIn/SyncOut interface as CmpSync for drop-in replacement
 *
 * Architecture:
 *   - 32 lock entries with fully-associative CAM lookup
 *   - Per-entry: valid, lockKey[31:0], owner[3:0], count[7:0], queueHead/Tail
 *   - FIFO wait queue per entry (block RAM, cpuCnt entries per slot)
 *   - 4-state machine: IDLE -> RAM_READ -> RAM_DELAY -> EXECUTE -> IDLE
 *   - Round-robin core servicing (one request per 4-cycle window)
 *
 * GC halt (drain mechanism):
 *   A core that owns ANY lock is exempt from gcHalt, allowing it to finish
 *   its critical section and release. Once all locks are released, the core
 *   is halted. This matches CmpSync's "lock owner never halted" rule but
 *   extended to multiple concurrent lock owners.
 *
 * @param config IHLU configuration
 */
case class Ihlu(config: IhluConfig) extends Component {
  val io = new Bundle {
    val syncIn  = in Vec(SyncIn(), config.cpuCnt)
    val syncOut = out Vec(SyncOut(), config.cpuCnt)
  }

  import config._

  // ========================================================================
  // State machine
  // ========================================================================

  object State extends SpinalEnum {
    val IDLE, RAM_READ, RAM_DELAY, EXECUTE = newElement()
  }

  val state = Reg(State()) init(State.IDLE)

  // Round-robin CPU index for request servicing
  val cpuPtr = Reg(UInt(cpuIdWidth bits)) init(0)

  // ========================================================================
  // Per-core request buffering (toggle-based handshake)
  // ========================================================================

  // Registered input buffers per core
  val dataReg    = Vec(Reg(Bits(32 bits)) init(0), cpuCnt)
  val opReg      = Vec(Reg(Bool()) init(False), cpuCnt)    // False=lock, True=unlock
  val registerIn = Vec(Reg(Bool()) init(False), cpuCnt)    // Core side toggle
  val registerOut = Vec(Reg(Bool()) init(False), cpuCnt)   // IHLU side toggle

  // Per-core "waiting in queue" flag
  val syncFlag = Vec(Reg(Bool()) init(False), cpuCnt)

  // Capture requests from cores (pulse-triggered, toggle handshake)
  for (i <- 0 until cpuCnt) {
    when(io.syncIn(i).reqPulse && (registerIn(i) === registerOut(i))) {
      // New request: capture data and toggle input register
      dataReg(i) := io.syncIn(i).data
      opReg(i) := io.syncIn(i).op
      registerIn(i) := !registerIn(i)
    }
  }

  // A core has a pending request when registerIn != registerOut
  def hasPending(i: Int): Bool = registerIn(i) =/= registerOut(i)

  // ========================================================================
  // Lock table: per-slot registers
  // ========================================================================

  // simPublic: IhluGcGrantTest reads the table directly to check WHO owns what
  // during a stop-the-world. The grant rule it tests is invisible from the
  // syncOut pins alone -- "core 3 was refused" and "core 3 has not asked yet"
  // look identical from outside.
  val entry      = Vec(Reg(Bits(32 bits)) init(0), lockSlots)       // Lock key
  val valid      = Vec(Reg(Bool()) init(False), lockSlots)          // Slot in use
  val owner      = Vec(Reg(UInt(cpuIdWidth bits)) init(0), lockSlots)
  // simPublic is per ELEMENT, not on the Vec.
  entry.foreach(_.simPublic()); valid.foreach(_.simPublic()); owner.foreach(_.simPublic())
  val count      = Vec(Reg(UInt(reentrantBits bits)) init(0), lockSlots)
  val queueHead  = Vec(Reg(UInt(queuePtrWidth bits)) init(0), lockSlots)
  val queueTail  = Vec(Reg(UInt(queuePtrWidth bits)) init(0), lockSlots)

  // ========================================================================
  // FIFO queue RAM (block RAM): lockSlots * cpuCnt entries of cpuIdWidth bits
  // ========================================================================

  val queueRam = Mem(UInt(cpuIdWidth bits), queueRamSize)

  // RAM read/write ports
  val ramRdAddr = UInt(queueRamAddrWidth bits)
  val ramWrAddr = UInt(queueRamAddrWidth bits)
  val ramWrData = UInt(cpuIdWidth bits)
  val ramWrEn   = Bool()
  val ramRdData = UInt(cpuIdWidth bits)

  ramRdAddr := U(0, queueRamAddrWidth bits)
  ramWrAddr := U(0, queueRamAddrWidth bits)
  ramWrData := U(0, cpuIdWidth bits)
  ramWrEn   := False

  // Synchronous read (registered output)
  ramRdData := queueRam.readSync(ramRdAddr)
  when(ramWrEn) {
    queueRam.write(ramWrAddr, ramWrData)
  }

  // ========================================================================
  // CAM match logic (fully associative lookup)
  // ========================================================================

  // Current CPU being processed
  val curCpu = Reg(UInt(cpuIdWidth bits)) init(0)

  // Match: find slot whose entry matches the request data
  val matchHit   = Bool()
  val matchIndex = UInt(slotIdWidth bits)

  matchHit := False
  matchIndex := U(0, slotIdWidth bits)
  for (s <- lockSlots - 1 to 0 by -1) {
    when(valid(s) && entry(s) === dataReg(curCpu.resized)) {
      matchHit := True
      matchIndex := U(s, slotIdWidth bits)
    }
  }
  // Last assignment wins: smallest matching index has priority (matching VHDL)

  // Register CAM results for use in later states
  val matchHitReg   = Reg(Bool()) init(False)
  val matchIndexReg = Reg(UInt(slotIdWidth bits)) init(0)

  // Empty slot: find first free slot
  val emptyHit   = Bool()
  val emptyIndex = UInt(slotIdWidth bits)

  emptyHit := False
  emptyIndex := U(0, slotIdWidth bits)
  for (s <- lockSlots - 1 to 0 by -1) {
    when(!valid(s)) {
      emptyHit := True
      emptyIndex := U(s, slotIdWidth bits)
    }
  }

  val emptyHitReg   = Reg(Bool()) init(False)
  val emptyIndexReg = Reg(UInt(slotIdWidth bits)) init(0)

  // ========================================================================
  // Per-core status (table full error)
  // ========================================================================

  val statusReg = Vec(Reg(Bool()) init(False), cpuCnt)

  // ========================================================================
  // State machine
  // ========================================================================

  // Helper: compute queue RAM address = slot * cpuCnt + queuePtr
  def queueAddr(slot: UInt, ptr: UInt): UInt = {
    (slot.resize(queueRamAddrWidth) * U(cpuCnt, queueRamAddrWidth bits) +
      ptr.resize(queueRamAddrWidth)).resize(queueRamAddrWidth)
  }

  // ========================================================================
  // DRAIN, BUT DO NOT ADMIT, WHILE A STOP-THE-WORLD IS IN FORCE — item 158.
  //
  // A core owning ANY lock is exempt from gcHalt (see the halted output
  // below), which is deliberate: it must reach its monitorexit or the cluster
  // deadlocks. The consequence nobody enforced is that the exempt SET must
  // shrink. Without the rule here a core owning nothing can ask for a free
  // slot DURING the halt, be granted it, become an owner, and so become exempt
  // from the halt it just walked into -- so "everyone has stopped" may never
  // become true, and a collector waiting for it would wait forever.
  //
  // THE RULE. While any core asserts gcHalt, service a request only if it
  //   - is an UNLOCK          (draining is the whole point of the exemption),
  //   - comes from a core that ALREADY OWNS something (see below), or
  //   - comes from the core that asked for the halt (the collector itself,
  //     which is legitimately running, and which holds the allocator's mutex
  //     across minorGc anyway).
  //
  // "ALREADY OWNS SOMETHING" IS LOAD-BEARING, and dropping it deadlocks the
  // machine. A nested `synchronized` inside a critical section needs a NEW
  // slot; refuse that and the owner can never reach its monitorexit, never
  // drops out of the exempt set, and the collector waits on it forever.
  // IhluGcGrantTest asserts both directions for exactly this reason.
  //
  // A core whose request is not serviced keeps hasPending high, which is
  // lockWait, which HALTS it -- so refusing to admit does not leave it
  // spinning inside the stop-the-world; it parks it, which is what the halt
  // wanted in the first place. The request is serviced when the halt lifts.
  val anyGcHaltReq = (0 until cpuCnt).map(io.syncIn(_).gcHalt).reduce(_ || _)
  val ownsAny = Vec((0 until cpuCnt).map { i =>
    (0 until lockSlots).map(s => valid(s) && owner(s) === U(i, cpuIdWidth bits)).reduce(_ || _)
  })
  def mayBeServiced(i: Int): Bool =
    !anyGcHaltReq || opReg(i) || ownsAny(i) || io.syncIn(i).gcHalt

  // Scan for next core with pending request (round-robin from cpuPtr)
  val foundPending = Bool()
  val foundCpu = UInt(cpuIdWidth bits)

  foundPending := False
  foundCpu := U(0, cpuIdWidth bits)

  // Two-pass scan for round-robin fairness (same as CmpSync)
  // First pass: i <= cpuPtr (lower priority)
  for (i <- cpuCnt - 1 to 0 by -1) {
    when(U(i, cpuIdWidth bits) <= cpuPtr && hasPending(i) && mayBeServiced(i)) {
      foundPending := True
      foundCpu := U(i, cpuIdWidth bits)
    }
  }
  // Second pass: i > cpuPtr (higher priority, overrides)
  for (i <- cpuCnt - 1 to 0 by -1) {
    when(U(i, cpuIdWidth bits) > cpuPtr && hasPending(i) && mayBeServiced(i)) {
      foundPending := True
      foundCpu := U(i, cpuIdWidth bits)
    }
  }

  switch(state) {
    is(State.IDLE) {
      when(foundPending) {
        curCpu := foundCpu
        state := State.RAM_READ
      }
    }

    is(State.RAM_READ) {
      // Register CAM results (combinational match happens this cycle on curCpu's data)
      matchHitReg := matchHit
      matchIndexReg := matchIndex
      emptyHitReg := emptyHit
      emptyIndexReg := emptyIndex

      state := State.RAM_DELAY
    }

    is(State.RAM_DELAY) {
      // Set up the RAM read here, not in RAM_READ, and drive it from the
      // REGISTERED CAM result.
      //
      // Timing: driving it from the combinational `matchIndex` put this whole
      // chain in one cycle — curCpu -> dataReg mux -> 32 x 32-bit CAM compare
      // -> priority encode -> queueHead lookup indexed by that same
      // combinational value -> multiply -> RAM address register. It missed
      // 100 MHz by 1.282 ns on the EP4CGX150 and owned the four worst paths in
      // the design. From a register it is just a lookup, a multiply and the
      // address port.
      //
      // Correctness: readSync samples the address every cycle and ramRdAddr
      // defaults to 0, so presenting the address in RAM_READ meant RAM_DELAY
      // re-sampled address 0 and EXECUTE saw queueRam[0] rather than the
      // matched slot's queue head. Presenting it here lines the data up with
      // EXECUTE, which is where it is consumed (the nextOwner read).
      //
      // Costs no extra cycles — RAM_DELAY previously did nothing but wait.
      when(matchHitReg) {
        ramRdAddr := queueAddr(matchIndexReg, queueHead(matchIndexReg.resized).resized)
      }

      state := State.EXECUTE
    }

    is(State.EXECUTE) {
      val curCpuIdx = curCpu.resized.asInstanceOf[UInt]

      when(!opReg(curCpuIdx)) {
        // ================================================================
        // LOCK operation
        // ================================================================

        when(matchHitReg) {
          // Slot found with matching key
          val slot = matchIndexReg

          when(owner(slot.resized) === curCpu) {
            // Reentrant: same owner, increment count
            count(slot.resized) := count(slot.resized) + 1
          } otherwise {
            // Contention: different owner, enqueue requesting CPU
            ramWrAddr := queueAddr(slot, queueTail(slot.resized).resized)
            ramWrData := curCpu.resize(cpuIdWidth)
            ramWrEn := True

            // Advance tail pointer (modular arithmetic, wraps at cpuCnt)
            val tailPlus1 = queueTail(slot.resized) + 1
            queueTail(slot.resized) := Mux(tailPlus1 === U(cpuCnt, queuePtrWidth bits),
              U(0, queuePtrWidth bits), tailPlus1)

            // Core stalls until lock is granted
            syncFlag(curCpuIdx) := True
          }
        } elsewhen (emptyHitReg) {
          // No match, free slot available: allocate
          val slot = emptyIndexReg

          entry(slot.resized) := dataReg(curCpuIdx)
          valid(slot.resized) := True
          owner(slot.resized) := curCpu.resize(cpuIdWidth)
          count(slot.resized) := U(0, reentrantBits bits)
          queueHead(slot.resized) := U(0, queuePtrWidth bits)
          queueTail(slot.resized) := U(0, queuePtrWidth bits)
        } otherwise {
          // No match, no free slots: table full error
          statusReg(curCpuIdx) := True
        }
      } otherwise {
        // ================================================================
        // UNLOCK operation (assumes only owner calls unlock)
        // ================================================================

        when(matchHitReg) {
          val slot = matchIndexReg

          when(count(slot.resized) > 0) {
            // Reentrant unlock: decrement count
            count(slot.resized) := count(slot.resized) - 1
          } otherwise {
            // Final unlock: check if waiters exist
            when(queueHead(slot.resized) === queueTail(slot.resized)) {
              // Queue empty: deallocate slot
              valid(slot.resized) := False
            } otherwise {
              // Queue non-empty: transfer ownership to next waiter
              val nextOwner = ramRdData  // Read from queue head during RAM_READ
              owner(slot.resized) := nextOwner.resize(cpuIdWidth)
              syncFlag(nextOwner.resized) := False  // Un-stall the next owner

              // Advance head pointer (modular arithmetic, wraps at cpuCnt)
              val headPlus1 = queueHead(slot.resized) + 1
              queueHead(slot.resized) := Mux(headPlus1 === U(cpuCnt, queuePtrWidth bits),
                U(0, queuePtrWidth bits), headPlus1)
            }
          }
        }
        // If no match on unlock, silently ignore (shouldn't happen in correct code)
      }

      // Mark request as processed
      registerOut(curCpuIdx) := registerIn(curCpuIdx)

      // Advance round-robin pointer
      cpuPtr := curCpu

      // Clear status for this core (status is latched, cleared on next request)
      statusReg(curCpuIdx) := False

      state := State.IDLE
    }
  }

  // ========================================================================
  // Output generation
  // ========================================================================

  for (i <- 0 until cpuCnt) {
    // Boot synchronization: broadcast core 0's s_in to all (same as CmpSync)
    io.syncOut(i).s_out := io.syncIn(0).s_in

    // GC halt: if any OTHER core has gcHalt set, this core is halted
    // UNLESS this core owns at least one lock (drain mechanism)
    val gcHaltFromOthers = (0 until cpuCnt).filter(_ != i)
      .map(j => io.syncIn(j).gcHalt).reduce(_ || _)

    // Check if core i owns any lock
    val isLockOwner = (0 until lockSlots).map { s =>
      valid(s) && owner(s) === U(i, cpuIdWidth bits)
    }.reduce(_ || _)

    // Halted = waiting for lock OR (gcHalt from others AND not lock owner)
    // The "pending request" check ensures the core stalls while IHLU processes
    val lockWait = syncFlag(i) || hasPending(i)
    io.syncOut(i).halted := lockWait || (gcHaltFromOthers && !isLockOwner)

    // Status: returns table-full error in bit 0 (read via IO_LOCK)
    io.syncOut(i).status := statusReg(i)
  }

  // IS THE STOP-THE-WORLD ACTUALLY STOPPING ANYONE? — status item 157, and the
  // same signal CmpSync computes; see the long note there for why it is here
  // and not in software. IHLU is the SMP default (CmpSync serialises every
  // lock onto one global lock), so this is the copy that runs on the boards.
  //
  // The exemption above is per-core and deliberate: a core owning ANY lock
  // keeps running through another core's gcHalt so it can drain. That is the
  // behaviour being observed, not a bug being caught.
  val anyGcHalt = (0 until cpuCnt).map(io.syncIn(_).gcHalt).reduce(_ || _)
  val someoneRunning = (0 until cpuCnt)
    .map(j => !io.syncIn(j).gcHalt && !io.syncOut(j).halted).reduce(_ || _)
  // REGISTERED BEFORE BROADCAST. Combinationally this is
  //   nextState -> halted(j) for every core -> OR reduce -> haltViolated ->
  //   every core's Sys -> 32-bit counter enable
  // which is a cluster-wide fanout ending at cpuCnt adders, and it cost the
  // 8-core build its timing: +0.346 ns without it, -0.790 ns with it.
  // Registering splits the path and costs nothing that matters -- this is a
  // CYCLE COUNT read as a difference across a window thousands of cycles long,
  // so a uniform one-cycle shift is invisible and only the first and last cycle
  // of a window can differ, by one. The same argument the IO_PERFCNT counters
  // already make for registering their category decode.
  val violated = RegNext(anyGcHalt && someoneRunning) init (False)
  for (i <- 0 until cpuCnt) io.syncOut(i).haltViolated := violated
  // THE ACKNOWLEDGEMENT — status item 158. Per core, unlike haltViolated: the
  // collector asks "is everyone BUT ME stopped?", and the answer differs per
  // asker. Registered for the same reason `violated` is; one cycle of latency
  // only makes the collector wait a cycle longer, and it is spinning anyway.
  for (i <- 0 until cpuCnt) {
    io.syncOut(i).othersHalted := RegNext(
      (0 until cpuCnt).filter(_ != i).map(io.syncOut(_).halted).fold(True)(_ && _)
    ) init (False)
  }
}
