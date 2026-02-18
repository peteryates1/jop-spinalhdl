package jop.memory

import spinal.core._
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.SdramGeneration._
import spinal.lib.memory.sdram.sdr.SdramTimings

/**
 * W9864G6JT - Winbond 64Mbit SDR SDRAM (8MB)
 *
 * 16-bit data, 12-bit row, 8-bit column, 4 banks.
 * Used on Trenz CYC5000 board.
 */
object W9864G6JT {
  def layout = SdramLayout(
    generation = SDR,
    bankWidth = 2,
    columnWidth = 8,
    rowWidth = 12,
    dataWidth = 16
  )

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
