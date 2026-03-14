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
    case other => throw new RuntimeException(s"No SDRAM timing for device '$other'")
  }
}
