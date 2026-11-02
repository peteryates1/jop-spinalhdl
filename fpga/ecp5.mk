# ---------------------------------------------------------------------------
# The ECP5 synthesis step, once.
#
# Included by the JOP board flows on the open-source ECP5 toolchain
# (colorlight-i5, colorlight-db-i5). Each board keeps its own generate, place
# and route, and program rules; this is the one step they must not differ on.
#
# WHY. yosys's model of the ECP5 block RAM (DP16KD) has no clock-to-out, so
# ABC9 deepens the RAM-fed fetch path -- JOP's critical path on the i5 -- to
# match whatever unrelated logic it sees as slowest: adding Ethernet moved the
# i5 by 9 MHz without touching the fetch RTL (status item 175).
# ecp5_dp16kd.v is yosys's own DP16KD with that delay. synth_ecp5 reads the
# stock cell library in its `begin` step; the model then replaces DP16KD's
# entry, and the rest of synth_ecp5 runs unchanged.
#
#   $(call ecp5_synth,<top>,<verilog files>,<json>,<log>)
#
# Run it from the generated Verilog's directory: its $readmemb paths are bare
# file names. A rule using it lists $(ECP5_DP16KD) as a prerequisite, so a new
# delay re-synthesises; .github/scripts/check-ecp5-bram-timing.sh holds both.
# ---------------------------------------------------------------------------

# This file's directory, taken before any later include changes MAKEFILE_LIST.
ECP5_DP16KD := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))ecp5_dp16kd.v)

ecp5_synth = yosys -p "read_verilog $(2); \
	synth_ecp5 -top $(1) -run :coarse; \
	read_verilog -lib -specify -overwrite $(ECP5_DP16KD); \
	synth_ecp5 -top $(1) -run coarse: -json $(3)" \
	> $(4) 2>&1 || { tail -20 $(4); exit 1; }
