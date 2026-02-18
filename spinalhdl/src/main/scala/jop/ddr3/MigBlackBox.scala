package jop.ddr3

import spinal.core._

// BlackBox for Vivado-generated MIG core (Native UI variant).
// Port list matches the generated mig_7series_0 top-level.
class MigBlackBox extends BlackBox {
  val io = new Bundle {
    val ddr3_dq      = inout(Analog(Bits(16 bits)))
    val ddr3_dqs_n   = inout(Analog(Bits(2 bits)))
    val ddr3_dqs_p   = inout(Analog(Bits(2 bits)))
    val ddr3_addr    = out Bits(14 bits)
    val ddr3_ba      = out Bits(3 bits)
    val ddr3_ras_n   = out Bool()
    val ddr3_cas_n   = out Bool()
    val ddr3_we_n    = out Bool()
    val ddr3_reset_n = out Bool()
    val ddr3_ck_p    = out Bits(1 bits)
    val ddr3_ck_n    = out Bits(1 bits)
    val ddr3_cke     = out Bits(1 bits)
    val ddr3_cs_n    = out Bits(1 bits)
    val ddr3_dm      = out Bits(2 bits)
    val ddr3_odt     = out Bits(1 bits)

    val sys_clk_i = in Bool()
    val clk_ref_i = in Bool()

    val app_addr         = in Bits(28 bits)
    val app_cmd          = in Bits(3 bits)
    val app_en           = in Bool()
    val app_wdf_data     = in Bits(128 bits)
    val app_wdf_end      = in Bool()
    val app_wdf_mask     = in Bits(16 bits)
    val app_wdf_wren     = in Bool()
    val app_rd_data      = out Bits(128 bits)
    val app_rd_data_end  = out Bool()
    val app_rd_data_valid = out Bool()
    val app_rdy          = out Bool()
    val app_wdf_rdy      = out Bool()
    val app_sr_req       = in Bool()
    val app_ref_req      = in Bool()
    val app_zq_req       = in Bool()
    val app_sr_active    = out Bool()
    val app_ref_ack      = out Bool()
    val app_zq_ack       = out Bool()

    val ui_clk             = out Bool()
    val ui_clk_sync_rst    = out Bool()
    val init_calib_complete = out Bool()
    val device_temp        = out Bits(12 bits)

    val sys_rst = in Bool()
  }

  setBlackBoxName("mig_7series_0")
  noIoPrefix()
}
