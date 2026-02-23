package jop.ddr3

import spinal.core._
import spinal.core.sim._
import spinal.lib._

object LruCacheCoreUnitSim extends App {
  val config = CacheConfig(addrWidth = 16, dataWidth = 32, setCount = 4, wayCount = 2)
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

      println("=== LruCacheCore Unit Test (2-way, 4 sets, 16-bit addr, 32-bit data) ===")

      // Memory model: maps word addresses to data values
      val memory = scala.collection.mutable.Map[Int, BigInt]()
      val mask32 = (BigInt(1) << 32) - 1
      for (i <- 0 until 256) {
        memory(i) = (BigInt("AA000000", 16) + i) & mask32
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
      def cacheTransaction(addr: Int, write: Boolean, data: Int, mask: Int): BigInt = {
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

        // Now drive the memory interface until we get a frontend response
        var gotResponse = false
        var responseData = BigInt(0)
        var pendingMemRsp = false
        var pendingMemRspData = BigInt(0)
        var pendingMemRspIsWrite = false
        timeout = 500

        while (!gotResponse && timeout > 0) {
          // Check if there's a pending memCmd that just fired
          if (dut.io.memCmd.valid.toBoolean && dut.io.memCmd.ready.toBoolean) {
            val cmdAddr = dut.io.memCmd.payload.addr.toInt
            val cmdWrite = dut.io.memCmd.payload.write.toBoolean
            val cmdData = dut.io.memCmd.payload.data.toInt
            val cmdMask = dut.io.memCmd.payload.mask.toInt
            val wordAddr = cmdAddr >> byteOffsetWidth

            if (cmdWrite) {
              // Write-back (eviction): merge written bytes into memory
              val existing = memory.getOrElse(wordAddr, BigInt(0))
              var merged = existing
              for (b <- 0 until dataBytes) {
                if (((cmdMask >> b) & 1) == 0) {  // mask=0 means write this byte
                  val byteMask = BigInt(0xFF) << (b * 8)
                  merged = (merged & ~byteMask) | ((BigInt(cmdData) & byteMask))
                }
              }
              memory(wordAddr) = merged & mask32
              pendingMemRsp = true
              pendingMemRspData = BigInt(0)
              pendingMemRspIsWrite = true
            } else {
              // Refill read
              pendingMemRsp = true
              pendingMemRspData = memory.getOrElse(wordAddr, BigInt(0))
              pendingMemRspIsWrite = false
            }
          }

          // Drive memRsp if we have a pending response
          if (pendingMemRsp) {
            dut.io.memRsp.valid #= true
            dut.io.memRsp.payload.data #= pendingMemRspData
            dut.io.memRsp.payload.error #= false
          } else {
            dut.io.memRsp.valid #= false
          }

          // Check if memRsp was accepted (will be evaluated next edge)
          if (pendingMemRsp && dut.io.memRsp.valid.toBoolean && dut.io.memRsp.ready.toBoolean) {
            pendingMemRsp = false
          }

          // Check for frontend response
          if (dut.io.frontend.rsp.valid.toBoolean && dut.io.frontend.rsp.ready.toBoolean) {
            responseData = dut.io.frontend.rsp.payload.data.toBigInt
            gotResponse = true
          }

          tick()
          timeout -= 1
        }

        // Clear memRsp
        dut.io.memRsp.valid #= false

        assert(gotResponse, s"Timeout: no response at addr=0x${addr.toHexString}")
        responseData
      }

      def doRead(addr: Int): BigInt = {
        cacheTransaction(addr, write = false, data = 0, mask = (1 << dataBytes) - 1)
      }

      def doWrite(addr: Int, data: Int, mask: Int): BigInt = {
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
      println("Test 1: Read miss (addr 0x0000)")
      check("read 0x0000", doRead(0x0000), BigInt("AA000000", 16))

      // --- Test 2: Read hit (same addr) ---
      println("Test 2: Read hit (addr 0x0000)")
      check("read 0x0000 hit", doRead(0x0000), BigInt("AA000000", 16))

      // --- Test 3: Read different address in same set ---
      // setCount=4, byteOffset=2: index = addr[3:2], tag = addr[15:4]
      // addr 0x0010 → index=0, tag=1 (different tag, same set as 0x0000)
      println("Test 3: Read miss (addr 0x0010, same set)")
      check("read 0x0010", doRead(0x0010), BigInt("AA000004", 16))

      // --- Test 4: Both ways of set 0 now full, read another addr in set 0 ---
      // addr 0x0020 → index=0, tag=2
      println("Test 4: Read miss with eviction (addr 0x0020, set 0 eviction)")
      check("read 0x0020", doRead(0x0020), BigInt("AA000008", 16))

      // --- Test 5: Read from set 1 ---
      // addr 0x0004 → index=1, tag=0
      println("Test 5: Read miss (addr 0x0004, set 1)")
      check("read 0x0004", doRead(0x0004), BigInt("AA000001", 16))

      // --- Test 6: Write hit then read back ---
      // addr 0x0020 is in cache (from test 4)
      println("Test 6: Write hit then read back (addr 0x0020)")
      doWrite(0x0020, 0x0EADBEEF, mask = 0x0)  // mask=0 means write all bytes
      check("read back", doRead(0x0020), BigInt("0EADBEEF", 16))

      // --- Test 7: Evict dirty data and verify write-back ---
      // Fill set 0 with two other lines to evict 0x0020 (which has dirty data)
      println("Test 7: Evict dirty data and verify write-back")
      doRead(0x0000)  // may or may not evict, depends on PLRU
      doRead(0x0010)  // should ensure both ways are filled with different data
      doRead(0x0030)  // addr 0x0030 → index=0, tag=3. Forces eviction from set 0
      // Check memory was updated with 0x0EADBEEF at word addr 8 (0x0020 >> 2)
      val memVal = memory.getOrElse(8, BigInt(-1))
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
