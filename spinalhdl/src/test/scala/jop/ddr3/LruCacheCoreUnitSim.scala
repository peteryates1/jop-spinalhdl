package jop.ddr3

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/**
 * Line-width is selectable so the same functional suite can be run at the widths
 * we actually care about: 32 (fast default), 128 (current DDR3 line) and 256
 * (matches the A-E115FB DDR2 4-beat burst of 32 bytes).
 *
 *   sbt "Test/runMain jop.ddr3.LruCacheCoreUnitSim 256"
 *
 * Passes 7/7 at 32, 128, 256 and 512-bit lines. The vectors are derived from the
 * cache geometry (`addrOf(tag, set)`) rather than hardcoded, because the address
 * layout moves with the line width — byteOffsetWidth is 2 at 32 bits but 5 at
 * 256, so a literal like 0x0020 lands in a different set and tag at each width.
 */
object LruCacheCoreUnitSim extends App {
  val lineWidth = if (args.nonEmpty) args(0).toInt else 32
  val config = CacheConfig(addrWidth = 16, dataWidth = lineWidth, setCount = 4, wayCount = 2)
  val dataBytes = config.dataWidth / 8
  val byteOffsetWidth = log2Up(dataBytes)

  SimConfig
    .compile(new LruCacheCore(config))
    .doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Default signal values
      dut.io.frontend.req.valid #= false
      dut.io.frontend.req.payload.addr #= 0
      dut.io.frontend.req.payload.write #= false
      dut.io.frontend.req.payload.data #= 0
      dut.io.frontend.req.payload.mask #= 0
      dut.io.frontend.rsp.ready #= true
      dut.io.memCmd.ready #= false
      dut.io.memRsp.valid #= false
      dut.io.memRsp.payload.data #= 0
      dut.io.memRsp.payload.error #= false

      dut.clockDomain.waitSampling(5)

      println(s"=== LruCacheCore Unit Test (${config.wayCount}-way, ${config.setCount} sets, " +
              s"${config.addrWidth}-bit addr, ${config.dataWidth}-bit line) ===")

      // Address layout: [ tag | index | byte offset ]. Consecutive sets are one
      // line apart; the same set with a different tag is setCount lines apart.
      // Deriving the test addresses from these rather than hardcoding them is
      // what lets the suite run at any line width.
      val setStride = dataBytes
      val tagStride = config.setCount * dataBytes
      def addrOf(tag: Int, set: Int): Int = tag * tagStride + set * setStride
      def wordOf(addr: Int): Int = addr >> byteOffsetWidth

      // Memory model: maps word addresses to data values
      val memory = scala.collection.mutable.Map[Int, BigInt]()
      // Line-wide mask; was hardcoded to 32 bits when this sim only ran at dataWidth=32.
      val lineMask = (BigInt(1) << config.dataWidth) - 1
      for (i <- 0 until 256) {
        memory(i) = (BigInt("AA000000", 16) + i) & lineMask
      }

      var testsPassed = 0
      var testsFailed = 0

      // Drive one clock cycle, handling memory interface
      def tick(): Unit = {
        // Before the edge: set memCmd.ready based on current memCmd.valid
        // This models an always-ready backend
        dut.io.memCmd.ready #= true
        dut.clockDomain.waitSampling()
      }

      // Execute a cache transaction (read or write) and return the response data
      // The memory interface is handled automatically each cycle
      def cacheTransaction(addr: Int, write: Boolean, data: BigInt, mask: BigInt): BigInt = {
        // Drive request
        dut.io.frontend.req.valid #= true
        dut.io.frontend.req.payload.addr #= addr
        dut.io.frontend.req.payload.write #= write
        dut.io.frontend.req.payload.data #= data
        dut.io.frontend.req.payload.mask #= mask

        // Wait for request acceptance
        var timeout = 100
        do {
          tick()
          timeout -= 1
        } while (!dut.io.frontend.req.ready.toBoolean && timeout > 0)
        assert(timeout > 0, s"Timeout: request not accepted at addr=0x${addr.toHexString}")

        dut.io.frontend.req.valid #= false

        // Now drive the memory interface until we get a frontend response.
        //
        // The model QUEUES outstanding commands. It used to hold exactly one,
        // which was enough only while the cache blocked in WAIT_EVICT_RSP: it
        // now issues an eviction and its refill back to back and is owed a
        // response to each, so a single slot silently dropped the first and the
        // cache waited forever for a response the model had overwritten.
        var gotResponse = false
        var responseData = BigInt(0)
        val memRspQueue = scala.collection.mutable.Queue[BigInt]()
        timeout = 500

        while (!gotResponse && timeout > 0) {
          // Check if there's a pending memCmd that just fired
          if (dut.io.memCmd.valid.toBoolean && dut.io.memCmd.ready.toBoolean) {
            val cmdAddr = dut.io.memCmd.payload.addr.toInt
            val cmdWrite = dut.io.memCmd.payload.write.toBoolean
            val cmdData = dut.io.memCmd.payload.data.toBigInt
            val cmdMask = dut.io.memCmd.payload.mask.toBigInt
            val wordAddr = cmdAddr >> byteOffsetWidth

            if (cmdWrite) {
              // Write-back (eviction): merge written bytes into memory
              val existing = memory.getOrElse(wordAddr, BigInt(0))
              var merged = existing
              for (b <- 0 until dataBytes) {
                if (((cmdMask >> b) & 1) == 0) {  // mask=0 means write this byte
                  val byteMask = BigInt(0xFF) << (b * 8)
                  merged = (merged & ~byteMask) | (cmdData & byteMask)
                }
              }
              memory(wordAddr) = merged & lineMask
              // An eviction is acknowledged too: one response per command.
              memRspQueue.enqueue(BigInt(0))
            } else {
              // Refill read
              memRspQueue.enqueue(memory.getOrElse(wordAddr, BigInt(0)))
            }
          }

          // Offer the oldest outstanding response; responses are in order.
          if (memRspQueue.nonEmpty) {
            dut.io.memRsp.valid #= true
            dut.io.memRsp.payload.data #= memRspQueue.head
            dut.io.memRsp.payload.error #= false
          } else {
            dut.io.memRsp.valid #= false
          }

          // memRsp.ready is a function of the cache's own state, not of
          // memRsp.valid, so reading it before the edge gives this cycle's value.
          val memRspAccepted = memRspQueue.nonEmpty && dut.io.memRsp.ready.toBoolean

          // Check for frontend response
          if (dut.io.frontend.rsp.valid.toBoolean && dut.io.frontend.rsp.ready.toBoolean) {
            responseData = dut.io.frontend.rsp.payload.data.toBigInt
            gotResponse = true
          }

          tick()
          if (memRspAccepted) memRspQueue.dequeue()
          timeout -= 1
        }

        // Clear memRsp
        dut.io.memRsp.valid #= false

        assert(gotResponse, s"Timeout: no response at addr=0x${addr.toHexString}")
        responseData
      }

      def doRead(addr: Int): BigInt = {
        // all-ones keep-mask = write nothing; (1 << dataBytes) overflows Int
        // once the line reaches 256 bits, hence BigInt
        cacheTransaction(addr, write = false, data = 0, mask = (BigInt(1) << dataBytes) - 1)
      }

      def doWrite(addr: Int, data: BigInt, mask: BigInt): BigInt = {
        cacheTransaction(addr, write = true, data = data, mask = mask)
      }

      def check(testName: String, got: BigInt, expected: BigInt): Unit = {
        if (got == expected) {
          println(s"  PASS: $testName (0x${got.toString(16)})")
          testsPassed += 1
        } else {
          println(s"  FAIL: $testName - got 0x${got.toString(16)}, expected 0x${expected.toString(16)}")
          testsFailed += 1
        }
      }

      // --- Test 1: Read miss (cold cache) ---
      val a00 = addrOf(0, 0)          // set 0, tag 0
      val a10 = addrOf(1, 0)          // set 0, tag 1 — same set, evicts nothing yet
      val a20 = addrOf(2, 0)          // set 0, tag 2 — forces an eviction (2-way)
      val a01 = addrOf(0, 1)          // set 1, tag 0
      println(f"Test 1: Read miss (addr 0x$a00%04x)")
      check(f"read 0x$a00%04x", doRead(a00), memory(wordOf(a00)))

      // --- Test 2: Read hit (same addr) ---
      println(f"Test 2: Read hit (addr 0x$a00%04x)")
      check(f"read 0x$a00%04x hit", doRead(a00), memory(wordOf(a00)))

      // --- Test 3: Read different address in same set ---
      println(f"Test 3: Read miss (addr 0x$a10%04x, same set)")
      check(f"read 0x$a10%04x", doRead(a10), memory(wordOf(a10)))

      // --- Test 4: Both ways of set 0 now full, read another addr in set 0 ---
      println(f"Test 4: Read miss with eviction (addr 0x$a20%04x, set 0 eviction)")
      check(f"read 0x$a20%04x", doRead(a20), memory(wordOf(a20)))

      // --- Test 5: Read from set 1 ---
      println(f"Test 5: Read miss (addr 0x$a01%04x, set 1)")
      check(f"read 0x$a01%04x", doRead(a01), memory(wordOf(a01)))

      // --- Test 6: Write hit then read back ---
      // mask = 0 writes every byte, so the line becomes the (zero-extended)
      // value regardless of how wide it is.
      println(f"Test 6: Write hit then read back (addr 0x$a20%04x)")
      doWrite(a20, BigInt("0EADBEEF", 16), mask = 0)
      check("read back", doRead(a20), BigInt("0EADBEEF", 16))

      // --- Test 7: Evict dirty data and verify write-back ---
      // Fill set 0 with two other lines to evict 0x0020 (which has dirty data)
      println("Test 7: Evict dirty data and verify write-back")
      doRead(a00)              // may or may not evict, depends on PLRU
      doRead(a10)              // ensure both ways hold different lines
      doRead(addrOf(3, 0))     // set 0, tag 3 — forces the dirty line out
      // The dirty line for a20 must have been written back to memory.
      val memVal = memory.getOrElse(wordOf(a20), BigInt(-1))
      if (memVal == BigInt("0EADBEEF", 16)) {
        println(s"  PASS: dirty data written back to memory (0x${memVal.toString(16)})")
        testsPassed += 1
      } else {
        println(s"  FAIL: memory at word 8 = 0x${memVal.toString(16)}, expected 0x0EADBEEF")
        testsFailed += 1
      }

      println(s"\n=== Results: $testsPassed passed, $testsFailed failed ===")
      if (testsFailed > 0) System.exit(1)
    }
}
