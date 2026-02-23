package jop.system

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import jop.ddr3._

/**
 * Simple diagnostic test for LruCacheCore with CacheToBramAdapter backend.
 * Verifies basic read/write operations through the cache.
 */
object LruCacheCoreTest extends App {
  val addrWidth = 28
  val dataWidth = 128
  val dataBytes = dataWidth / 8

  case class CacheTestHarness() extends Component {
    val cache = new LruCacheCore(CacheConfig(addrWidth = addrWidth, dataWidth = dataWidth, setCount = 16, wayCount = 1))
    val backend = CacheToBramAdapter(addrWidth, dataWidth, 4096, readLatency = 2, writeLatency = 1)

    val io = new Bundle {
      val frontend = slave(CacheFrontend(addrWidth, dataWidth))
      val cacheState = out UInt(3 bits)
      val busy = out Bool()
    }

    // Frontend → Cache
    cache.io.frontend.req << io.frontend.req
    io.frontend.rsp << cache.io.frontend.rsp

    // Cache → Backend
    backend.io.cmd.valid         := cache.io.memCmd.valid
    backend.io.cmd.payload.addr  := cache.io.memCmd.payload.addr
    backend.io.cmd.payload.write := cache.io.memCmd.payload.write
    backend.io.cmd.payload.data  := cache.io.memCmd.payload.data
    backend.io.cmd.payload.mask  := cache.io.memCmd.payload.mask
    cache.io.memCmd.ready        := backend.io.cmd.ready

    cache.io.memRsp.valid         := backend.io.rsp.valid
    cache.io.memRsp.payload.data  := backend.io.rsp.payload.data
    cache.io.memRsp.payload.error := backend.io.rsp.payload.error
    backend.io.rsp.ready          := cache.io.memRsp.ready

    io.cacheState := cache.io.debugState
    io.busy := cache.io.busy

    // Initialize backend memory with known pattern
    val initData = (0 until 256).map { i =>
      val w0 = BigInt(i * 4)
      val w1 = BigInt(i * 4 + 1)
      val w2 = BigInt(i * 4 + 2)
      val w3 = BigInt(i * 4 + 3)
      (w3 << 96) | (w2 << 64) | (w1 << 32) | w0
    }
    backend.mem.init(initData.map(v => B(v, dataWidth bits)))
  }

  SimConfig.compile(CacheTestHarness()).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    // Initialize frontend signals
    dut.io.frontend.req.valid #= false
    dut.io.frontend.req.payload.addr #= 0
    dut.io.frontend.req.payload.write #= false
    dut.io.frontend.req.payload.data #= BigInt(0)
    dut.io.frontend.req.payload.mask #= BigInt(0)
    dut.io.frontend.rsp.ready #= true

    dut.clockDomain.waitSampling(5)

    def doRead(addr: Long): BigInt = {
      // Send read request
      dut.io.frontend.req.valid #= true
      dut.io.frontend.req.payload.addr #= addr
      dut.io.frontend.req.payload.write #= false
      dut.io.frontend.req.payload.data #= BigInt(0)
      dut.io.frontend.req.payload.mask #= BigInt(0)
      dut.clockDomain.waitSampling()

      // Wait for request to be accepted
      var timeout = 100
      while (!dut.io.frontend.req.ready.toBoolean && timeout > 0) {
        dut.clockDomain.waitSampling()
        timeout -= 1
      }
      dut.io.frontend.req.valid #= false

      // Wait for response
      timeout = 100
      while (!dut.io.frontend.rsp.valid.toBoolean && timeout > 0) {
        dut.clockDomain.waitSampling()
        timeout -= 1
      }
      if (timeout <= 0) {
        println(s"  TIMEOUT waiting for read response! cacheState=${dut.io.cacheState.toInt} busy=${dut.io.busy.toBoolean}")
        return BigInt(-1)
      }
      val data = dut.io.frontend.rsp.payload.data.toBigInt
      dut.io.frontend.rsp.ready #= true
      dut.clockDomain.waitSampling()
      data
    }

    def doWrite(addr: Long, data: BigInt, mask: BigInt): Unit = {
      // mask: MIG convention, 1 = keep cached byte (don't write)
      dut.io.frontend.req.valid #= true
      dut.io.frontend.req.payload.addr #= addr
      dut.io.frontend.req.payload.write #= true
      dut.io.frontend.req.payload.data #= data
      dut.io.frontend.req.payload.mask #= mask
      dut.clockDomain.waitSampling()

      var timeout = 100
      while (!dut.io.frontend.req.ready.toBoolean && timeout > 0) {
        dut.clockDomain.waitSampling()
        timeout -= 1
      }
      dut.io.frontend.req.valid #= false

      // Wait for write response
      timeout = 100
      while (!dut.io.frontend.rsp.valid.toBoolean && timeout > 0) {
        dut.clockDomain.waitSampling()
        timeout -= 1
      }
      if (timeout <= 0) {
        println(s"  TIMEOUT waiting for write response! cacheState=${dut.io.cacheState.toInt} busy=${dut.io.busy.toBoolean}")
      }
      dut.io.frontend.rsp.ready #= true
      dut.clockDomain.waitSampling()
    }

    // Test 1: Read address 0x00 (first cache line)
    println("=== Test 1: Read addr 0x00 (cold miss) ===")
    val data0 = doRead(0x00)
    val expected0 = (BigInt(3) << 96) | (BigInt(2) << 64) | (BigInt(1) << 32) | BigInt(0)
    println(f"  Read: 0x${data0}%032x")
    println(f"  Expect: 0x${expected0}%032x")
    println(s"  ${if (data0 == expected0) "PASS" else "FAIL"}")

    // Test 2: Read address 0x00 again (should be a hit)
    println("\n=== Test 2: Read addr 0x00 (hit) ===")
    val data0b = doRead(0x00)
    println(f"  Read: 0x${data0b}%032x")
    println(s"  ${if (data0b == expected0) "PASS" else "FAIL"}")

    // Test 3: Read address 0x10 (second cache line, different set)
    println("\n=== Test 3: Read addr 0x10 (cold miss, set 1) ===")
    val data1 = doRead(0x10)
    val expected1 = (BigInt(7) << 96) | (BigInt(6) << 64) | (BigInt(5) << 32) | BigInt(4)
    println(f"  Read: 0x${data1}%032x")
    println(f"  Expect: 0x${expected1}%032x")
    println(s"  ${if (data1 == expected1) "PASS" else "FAIL"}")

    // Test 4: Write to address 0x00 (hit), then read back
    println("\n=== Test 4: Write then read addr 0x00 ===")
    // Write word 0 (bytes 0-3) with value 0xDEADBEEF, keep rest (mask = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF0)
    val writeMask = (BigInt(1) << 16) - 1 - BigInt(0xF) // all 1s except bytes 0-3
    val writeData = BigInt(0xDEADBEEFL)
    doWrite(0x00, writeData, writeMask)
    val data0c = doRead(0x00)
    val expected0c = (BigInt(3) << 96) | (BigInt(2) << 64) | (BigInt(1) << 32) | BigInt(0xDEADBEEFL)
    println(f"  Read: 0x${data0c}%032x")
    println(f"  Expect: 0x${expected0c}%032x")
    println(s"  ${if (data0c == expected0c) "PASS" else "FAIL"}")

    // Test 5: Read address that aliases to set 0 (eviction test)
    println("\n=== Test 5: Read addr 0x100 (alias to set 0, evict dirty) ===")
    val data2 = doRead(0x100)
    val idx = 0x100 / 16 // word index
    val expected2 = (BigInt(idx*4+3) << 96) | (BigInt(idx*4+2) << 64) | (BigInt(idx*4+1) << 32) | BigInt(idx*4)
    println(f"  Read: 0x${data2}%032x")
    println(f"  Expect: 0x${expected2}%032x")
    println(s"  ${if (data2 == expected2) "PASS" else "FAIL"}")

    // Test 6: Read back address 0x00 (should have been evicted, re-fetched with write-back)
    println("\n=== Test 6: Read addr 0x00 (re-fetch after eviction) ===")
    val data0d = doRead(0x00)
    println(f"  Read: 0x${data0d}%032x")
    println(f"  Expect: 0x${expected0c}%032x (should have written-back DEADBEEF)")
    println(s"  ${if (data0d == expected0c) "PASS" else "FAIL"}")

    println("\n=== Done ===")
  }
}
