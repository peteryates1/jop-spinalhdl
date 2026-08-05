package jop.memory

import spinal.core.Component
import spinal.lib.bus.bmb.Bmb
import spinal.lib.memory.sdram.sdr.SdramInterface

/**
 * Common surface of the two BMB-to-SDR-SDRAM bridges, so the memory controller
 * factory can pick one by SDRAM data width and the top level does not care which
 * it got.
 *
 *   - [[BmbSdramCtrl32]]   32-bit BMB -> **16**-bit SDRAM, two SDRAM ops per beat.
 *                          Used by every Altera/Xilinx board here.
 *   - [[BmbSdramCtrlWide]] 32-bit BMB -> **32**-bit SDRAM, one op per beat.
 *                          Used by the Colorlight i5 (EM638325BK-6H).
 *
 * Only the three ports the top level actually wires are exposed. Each bridge
 * keeps its own `io.debug` bundle, whose contents differ because the two have
 * genuinely different internal state (there is no `sendingHigh` in a 1:1
 * bridge), and neither is wired at the top level.
 */
trait SdramBridge { this: Component =>
  def bmbPort: Bmb
  def sdramPort: SdramInterface
  def fillPort: MemFill
}
