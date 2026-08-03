package jop.ddr2

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.util.Random

/**
 * Functional check of CacheToDdr2Adapter against a behavioural model of the
 * ALTMEMPHY local interface.
 *
 * The model reproduces the two behaviours that make this interface different
 * from the MIG, because they are what the adapter has to get right:
 *  - `local_ready` de-asserts at random, covering command AND write data at once
 *  - read data returns some cycles later on `local_rdata_valid`, in order, and
 *    CANNOT be back-pressured — so if the adapter lets more reads run than its
 *    response FIFO can hold, data is silently lost and the test will see it
 *
 * Checks: every read returns the value previously written to that address,
 * responses arrive in order, byte enables are honoured (a masked write must
 * leave those bytes alone), and nothing is dropped when the consumer stalls.
 */
object CacheToDdr2AdapterSim extends App {

  val ADDR_W = 30          // 1 GB byte address
  val DATA_W = 256
  val WORD_BYTES = DATA_W / 8
  val WORD_SHIFT = log2Up(WORD_BYTES)

  SimConfig.compile(new CacheToDdr2Adapter(ADDR_W, DATA_W, rspDepth = 8)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.cmd.valid #= false
    dut.io.rsp.ready #= true
    dut.io.local_ready #= false
    dut.io.local_rdata #= 0
    dut.io.local_rdata_valid #= false
    dut.io.local_init_done #= false
    dut.clockDomain.waitSampling(5)

    // ---- behavioural DDR2 model -----------------------------------------
    val mem = mutable.Map[BigInt, BigInt]()      // local word address -> data
    val readPipe = mutable.Queue[(Int, BigInt)]() // (countdown, data)
    val rnd = new Random(1)
    var acceptedWrites = 0
    var acceptedReads = 0
    var returned = 0

    dut.io.local_init_done #= true

    dut.clockDomain.onSamplings {
      // Use the ready the DUT ACTUALLY SAW at this edge. Randomising first and
      // deciding acceptance with the new value makes the model accept commands
      // the DUT never saw accepted, which shows up as duplicated reads.
      val ready = dut.io.local_ready.toBoolean

      val wr = dut.io.local_write_req.toBoolean
      val rd = dut.io.local_read_req.toBoolean
      if (ready && (wr || rd)) {
        val a = dut.io.local_address.toBigInt
        if (wr) {
          val be = dut.io.local_be.toBigInt
          val wdata = dut.io.local_wdata.toBigInt
          val old = mem.getOrElse(a, BigInt(0))
          // byte enables: 1 = write this byte
          var merged = old
          for (b <- 0 until WORD_BYTES) {
            if (((be >> b) & 1) == 1) {
              val m = BigInt(0xFF) << (b * 8)
              merged = (merged & ~m) | (wdata & m)
            }
          }
          mem(a) = merged
          acceptedWrites += 1
        } else {
          // latency 3..9 cycles, returned strictly in order
          readPipe.enqueue((3 + rnd.nextInt(7), mem.getOrElse(a, BigInt(0))))
          acceptedReads += 1
        }
      }

      // advance the read pipeline; head pops when its countdown expires
      var fire = false
      if (readPipe.nonEmpty) {
        val (c, d) = readPipe.head
        if (c <= 0) {
          readPipe.dequeue()
          dut.io.local_rdata #= d
          fire = true
          returned += 1
        } else {
          readPipe.update(0, (c - 1, d))
        }
      }
      dut.io.local_rdata_valid #= fire

      // back-pressure for the NEXT edge
      dut.io.local_ready #= rnd.nextInt(100) < 70
    }

    // ---- stimulus --------------------------------------------------------
    def word(i: Int): BigInt = {
      var v = BigInt(0)
      for (k <- 0 until WORD_BYTES) v |= BigInt((i * 7 + k * 31) & 0xFF) << (k * 8)
      v
    }

    val N = 200
    val expected = mutable.Queue[BigInt]()
    val golden = mutable.Map[BigInt, BigInt]()

    // consumer: sometimes stalls, to prove the response FIFO bound holds
    var got = 0
    var mismatches = 0
    val checker = fork {
      while (true) {
        dut.io.rsp.ready #= rnd.nextInt(100) < 60
        dut.clockDomain.waitSampling()
        if (dut.io.rsp.valid.toBoolean && dut.io.rsp.ready.toBoolean) {
          val d = dut.io.rsp.payload.data.toBigInt
          val want = expected.dequeue()
          if (d != want) {
            println(f"MISMATCH at response $got: got 0x$d%x want 0x$want%x")
            mismatches += 1
          }
          got += 1
        }
      }
    }

    def issue(addrWord: Int, isWrite: Boolean, data: BigInt, mask: BigInt): Unit = {
      dut.io.cmd.valid #= true
      dut.io.cmd.payload.addr #= BigInt(addrWord) << WORD_SHIFT
      dut.io.cmd.payload.write #= isWrite
      dut.io.cmd.payload.data #= data
      dut.io.cmd.payload.mask #= mask
      dut.clockDomain.waitSamplingWhere(dut.io.cmd.ready.toBoolean)
      dut.io.cmd.valid #= false
    }

    // 1. write a block, full-line (keep-mask all zero = write every byte)
    for (i <- 0 until N) {
      golden(BigInt(i)) = word(i)
      issue(i, isWrite = true, word(i), BigInt(0))
    }

    // 2. a masked write: keep the low 16 bytes, replace the top 16
    val maskedAddr = 5
    val keepMask = (BigInt(1) << (WORD_BYTES / 2)) - 1        // 1 = keep
    val newData = BigInt("A5", 16) * ((BigInt(1) << DATA_W) - 1) / 255
    issue(maskedAddr, isWrite = true, newData, keepMask)
    val oldV = golden(BigInt(maskedAddr))
    val lowMask = (BigInt(1) << (DATA_W / 2)) - 1
    golden(BigInt(maskedAddr)) = (oldV & lowMask) | (newData & ~lowMask)

    // 3. read everything back
    for (i <- 0 until N) {
      expected.enqueue(golden(BigInt(i)))
      issue(i, isWrite = false, 0, BigInt(0))
    }

    // drain
    var guard = 0
    while (got < N && guard < 200000) { dut.clockDomain.waitSampling(); guard += 1 }

    println(s"writes accepted=$acceptedWrites reads accepted=$acceptedReads returned=$returned")
    println(s"responses checked=$got mismatches=$mismatches")

    def fail(m: String): Unit = { println(s"FAIL: $m"); simFailure(m) }
    if (got != N) fail(s"expected $N responses, got $got (data lost?)")
    if (mismatches != 0) fail(s"$mismatches data mismatches")
    if (returned != acceptedReads) fail("read responses lost")
    println("PASS: CacheToDdr2Adapter — data, ordering, byte enables and FIFO bound all hold")
  }
}
