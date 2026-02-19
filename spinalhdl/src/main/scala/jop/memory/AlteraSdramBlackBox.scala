package jop.memory

import spinal.core._

/**
 * Configuration for Altera altera_sdram_tri_controller.
 * All timing values in clock cycles.
 */
case class AlteraSdramConfig(
  numChipSelects: Int = 1,
  sdramBankWidth: Int = 2,
  sdramRowWidth: Int = 13,
  sdramColWidth: Int = 9,
  sdramDataWidth: Int = 16,
  casLatency: Int = 3,
  initRefresh: Int = 2,
  refreshPeriod: Int = 1563,
  powerupDelay: Int = 20000,
  tRFC: Int = 7,
  tRP: Int = 2,
  tRCD: Int = 2,
  tWR: Int = 5,
  maxRecTime: Int = 1
) {
  // Avalon address width = row + col + bank
  val ctrlAddrWidth: Int = sdramRowWidth + sdramColWidth + sdramBankWidth
}

/**
 * BlackBox wrapping Altera's altera_sdram_tri_controller.
 *
 * Uses TRISTATE_EN=0 (separate DQ in/out/oe signals, no tristate pipeline latency).
 * The sdram_dq inout port is omitted (dead code with our patched Verilog).
 */
case class AlteraSdramBlackBox(cfg: AlteraSdramConfig) extends BlackBox {
  setDefinitionName("altera_sdram_tri_controller")

  addGeneric("TRISTATE_EN", 0)
  addGeneric("NUM_CHIPSELECTS", cfg.numChipSelects)
  addGeneric("CNTRL_ADDR_WIDTH", cfg.ctrlAddrWidth)
  addGeneric("SDRAM_BANK_WIDTH", cfg.sdramBankWidth)
  addGeneric("SDRAM_ROW_WIDTH", cfg.sdramRowWidth)
  addGeneric("SDRAM_COL_WIDTH", cfg.sdramColWidth)
  addGeneric("SDRAM_DATA_WIDTH", cfg.sdramDataWidth)
  addGeneric("CAS_LATENCY", cfg.casLatency)
  addGeneric("INIT_REFRESH", cfg.initRefresh)
  addGeneric("REFRESH_PERIOD", cfg.refreshPeriod)
  addGeneric("POWERUP_DELAY", cfg.powerupDelay)
  addGeneric("T_RFC", cfg.tRFC)
  addGeneric("T_RP", cfg.tRP)
  addGeneric("T_RCD", cfg.tRCD)
  addGeneric("T_WR", cfg.tWR)
  addGeneric("MAX_REC_TIME", cfg.maxRecTime)

  val io = new Bundle {
    // Clock/Reset
    val clk   = in Bool()
    val rst_n = in Bool()

    // Avalon-MM Slave Interface
    val avs_read          = in Bool()
    val avs_write         = in Bool()
    val avs_byteenable    = in Bits(cfg.sdramDataWidth / 8 bits)
    val avs_address       = in UInt(cfg.ctrlAddrWidth bits)
    val avs_writedata     = in Bits(cfg.sdramDataWidth bits)
    val avs_readdata      = out Bits(cfg.sdramDataWidth bits)
    val avs_readdatavalid = out Bool()
    val avs_waitrequest   = out Bool()

    // TCM Interface (unused with TRISTATE_EN=0)
    val tcm_grant   = in Bool()
    val tcm_request = out Bool()

    // SDRAM Interface (no sdram_dq inout — dead code with our patch)
    val sdram_addr   = out Bits(cfg.sdramRowWidth bits)
    val sdram_ba     = out Bits(cfg.sdramBankWidth bits)
    val sdram_dq_out = out Bits(cfg.sdramDataWidth bits)
    val sdram_dq_in  = in Bits(cfg.sdramDataWidth bits)
    val sdram_dq_oe  = out Bool()
    val sdram_dqm    = out Bits(cfg.sdramDataWidth / 8 bits)
    val sdram_ras_n  = out Bool()
    val sdram_cas_n  = out Bool()
    val sdram_we_n   = out Bool()
    val sdram_cs_n   = out Bits(cfg.numChipSelects bits)
    val sdram_cke    = out Bool()
  }

  noIoPrefix()

  // Map clock domain (active-low async reset)
  mapClockDomain(
    clock = io.clk,
    reset = io.rst_n,
    resetActiveLevel = LOW
  )
}
