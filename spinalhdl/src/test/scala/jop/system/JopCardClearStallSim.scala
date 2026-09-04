package jop.system

import spinal.core._
import spinal.core.sim._
import jop.utils.JopFileLoader
import jop.config.{JopCoreConfig, MicrocodePaths}
import jop.memory.JopMemoryConfig

/**
 * Does writing IO_CARD_CLEAR actually STALL the core? — status item 131.
 *
 * THE GAP THIS FILLS. The card-table clear-all sweep drops every mark that
 * arrives while it runs, so the fix makes the I/O write block until the table
 * is clear. `CardTableTest` proves the COMPONENT's half of that (clrBusy is
 * high from the request cycle, with no gap, at all five board geometries).
 * Nothing proved the other half: that `clrBusy` actually reaches the pipeline
 * through CardCtrlPort.busy -> JopCore.cardBusy -> pipeline.io.memBusy. Before
 * this file, `grep -rl 'CardCtrlPort|cardBusy|clrBusy' spinalhdl/src/test`
 * matched exactly one file, and it was the component test.
 *
 * WHY HARDWARE CANNOT ANSWER IT. The fix was validated on three boards, and
 * `GcStressTest` soaked 439k/360k/206k rounds fault-free. That shows nothing
 * broke — but it would show exactly the same if the stall were one cycle short,
 * or absent entirely, because a dropped mark costs a live object only when a
 * cross-generation store lands in the window AND that object has no other root.
 * It is statistical, rare, and silent. A soak passing is not evidence the stall
 * happened.
 *
 * WHY THE BUDGET IS RAISED TO 16 KB. The sweep is `cardWords32` cycles. At the
 * GC sims' 512 B that is 128 cycles, which is inside the range an ordinary L2
 * miss can occupy on this harness (readLatency 10, burstLen 8), so a 128-cycle
 * stall proves nothing. 16 KB is what the EP4CGX150 and both XC7A100T boards
 * actually carry, and it gives 4096 — unmistakable.
 *
 * THE ASSERTION IS RELATIVE, NOT ABSOLUTE. It compares the longest stall seen
 * against the longest stall seen BEFORE the app reaches its card-clear. That
 * self-calibrates to whatever the memory system happens to cost on the day,
 * so it cannot pass by accident on a slow backend and cannot fail because
 * someone retuned the cache.
 *
 *   sbt "Test/runMain jop.system.JopCardClearStallSim"
 *
 * DISCIPLINE: docs/testing-discipline.md. Asserts on CONTENT -- a stall length
 * against a self-calibrated baseline -- never on the exit status, since an App
 * exits 0 whatever it printed. PROVED RED by removing only `|| cardBusy` from
 * JopCore.scala:337, which takes the maximum stall from 4099 cycles back to 51.
 */
object JopCardClearStallSim extends App {

  val jopFilePath = jop.utils.SimApp.jop("Small", "CardMarkTest")
  val romData = JopFileLoader.loadMicrocodeRom(MicrocodePaths.simulationRom)
  val ramData = JopFileLoader.loadStackRam(MicrocodePaths.simulationRam)

  // 2 MB of memory so a 16 KB budget is not clamped: cardCount is
  // min(memWords >> cardMinShift, budgetBits), and 16 KB is 131072 bits, so it
  // needs at least 524288 words behind it to reach the real 4096-word table.
  //
  // JopCoreTestHarness, NOT the cache harness. The cache harness takes
  // `hasCard = true` but contains no CardTable component at all, so
  // `io.card.rdData` has never had a driver and every sim using it fails
  // elaboration -- see status item 139. This one instantiates a real table and
  // wires the port.
  val memBytes = 2 * 1024 * 1024
  val mainMemData = JopFileLoader.jopFileToMemoryInit(jopFilePath, memBytes / 4)

  val cfg = JopCoreConfig(memConfig = JopMemoryConfig(
    mainMemSize = memBytes, hasCardTable = true, cardTableBudgetBytes = 16 * 1024))

  SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(100 MHz)))
    .compile(JopCoreTestHarness(romData, ramData, mainMemData,
                                memSize = memBytes, coreConfig = Some(cfg)))
    .doSim { dut =>
      val nWords = dut.config.memConfig.cardWords32
      println(s"card table: ${dut.config.memConfig.cardCount} cards, " +
              s"shift ${dut.config.memConfig.cardShift}, $nWords table words " +
              s"=> a clear-all sweep is $nWords cycles")
      require(nWords >= 1024,
        s"the sweep is only $nWords cycles — too short to tell from a cache miss; " +
        "raise cardBudgetBytes or memWords")

      dut.clockDomain.forkStimulus(10)

      val uart = new StringBuilder
      var cycle = 0
      var done = false
      // Longest run of cycles with the pipeline stalled and the PC not moving.
      var stallRun = 0
      var maxBefore = 0      // longest such run before the app announces its result
      var maxOverall = 0
      var lastPc = -1
      var sawClear = false

      while (cycle < 12000000 && !done) {
        cycle += 1
        dut.clockDomain.waitSampling()

        // THE PC, NOT io.memBusy. `io.memBusy` is exported at JopCore.scala:471
        // as `memCtrl.io.memOut.busy` ALONE -- it deliberately omits the other
        // stall terms that reach `pipeline.io.memBusy` at :337, cardBusy among
        // them. Gating on it made this test report a 50-cycle maximum and
        // announce that the fix did not work, when what had actually happened
        // is that the test was watching a signal which cannot see this stall.
        // A frozen PC is the observable that means "the core is not retiring".
        val pc = dut.io.pc.toInt
        if (pc == lastPc) stallRun += 1
        else {
          if (stallRun > maxOverall) maxOverall = stallRun
          // Everything up to the first sweep-length stall is the calibration
          // baseline: ordinary memory stalls on this harness.
          if (!sawClear) {
            if (stallRun >= nWords - 8) sawClear = true
            else if (stallRun > maxBefore) maxBefore = stallRun
          }
          stallRun = 0
        }
        lastPc = pc

        if (dut.io.uartTxValid.toBoolean) {
          val c = dut.io.uartTxData.toInt
          uart.append(if (c >= 32 && c < 127) c.toChar else '.')
        }
        val o = uart.toString
        if (o.contains("CARD OK") || o.contains("CARD FAIL")) done = true
      }
      if (stallRun > maxOverall) maxOverall = stallRun

      val o = uart.toString
      println(s"\n=== $cycle cycles ===")
      println(f"longest stall BEFORE the clear (ordinary memory): $maxBefore%6d cycles")
      println(f"longest stall overall:                            $maxOverall%6d cycles")
      println(f"the sweep should be:                              $nWords%6d cycles")

      def fail(m: String): Unit = { println(s"FAIL: $m"); System.exit(1) }

      // The app must still work — a stall that breaks card marking is not a fix.
      if (o.contains("CARD FAIL")) fail("card marking mismatch")
      if (!o.contains("CARD OK")) fail(s"did not see CARD OK (uart: ${o.takeRight(80)})")

      // THE PROPERTY. The clear must produce a stall of about the sweep length.
      if (maxOverall < nWords - 8)
        fail(s"longest stall was $maxOverall cycles, but a clear-all sweep is $nWords. " +
             "IO_CARD_CLEAR did not block the core: clrBusy is not reaching " +
             "pipeline.io.memBusy. Status item 131.")

      // THE CONTROL. It must stand out from ordinary memory stalls, or the
      // check above could be satisfied by a slow backend rather than by the fix.
      if (maxBefore >= nWords - 8)
        fail(s"ordinary memory stalls reach $maxBefore cycles, as long as the sweep " +
             s"($nWords) — this test cannot distinguish them and proves nothing")

      println(f"PASS: IO_CARD_CLEAR stalls the core for $maxOverall%d cycles " +
              f"(sweep $nWords%d), against a $maxBefore%d-cycle worst case for " +
              "ordinary memory — the stall is the clear, not the backend")
    }
}
