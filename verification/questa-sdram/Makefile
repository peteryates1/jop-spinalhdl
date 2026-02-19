# Questa simulation for JOP GC SDRAM test with timing assertions
#
# Usage: make gen        — generate Verilog + sdram_init.hex from SpinalHDL
#        make sim        — run simulation (batch mode, 20M cycles)
#        make sim_gui    — run simulation with GUI/waveform viewer
#        make clean      — clean build artifacts
#
# The SDRAM model (sdram_model.v) checks for SDRAM protocol timing violations
# that SpinalHDL's SdramModel does NOT enforce. This is the key difference
# from Verilator-based simulation.

QUESTA_HOME ?= /opt/altera/25.1/questa_fse
VLOG  = $(QUESTA_HOME)/bin/vlog
VSIM  = $(QUESTA_HOME)/bin/vsim
VLIB  = $(QUESTA_HOME)/bin/vlib
VMAP  = $(QUESTA_HOME)/bin/vmap

# Generated RTL
RTL = JopCoreWithSdramTestHarness.v
TB  = tb_jop_gc_sdram.v
SDRAM_MODEL = sdram_model.v

# Work library
WORK = work

.PHONY: sim sim_gui compile clean gen

# Generate Verilog + sdram_init.hex from SpinalHDL (run from project root)
gen:
	cd ../.. && sbt "Test / runMain jop.system.JopSmallGcSdramQuestaGen"

# Create work library
$(WORK):
	$(VLIB) $(WORK)

# Compile RTL, SDRAM model, and testbench
compile: $(WORK)
	$(VLOG) -work $(WORK) +acc $(RTL) $(SDRAM_MODEL) $(TB)

# Run simulation (batch mode)
sim: compile
	$(VSIM) -batch -work $(WORK) \
		-do "run -all; quit" \
		tb_jop_gc_sdram \
		| tee sim_output.log
	@echo ""
	@echo "=== Check sim_output.log for TIMING VIOLATION messages ==="
	@grep -c "TIMING VIOLATION\|PROTOCOL ERROR" sim_output.log || echo "No violations found."

# Run simulation with GUI
sim_gui: compile
	$(VSIM) -work $(WORK) \
		-do "add wave -r /tb_jop_gc_sdram/dut/*; add wave -r /tb_jop_gc_sdram/sdram/*; run -all" \
		tb_jop_gc_sdram

clean:
	rm -rf $(WORK) transcript vsim.wlf sim_output.log
