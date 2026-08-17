package jop.ddr3

import spinal.core._
import spinal.lib._

/**
 * Bridges LruCacheCore's memCmd/memRsp to the Xilinx MIG 7-series user interface.
 *
 * Multi-outstanding. The previous version pipelined writes (issued from IDLE at
 * one per cycle) but serialised READS through `IDLE -> ISSUE_READ -> WAIT_READ`,
 * latching a single command and waiting for `app_rd_data_valid` before looking
 * at the next one. That made it, not the cache, the limit on the DDR3 boards
 * once `LruCacheCore` gained an MSHR file: the cache would offer concurrency the
 * adapter refused. There is no state machine now — commands issue as fast as the
 * MIG accepts them, bounded only by `maxOutstanding`.
 *
 * TWO MIG PROPERTIES THIS RESTS ON:
 *
 *  1. **Ordering is STRICT** on every board here (`<Ordering>Strict</Ordering>`
 *     in each `mig.prj`), so the controller does not reorder and read data comes
 *     back in the order the reads were issued. That is what lets responses be
 *     matched by position instead of by tag — the UI provides no tag.
 *  2. **`app_rd_data_valid` is a one-cycle pulse that cannot be back-pressured.**
 *     Returned data must be captured unconditionally, so it goes straight into
 *     `readFifo`, whose depth is what `maxOutstanding` is really bounding.
 *
 * THE ORDERING TRAP, which is why the structure looks like it does: a write is
 * acknowledged LOCALLY the cycle the MIG accepts it, because the UI returns
 * nothing for a write, while read data arrives tens of cycles later. Push both
 * into one response stream and a write issued after a read will answer first —
 * and `LruCacheCore` matches responses to commands BY ORDER, so it would install
 * the wrong data into the wrong miss. The old adapter was safe only by accident:
 * a read blocked the issue path, so a write could never be in flight beside one.
 * Making reads concurrent removes that accident, so responses are explicitly
 * re-serialised here. Same fix, same reason, as `CacheToDdr2Adapter` — and the
 * same shape as the AlteraSdramAdapter bug (ef36d99).
 *
 * @param addrWidth      cache-side byte address width
 * @param maxOutstanding commands allowed in flight; also the response-FIFO depth
 */
class CacheToMigAdapter(addrWidth: Int = 28, maxOutstanding: Int = 8) extends Component {
  require(maxOutstanding >= 1, "maxOutstanding must be at least 1")

  val io = new Bundle {
    val cmd = slave Stream(new Bundle {
      val addr = Bits(addrWidth bits)
      val write = Bool()
      val wdata = Bits(128 bits)
      val wmask = Bits(16 bits)
    })

    val rsp = master Stream(new Bundle {
      val rdata = Bits(128 bits)
      val error = Bool()
    })

    val busy = out Bool()

    // MIG UI side
    val app_rdy = in Bool()
    val app_wdf_rdy = in Bool()
    val app_rd_data = in Bits(128 bits)
    val app_rd_data_valid = in Bool()

    val app_addr = out Bits(addrWidth bits)
    val app_cmd = out Bits(3 bits)
    val app_en = out Bool()
    val app_wdf_data = out Bits(128 bits)
    val app_wdf_mask = out Bits(16 bits)
    val app_wdf_wren = out Bool()
    val app_wdf_end = out Bool()

    // Debug
    val debugState = out UInt(3 bits)
    /** Commands accepted whose response has not been consumed. */
    val debugOutstanding = out UInt(log2Up(maxOutstanding + 1) bits)
  }

  private val addrAlignBits = log2Up(128 / 8)
  val writeCmd = B"3'x0"
  val readCmd = B"3'x1"

  val cmdFifo = StreamFifo(io.cmd.payloadType, 2)
  cmdFifo.io.push << io.cmd

  // What each accepted command was, in issue order, and the data reads bring
  // back. Both are bounded by `owed`, so neither push can ever be refused.
  val orderFifo = StreamFifo(Bool(), maxOutstanding)
  val readFifo = StreamFifo(Bits(128 bits), maxOutstanding)

  val owed = Reg(UInt(log2Up(maxOutstanding + 1) bits)) init (0)
  val room = owed < maxOutstanding

  // MIG app_addr is byte-space addressed; low bits must be zero for 128-bit transactions.
  def alignedAddr(addr: Bits): Bits = {
    val a = UInt(io.app_addr.getWidth bits)
    a := addr.asUInt.resized
    a(addrAlignBits - 1 downto 0) := 0
    a.asBits
  }

  // --- command issue -------------------------------------------------------
  val head = cmdFifo.io.pop
  val headIsWrite = head.payload.write
  // A write needs the command port AND the write-data port in the same cycle;
  // presenting them together is what the MIG's simplest legal handshake looks
  // like, and it is what this design has always done on hardware.
  val portReady = io.app_rdy && (!headIsWrite || io.app_wdf_rdy)
  val issue = head.valid && portReady && room

  io.app_addr     := alignedAddr(head.payload.addr)
  io.app_cmd      := Mux(headIsWrite, writeCmd, readCmd)
  io.app_en       := issue
  io.app_wdf_data := head.payload.wdata
  io.app_wdf_mask := head.payload.wmask
  io.app_wdf_wren := issue && headIsWrite
  io.app_wdf_end  := issue && headIsWrite

  head.ready := issue

  orderFifo.io.push.valid   := issue
  orderFifo.io.push.payload := headIsWrite

  // Unconditional: the pulse cannot be held off, and `owed` guarantees room.
  readFifo.io.push.valid   := io.app_rd_data_valid
  readFifo.io.push.payload := io.app_rd_data

  // --- response return -----------------------------------------------------
  // EVERY command gets exactly one response, writes included: LruCacheCore
  // records an order-queue entry per issued command — an eviction as well as a
  // refill — and retires them in order, so a missing write acknowledgement
  // deadlocks the memory path.
  val rspIsWrite = orderFifo.io.pop.payload
  val rspCanGo = orderFifo.io.pop.valid && (rspIsWrite || readFifo.io.pop.valid)
  io.rsp.valid         := rspCanGo
  io.rsp.payload.rdata := readFifo.io.pop.payload   // ignored for a write ack
  io.rsp.payload.error := False
  orderFifo.io.pop.ready := io.rsp.ready && rspCanGo
  readFifo.io.pop.ready  := io.rsp.fire && !rspIsWrite

  when(issue && !io.rsp.fire) {
    owed := owed + 1
  } elsewhen (!issue && io.rsp.fire) {
    owed := owed - 1
  }

  io.busy := head.valid || io.cmd.valid || owed =/= 0
  io.debugOutstanding := owed
  // No state machine any more. Kept as a port so the exerciser tops that wire it
  // out still build; reports how much concurrency is actually in use.
  io.debugState := owed.resized
}
