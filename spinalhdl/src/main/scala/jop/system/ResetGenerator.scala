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
 */
object ResetGenerator {
  /**
   * Generate a reset signal from a PLL locked indicator and system clock.
   *
   * @param pllLocked PLL locked signal (may be in a different clock domain)
   * @param systemClk System clock output from PLL
   * @return Active-high reset signal synchronized to systemClk
   */
  def apply(pllLocked: Bool, systemClk: Bool): Bool = {
    val rawCd = ClockDomain(
      clock = systemClk,
      config = ClockDomainConfig(resetKind = BOOT)
    )
    val gen = new ClockingArea(rawCd) {
      val res_cnt = Reg(UInt(3 bits)) init(0)
      when(pllLocked && res_cnt =/= 7) {
        res_cnt := res_cnt + 1
      }
      val int_res = !pllLocked || !res_cnt(0) || !res_cnt(1) || !res_cnt(2)
    }
    gen.int_res
  }
}
