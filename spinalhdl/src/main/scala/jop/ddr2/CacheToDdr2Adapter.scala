package jop.ddr2

import spinal.core._
import spinal.lib._
import jop.ddr3.{CacheReq, CacheRsp}

/**
 * Bridges LruCacheCore's memCmd/memRsp to the Altera ALTMEMPHY DDR2 local
 * interface on the A-E115FB (EP4CE115 + 1 GB DDR2 SODIMM).
 *
 * Simpler than CacheToMigAdapter because the Altera interface is a plain
 * request/response:
 *  - ONE `local_ready` covers command AND write data; MIG splits those into
 *    app_rdy and app_wdf_rdy, so this needs no write-data state machine.
 *  - `local_wdata` is sampled on the same cycle as `local_write_req`.
 *  - Everything lives in the `phy_clk` domain, so there is no clock crossing.
 *
 * Two things are NOT symmetrical with the MIG and are easy to get wrong:
 *
 *  1. **Mask polarity is inverted.** The cache's `mask` is a KEEP mask — 1 means
 *     "leave this byte alone" — which happens to match MIG's `app_wdf_mask`, so
 *     that adapter passes it straight through. DDR2's `local_be` is a byte
 *     ENABLE, 1 meaning "write this byte". Hence `local_be := ~mask`.
 *
 *  2. **Reads cannot be back-pressured.** `local_rdata_valid` arrives whenever
 *     the controller is ready and there is no way to stall it, but the cache's
 *     memRsp is a Stream that can stall. So responses land in a FIFO and the
 *     number of reads in flight is capped at the space available in it —
 *     otherwise a slow consumer would silently drop returned data.
 *
 * @param addrWidth  cache-side BYTE address width (30 for 1 GB)
 * @param dataWidth  cache line width in bits; 256 matches the half-rate local
 *                   interface and the DDR2 BL=4 burst (32 bytes)
 * @param rspDepth   response FIFO depth, and therefore the read-issue limit
 */
class CacheToDdr2Adapter(addrWidth: Int = 30,
                         dataWidth: Int = 256,
                         rspDepth: Int = 8) extends Component {

  /** Bytes per local word; the local address counts these, not bytes. */
  private val wordBytes = dataWidth / 8
  private val wordShift = log2Up(wordBytes)          // 5 for 256-bit words
  val localAddrWidth = addrWidth - wordShift         // 25 for 1 GB

  require(dataWidth % 8 == 0, "dataWidth must be byte aligned")
  require(localAddrWidth > 0, "addrWidth must exceed the word size")

  val io = new Bundle {
    // --- cache side ---
    val cmd  = slave Stream (CacheReq(addrWidth, dataWidth))
    val rsp  = master Stream (CacheRsp(dataWidth))
    val busy = out Bool()

    // --- DDR2 controller local interface ---
    val local_ready       = in Bool()
    val local_rdata       = in Bits (dataWidth bits)
    val local_rdata_valid = in Bool()
    val local_init_done   = in Bool()

    val local_address    = out Bits (localAddrWidth bits)
    val local_write_req  = out Bool()
    val local_read_req   = out Bool()
    val local_burstbegin = out Bool()
    val local_wdata      = out Bits (dataWidth bits)
    val local_be         = out Bits (wordBytes bits)
    val local_size       = out Bits (3 bits)

    val debugOutstanding = out UInt (log2Up(rspDepth + 1) bits)
  }

  // Register the incoming command before it reaches the controller. Without
  // this, `local_wdata` is driven combinationally all the way from inside the
  // cache: during a backend fill, `fillWord` addresses the data BRAM, whose
  // read feeds the merge logic, which feeds 256 bits of routing into the IP's
  // write-data buffer input — 13.75 ns against a 13.33 ns period at 75 MHz.
  // That path is placement-sensitive enough to swing ~0.6 ns between fitter
  // runs, so seeds only move it around; the register removes it. Costs one
  // cycle per memory command, against a DRAM access of tens.
  val cmd = io.cmd.m2sPipe()

  // Responses are buffered because the controller cannot be stalled.
  val rspFifo = StreamFifo(CacheRsp(dataWidth), rspDepth)
  io.rsp << rspFifo.io.pop

  // Responses owed: commands accepted whose response has not yet been consumed.
  // Counted all the way to the POP rather than to the FIFO push, because reads
  // still in flight and responses already queued draw on the same rspDepth
  // budget — so the push below can never be refused. (Counting only to the push
  // bounds in-flight reads but not queued ones, which lets a stalled consumer
  // overflow the FIFO and silently drop returned data.)
  val owed = Reg(UInt(log2Up(rspDepth + 1) bits)) init (0)
  val room = owed < rspDepth

  // A write acknowledgement that lost the race with read data waits here for
  // one cycle. Read data arrives on the controller's schedule and cannot be
  // held off, so it always wins the single FIFO push port.
  val ackPending = RegInit(False)

  // --- command issue -------------------------------------------------------
  // Single-word accesses: one local word IS one cache line, so no bursting is
  // needed and local_size stays 1 with burstbegin on every command.
  val canIssue = cmd.valid && io.local_init_done && room && !ackPending

  io.local_address    := cmd.payload.addr(addrWidth - 1 downto wordShift)
  io.local_wdata      := cmd.payload.data
  io.local_be         := ~cmd.payload.mask     // keep-mask -> byte-enable
  io.local_size       := B"3'd1"
  io.local_write_req  := canIssue && cmd.payload.write
  io.local_read_req   := canIssue && !cmd.payload.write
  io.local_burstbegin := canIssue

  // One signal acknowledges both the command and its write data.
  cmd.ready := canIssue && io.local_ready

  // EVERY command gets exactly one response, writes included. LruCacheCore
  // issues an eviction as a memCmd WRITE and then waits in WAIT_EVICT_RSP for
  // a memRsp, exactly as it waits after a refill; CacheToMigAdapter satisfies
  // this by pushing a dummy response for each accepted write. Without it the
  // first dirty eviction deadlocks the whole memory path — which is what hung
  // the A-E115FB serial download at 8193 words, one word past the 32 KB cache.
  val writeFire = cmd.fire && cmd.payload.write
  val ackNow    = writeFire || ackPending

  rspFifo.io.push.valid         := io.local_rdata_valid || ackNow
  rspFifo.io.push.payload.data  := io.local_rdata   // ignored for a write ack
  rspFifo.io.push.payload.error := False

  ackPending := io.local_rdata_valid && ackNow

  when(cmd.fire && !io.rsp.fire) {
    owed := owed + 1
  } elsewhen (!cmd.fire && io.rsp.fire) {
    owed := owed - 1
  }

  // Both stages count: a command still waiting at the port has not been seen yet.
  io.busy := io.cmd.valid || cmd.valid || owed =/= 0
  io.debugOutstanding := owed
}
