package jop.memory

import spinal.core._
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.SdramGeneration._
import spinal.lib.memory.sdram.sdr.SdramTimings
import jop.config.MemoryDevice

/**
 * W9825G6JH6 - Winbond 256Mbit SDR SDRAM (32MB)
 *
 * 16-bit data, 13-bit row, 9-bit column, 4 banks.
 * Used on QMTECH EP4CGX150 and Wukong boards.
 */
object W9825G6JH6 {
  /** Timings for -7 speed grade (7.5ns CAS) */
  def timingGrade7 = SdramTimings(
    bootRefreshCount =   8,
    tPOW             = 200 us,
    tREF             =  64 ms,
    tRC              =  60 ns,
    tRFC             =  60 ns,
    tRAS             =  42 ns,
    tRP              =  18 ns,
    tRCD             =  18 ns,
    cMRD             =  2,
    tWR              = 7.5 ns,
    cWR              =  1
  )
}

/**
 * W9864G6JT - Winbond 64Mbit SDR SDRAM (8MB)
 *
 * 16-bit data, 12-bit row, 8-bit column, 4 banks.
 * Used on Trenz CYC5000 board.
 */
object W9864G6JT {
  /** Timings for -6 speed grade (6ns CAS) at 100 MHz */
  def timingGrade6 = SdramTimings(
    bootRefreshCount =   8,
    tPOW             = 200 us,
    tREF             =  64 ms,
    tRC              =  60 ns,
    tRFC             =  60 ns,
    tRAS             =  42 ns,
    tRP              =  18 ns,
    tRCD             =  18 ns,
    cMRD             =  2,
    tWR              =   6 ns,
    cWR              =  1
  )
}

/**
 * EM638325BK-6H - Etron 64Mbit SDR SDRAM (8MB)
 *
 * **32-bit data**, 11-bit row, 8-bit column, 4 banks — the only 32-bit-wide SDR
 * part here. Used on the Colorlight i5 v7.0.
 */
object EM638325BK6H {
  /** Timings for the -6 speed grade.
    *
    * tREF is 32 ms, not the 64 ms the other parts here use, and that is
    * deliberate rather than a typo. SpinalHDL's SdramCtrl derives the refresh
    * interval as `tREF / 2^rowWidth`, so what matters is the quotient: this part
    * has 2048 rows, so 32 ms gives 15.6 us between refreshes — the same interval
    * the 4096-row W9864G6JT gets from 64 ms. Using 64 ms here would refresh half
    * as often as every other board and drift outside the usual SDR spec.
    */
  def timingGrade6 = SdramTimings(
    bootRefreshCount =   8,
    tPOW             = 200 us,
    tREF             =  32 ms,
    tRC              =  60 ns,
    tRFC             =  60 ns,
    tRAS             =  42 ns,
    tRP              =  18 ns,
    tRCD             =  18 ns,
    cMRD             =  2,
    tWR              =   6 ns,
    cWR              =  1
  )
}

/**
 * M12L64322A-6 - ESMT 64Mbit SDR SDRAM (8MB), on the Colorlight i9+ module.
 *
 * Same geometry as EM638325BK6H (32-bit, 11-bit row, 8-bit column, 4 banks).
 * Timings are the -6 column of the datasheet (Colorlight-FPGA-Projects/doc/
 * M12L64322A(2S).pdf): tRCD/tRP 18, tRAS 42, tRC = tRFC 60 ns.
 */
object M12L64322A {
  /** tREF 32 ms over 2048 rows = 15.6 us between refreshes, which is the
    * datasheet's own "64 ms, 4K cycle" -- see EM638325BK6H for why the quotient
    * is what matters.
    *
    * Write recovery is specified as tRDL = 2 CLOCKS, not a time. SdramCtrl
    * waits cWR + ceil(tWR) cycles, so cWR = 2 meets it at every frequency;
    * cWR = 1 (the i5's value) would meet it only while ceil(6 ns) >= 1 cycle,
    * i.e. below 166 MHz -- true here, but by luck of the clock, not by design. */
  def timingGrade6 = SdramTimings(
    bootRefreshCount =   8,
    tPOW             = 200 us,
    tREF             =  32 ms,
    tRC              =  60 ns,
    tRFC             =  60 ns,
    tRAS             =  42 ns,
    tRP              =  18 ns,
    tRCD             =  18 ns,
    cMRD             =  2,
    tWR              =   6 ns,
    cWR              =  2
  )
}

/**
 * Centralized SDRAM device info — derives layout and timing from MemoryDevice.
 */
object SdramDeviceInfo {

  /** Derive SdramLayout from MemoryDevice fields.
    * @param count number of ganged chips (>1 widens data bus) */
  def layoutFor(md: MemoryDevice, count: Int = 1): SdramLayout = SdramLayout(
    generation = SDR,
    bankWidth = md.bankWidth,
    columnWidth = md.columnWidth,
    rowWidth = md.rowWidth,
    dataWidth = md.dataWidth * count
  )

  /** Map MemoryDevice to its timing constants */
  def timingFor(md: MemoryDevice): SdramTimings = md.name match {
    case "W9825G6JH6"  => W9825G6JH6.timingGrade7
    case "IS42S16160G" => W9825G6JH6.timingGrade7  // Same geometry/timing
    case "W9864G6JT"   => W9864G6JT.timingGrade6
    case "EM638325BK6H" => EM638325BK6H.timingGrade6
    case "M12L64322A"   => M12L64322A.timingGrade6
    case other => throw new RuntimeException(s"No SDRAM timing for device '$other'")
  }
}
