# Makefile for Vivado xsim DDR3 simulation of JopDdr3Top
#
# Uses the real MIG RTL and Xilinx DDR3 behavioral model (ddr3_model.sv).
# Requires Vivado installation (XILINX_VIVADO must be set).

# Project root (relative to this directory)
PROJECT_ROOT = ../..
SBT_DIR      = $(PROJECT_ROOT)/spinalhdl

# MIG IP paths
MIG_IP_BASE = $(PROJECT_ROOT)/fpga/alchitry-au/vivado/ip/mig_7series_0/mig_7series_0
MIG_RTL     = $(MIG_IP_BASE)/user_design/rtl
MIG_SIM     = $(MIG_IP_BASE)/example_design/sim

# Vivado tools
VIVADO_HOME  ?= /opt/xilinx/2025.2/Vivado
VIVADO_DIR   ?= $(VIVADO_HOME)
VIVADO_BIN   = $(VIVADO_HOME)/bin
XVLOG        = $(VIVADO_BIN)/xvlog
XVHDL        = $(VIVADO_BIN)/xvhdl
XELAB        = $(VIVADO_BIN)/xelab
XSIM         = $(VIVADO_BIN)/xsim

# Simulation parameters
MAX_CYCLES   ?= 2000000
DUMP_WAVES   ?= 0

# ============================================================================
# Targets
# ============================================================================

.PHONY: gen compile sim sim_gui clean help

help:
	@echo "Targets:"
	@echo "  gen       - Generate Verilog + hex from SpinalHDL (requires sbt)"
	@echo "  compile   - Compile all Verilog sources with xvlog + elaborate with xelab"
	@echo "  sim       - Run simulation in batch mode"
	@echo "  sim_gui   - Run simulation with waveform viewer"
	@echo "  clean     - Remove build artifacts"
	@echo ""
	@echo "Parameters:"
	@echo "  MAX_CYCLES=N    Simulation timeout (default: 2000000)"
	@echo "  DUMP_WAVES=1    Enable VCD waveform dump"

# ============================================================================
# Generate Verilog + hex
# ============================================================================

gen:
	cd $(PROJECT_ROOT) && sbt "Test / runMain jop.system.JopDdr3SimGen"

# ============================================================================
# Compile
# ============================================================================

# 1. Behavioral clock wizard (replaces Xilinx ClkWiz IP)
# 2. MIG RTL (real controller, PHY, calibration)
# 3. DDR3 behavioral model
# 4. SpinalHDL-generated DUT
# 5. Testbench
# 6. Xilinx glbl module

compile: compile_clkwiz compile_mig compile_ddr3_model compile_dut compile_tb compile_glbl elaborate

compile_clkwiz:
	$(XVLOG) -work work clk_wiz_0_sim.v \
		2>&1 | tee xvlog_clkwiz.log

compile_mig:
	$(XVLOG) -work work \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_byte_group_io.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_byte_lane.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_calib_top.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_if_post_fifo.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_mc_phy.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_mc_phy_wrapper.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_of_pre_fifo.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_4lanes.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ck_addr_cmd_delay.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_dqs_found_cal.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_dqs_found_cal_hr.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_init.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ocd_cntlr.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ocd_data.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ocd_edge.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ocd_lim.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ocd_mux.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ocd_po_cntlr.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_ocd_samp.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_oclkdelay_cal.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_prbs_rdlvl.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_rdlvl.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_tempmon.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_top.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_wrcal.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_wrlvl.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_phy_wrlvl_off_delay.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_prbs_gen.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_ddr_skip_calib_tap.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_poc_cc.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_poc_edge_store.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_poc_meta.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_poc_pd.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_poc_tap_base.v \
		$(MIG_RTL)/phy/mig_7series_v4_2_poc_top.v \
		$(MIG_RTL)/clocking/mig_7series_v4_2_clk_ibuf.v \
		$(MIG_RTL)/clocking/mig_7series_v4_2_infrastructure.v \
		$(MIG_RTL)/clocking/mig_7series_v4_2_iodelay_ctrl.v \
		$(MIG_RTL)/clocking/mig_7series_v4_2_tempmon.v \
		$(MIG_RTL)/ecc/mig_7series_v4_2_ecc_buf.v \
		$(MIG_RTL)/ecc/mig_7series_v4_2_ecc_dec_fix.v \
		$(MIG_RTL)/ecc/mig_7series_v4_2_ecc_gen.v \
		$(MIG_RTL)/ecc/mig_7series_v4_2_ecc_merge_enc.v \
		$(MIG_RTL)/ecc/mig_7series_v4_2_fi_xor.v \
		$(MIG_RTL)/ip_top/mig_7series_v4_2_memc_ui_top_std.v \
		$(MIG_RTL)/ip_top/mig_7series_v4_2_mem_intfc.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_arb_mux.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_arb_row_col.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_arb_select.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_bank_cntrl.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_bank_common.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_bank_compare.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_bank_mach.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_bank_queue.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_bank_state.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_col_mach.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_mc.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_rank_cntrl.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_rank_common.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_rank_mach.v \
		$(MIG_RTL)/controller/mig_7series_v4_2_round_robin_arb.v \
		$(MIG_RTL)/ui/mig_7series_v4_2_ui_cmd.v \
		$(MIG_RTL)/ui/mig_7series_v4_2_ui_rd_data.v \
		$(MIG_RTL)/ui/mig_7series_v4_2_ui_top.v \
		$(MIG_RTL)/ui/mig_7series_v4_2_ui_wr_data.v \
		$(MIG_RTL)/mig_7series_0_mig_sim.v \
		$(MIG_RTL)/mig_7series_0.v \
		2>&1 | tee xvlog_mig.log

compile_ddr3_model:
	$(XVLOG) -sv -work work \
		-d x2Gb -d sg15E -d x16 \
		-i $(MIG_SIM) \
		$(MIG_SIM)/ddr3_model.sv \
		2>&1 | tee xvlog_ddr3.log

compile_dut:
	$(XVLOG) -work work \
		JopDdr3Top.v \
		2>&1 | tee xvlog_dut.log

compile_tb:
	$(XVLOG) -work work \
		tb_jop_ddr3.v \
		2>&1 | tee xvlog_tb.log

compile_glbl:
	$(XVLOG) -work work \
		$(VIVADO_DIR)/data/verilog/src/glbl.v \
		2>&1 | tee xvlog_glbl.log

elaborate:
	LIBRARY_PATH=/usr/lib/x86_64-linux-gnu:$$LIBRARY_PATH \
	$(XELAB) -debug typical \
		-L unisims_ver -L secureip \
		work.tb_jop_ddr3 work.glbl \
		-generic_top "MAX_CYCLES=$(MAX_CYCLES)" \
		-timescale 1ps/1ps \
		-s tb_jop_ddr3_sim \
		2>&1 | tee xelab.log

# ============================================================================
# Simulate
# ============================================================================

sim:
	@echo "log_wave -recursive *" > xsim_batch.tcl
	@echo "run all" >> xsim_batch.tcl
	@echo "exit" >> xsim_batch.tcl
	$(XSIM) tb_jop_ddr3_sim \
		-tclbatch xsim_batch.tcl \
		2>&1 | tee xsim.log

sim_gui:
	$(XSIM) tb_jop_ddr3_sim -gui

# ============================================================================
# Clean
# ============================================================================

clean:
	rm -rf xsim.dir .Xil
	rm -f *.log *.jou *.pb *.wdb *.vcd
	rm -f xsim_batch.tcl webtalk*.xml webtalk*.jou
	rm -f xvlog.pb xelab.pb xsim.pb

distclean: clean
	rm -f JopDdr3Top.v *.bin ddr3_init.hex
