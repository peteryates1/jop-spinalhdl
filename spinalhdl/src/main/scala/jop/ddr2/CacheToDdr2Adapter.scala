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

  // Responses are buffered because the controller cannot be stalled.
  val rspFifo = StreamFifo(CacheRsp(dataWidth), rspDepth)
  io.rsp << rspFifo.io.pop

  rspFifo.io.push.valid          := io.local_rdata_valid
  rspFifo.io.push.payload.data   := io.local_rdata
  rspFifo.io.push.payload.error  := False

  // Reads in flight. Capped at rspDepth so the push above can never be refused:
  // every read we issue has a slot reserved for it before it is issued.
  val outstanding = Reg(UInt(log2Up(rspDepth + 1) bits)) init (0)
  val issueRead   = False
  val readRoom    = outstanding < rspDepth

  when(issueRead && !io.local_rdata_valid) {
    outstanding := outstanding + 1
  } elsewhen (!issueRead && io.local_rdata_valid) {
    outstanding := outstanding - 1
  }

  // --- command issue -------------------------------------------------------
  // Single-word accesses: one local word IS one cache line, so no bursting is
  // needed and local_size stays 1 with burstbegin on every command.
  val canIssue = io.cmd.valid && io.local_init_done &&
                 (io.cmd.payload.write || readRoom)

  io.local_address    := io.cmd.payload.addr(addrWidth - 1 downto wordShift)
  io.local_wdata      := io.cmd.payload.data
  io.local_be         := ~io.cmd.payload.mask     // keep-mask -> byte-enable
  io.local_size       := B"3'd1"
  io.local_write_req  := canIssue && io.cmd.payload.write
  io.local_read_req   := canIssue && !io.cmd.payload.write
  io.local_burstbegin := canIssue

  // One signal acknowledges both the command and its write data.
  io.cmd.ready := canIssue && io.local_ready
  when(io.cmd.ready && !io.cmd.payload.write) { issueRead := True }

  io.busy := io.cmd.valid || outstanding =/= 0 || rspFifo.io.occupancy =/= 0
  io.debugOutstanding := outstanding
}
