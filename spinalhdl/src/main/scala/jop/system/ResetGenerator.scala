package jop.system

import spinal.core._

/**
 * Standard JOP reset generator.
 *
 * Creates a synchronous reset that stays active until:
 * 1. The PLL reports locked
 * 2. A 3-bit counter has counted to 7
 *
 * Identical logic extracted from all top-level files.
 *
 * RUNTIME RESET. Until 2026-08-18 this had no input but the PLL lock, and
 * `res_cnt` is `resetKind = BOOT` -- it initialises when the FPGA is
 * CONFIGURED and never again. That is why every board's workflow said
 * "reprogram before each download": reconfiguration was not a habit, it was
 * the only reset in the design. `resetRequest` adds one.
 *
 * A REQUESTED RESET IS NOT A POWER-ON RESET, and the difference matters.
 * Configuration clears every flip-flop in the fabric; this only re-runs the
 * reset sequence, so registers built without `init()` keep whatever the
 * previous application left in them. This design has ~405 of those (item 45),
 * so a re-download landing in a machine that is not quite box-fresh is the
 * expected failure mode. `JOP_SIM_XINIT=random` reproduces exactly that
 * condition in simulation and is the qualification test for this feature.
 */
object ResetGenerator {
  /** Cycles a requested reset is held. Power-on gets 8 (the 3-bit counter)
    * plus however long the PLL takes to lock; a requested reset has no PLL
    * settling behind it, so hold it long enough for peripherals with their own
    * init sequences -- notably the SDRAM controller -- to see a clean edge and
    * restart. 1024 is ~17 us at 60 MHz, and costs nothing but a counter. */
  val RequestedResetCycles = 1024

  /** DRAM boards hold far longer, because the memory controller keeps running
    * underneath and its outstanding read data must land and be discarded while
    * the adapter is still in reset. 4096 cycles is ~45 us at 91.7 MHz (DDR3) or
    * ~55 us at 75 MHz (DDR2), orders of magnitude past either controller's read
    * latency. Releasing early would drop a stale beat into a fresh FIFO on a
    * path that matches responses BY POSITION -- see CacheMigResetSim, which
    * fails deliberately when the hold is shortened.
    *
    * SECOND LAYER since 2026-08-22, and it does NOT replace this one.
    * LruCacheCore now clears its valid-bit Mem in an INIT state before serving
    * anything, so for `setCount` cycles after reset it consumes no responses
    * and a stale pulsed beat arriving in that window is simply dropped. At the
    * shipped 512 sets that is 512 cycles, well past either controller's read
    * latency, so a short hold would often now be survivable.
    *
    * "Often" is the problem. The INIT window scales with setCount, not with
    * memory latency, so the margin is accidental: a build with a small L2, or a
    * slower controller, loses it silently. This hold is still the guarantee.
    * The interaction is documented in CacheMigResetSim, whose LATENCY had to be
    * raised above the INIT window for its negative test to remain meaningful. */
  val DramResetCycles = 4096

  /** Hold a reset for `cycles` after each `resetRequest` pulse. Call inside a
    * ClockingArea whose domain is NOT the one being reset. */
  def requestedHold(resetRequest: Bool, cycles: Int = RequestedResetCycles): Bool = {
    val hold = Reg(UInt(log2Up(cycles + 1) bits)) init(0)
    when(resetRequest) {
      hold := cycles
    } elsewhen(hold =/= 0) {
      hold := hold - 1
    }
    hold =/= 0
  }

  /**
   * Generate a reset signal from a PLL locked indicator and system clock.
   *
   * @param pllLocked    PLL locked signal (may be in a different clock domain)
   * @param systemClk    System clock output from PLL
   * @param resetRequest optional one-cycle pulse asking for a reset; must be
   *                     synchronous to `systemClk` and driven from OUTSIDE the
   *                     domain this reset controls, or it resets its own source
   * @return Active-high reset signal synchronized to systemClk
   */
  def apply(pllLocked: Bool, systemClk: Bool, resetRequest: Bool = null): Bool = {
    val rawCd = ClockDomain(
      clock = systemClk,
      config = ClockDomainConfig(resetKind = BOOT)
    )
    val gen = new ClockingArea(rawCd) {
      val res_cnt = Reg(UInt(3 bits)) init(0)
      when(pllLocked && res_cnt =/= 7) {
        res_cnt := res_cnt + 1
      }
      val powerOn = !pllLocked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)

      val requested = if (resetRequest == null) False else requestedHold(resetRequest)

      val int_res = powerOn || requested
    }
    gen.int_res
  }
}
