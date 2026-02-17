# CocoTB Makefile for JOP core testing
#
# Requires JOP_HOME to point to the original JOP repository
# (https://github.com/peteryates1/jop)

# Defaults
SIM ?= ghdl
TOPLEVEL_LANG ?= vhdl
WAVES ?= 1

# Original JOP repository (for VHDL reference sources)
JOP_HOME ?= /home/peter/git/jop
ORIG_VHDL_DIR = $(JOP_HOME)/vhdl/core

# Local VHDL testbenches (hand-written)
VHDL_TB_DIR = vhdl
# Generated VHDL files (copied from SpinalHDL for testing)
GENERATED_DIR = generated
SPINALHDL_GEN_DIR = ../../spinalhdl/generated
TEST_VECTORS_DIR = ../test-vectors

# VHDL source files (will be populated based on module)
VHDL_SOURCES =

# Test module
MODULE ?= tests.test_example

# Top-level entity
TOPLEVEL ?= example

# Python search path
export PYTHONPATH := $(PWD):$(PYTHONPATH)

# GHDL specific flags
GHDL_ARGS = --std=08 --ieee=synopsys -frelaxed

# Waveform configuration (for runtime, not compilation)
ifeq ($(WAVES),1)
    SIM_ARGS += --wave=waveforms/$(TOPLEVEL).ghw
endif

# Include cocotb makefiles
include $(shell cocotb-config --makefiles)/Makefile.sim

# Phony targets
.PHONY: clean test_all \
	test_mul test_shift test_fetch test_decode test_stack test_bcfetch test_bcfetch_ref \
	test_core_alu test_core_shift test_core_loadstore test_core_branch test_core_stack \
	test_core_regstore test_core_regload test_core_store test_core_load test_core_loadopd \
	test_core_mmu test_core_control test_core_multiplier test_jvm_sequences \
	test_method_cache test_main_memory_sim test_interrupt test_jop_simulator \
	help

# Clean build artifacts
clean::
	@rm -rf sim_build
	@rm -rf waveforms
	@rm -rf __pycache__
	@rm -rf .pytest_cache
	@rm -f results.xml

# Test all VHDL reference modules
test_all:
	@echo "Running all module tests..."
	$(MAKE) test_mul
	$(MAKE) test_shift
	$(MAKE) test_fetch
	$(MAKE) test_decode
	$(MAKE) test_stack
	$(MAKE) test_bcfetch

# ==========================================================================
# VHDL Reference Tests (from JOP_HOME)
# ==========================================================================

# Test mul.vhd (multiplier)
test_mul:
	@echo "Testing mul.vhd (bit-serial multiplier)..."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	@cp -f $(ORIG_VHDL_DIR)/mul.vhd $(VHDL_TB_DIR)/mul.vhd
	$(MAKE) VHDL_SOURCES="$(VHDL_TB_DIR)/mul.vhd" TOPLEVEL=mul MODULE=tests.test_mul

# Test shift.vhd (barrel shifter)
test_shift:
	@echo "Testing shift.vhd (barrel shifter)..."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	@cp -f $(ORIG_VHDL_DIR)/shift.vhd $(VHDL_TB_DIR)/shift.vhd
	$(MAKE) VHDL_SOURCES="$(VHDL_TB_DIR)/shift.vhd" TOPLEVEL=shift MODULE=tests.test_shift

# ==========================================================================
# Hand-written VHDL Testbench Tests
# ==========================================================================

# Test fetch.vhd (microcode ROM fetch stage)
# Uses fetch_tb.vhd wrapper which includes integrated ROM for testing
test_fetch:
	@echo "Testing fetch.vhd (microcode ROM fetch stage)..."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	$(MAKE) VHDL_SOURCES="$(VHDL_TB_DIR)/fetch_tb.vhd" TOPLEVEL=fetch_tb MODULE=tests.test_fetch

# Test decode.vhd (microcode instruction decoder)
# Uses decode_tb.vhd wrapper which includes embedded configuration constants
# and exposes mem_in record fields as individual ports
test_decode:
	@echo "Testing decode.vhd (microcode instruction decoder)..."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	$(MAKE) VHDL_SOURCES="$(VHDL_TB_DIR)/decode_tb.vhd" TOPLEVEL=decode_tb MODULE=tests.test_decode

# Test stack.vhd (stack/execute stage)
# Uses stack_tb.vhd wrapper which includes:
# - Embedded shift component (barrel shifter)
# - Embedded dual-port RAM for stack storage
# - Debug outputs for internal state visibility
test_stack:
	@echo "Testing stack.vhd (stack/execute stage)..."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	$(MAKE) VHDL_SOURCES="$(VHDL_TB_DIR)/stack_tb.vhd" TOPLEVEL=stack_tb MODULE=tests.test_stack

# Test bcfetch.vhd (bytecode fetch stage)
# Uses bcfetch_tb.vhd wrapper which includes:
# - Embedded JBC RAM (bytecode cache)
# - Jump table (jtbl) from asm/generated
# - IRQ record fields exposed as individual ports
# - Debug outputs for internal state visibility
JTBL_DIR = ../../asm/generated

test_bcfetch:
	@echo "Testing bcfetch.vhd (bytecode fetch stage - SpinalHDL testbench)..."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	@if [ ! -f $(JTBL_DIR)/jtbl.vhd ]; then \
		echo "ERROR: jtbl.vhd not found. Run 'make' in asm/ directory first"; \
		exit 1; \
	fi
	$(MAKE) VHDL_SOURCES="$(JTBL_DIR)/jtbl.vhd $(VHDL_TB_DIR)/bcfetch_tb.vhd" TOPLEVEL=bcfetch_tb MODULE=tests.test_bcfetch

# Test bcfetch reference implementation
# Uses bcfetch_ref_tb.vhd which replicates the original bcfetch.vhd logic
# with embedded packages and exposed record fields for CocoTB testing
test_bcfetch_ref:
	@echo "Testing bcfetch.vhd (bytecode fetch stage - Reference testbench)..."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	@if [ ! -f $(JTBL_DIR)/jtbl.vhd ]; then \
		echo "ERROR: jtbl.vhd not found. Run 'make' in asm/ directory first"; \
		exit 1; \
	fi
	$(MAKE) VHDL_SOURCES="$(JTBL_DIR)/jtbl.vhd $(VHDL_TB_DIR)/bcfetch_ref_tb.vhd" TOPLEVEL=bcfetch_ref_tb MODULE=tests.test_bcfetch

# ==========================================================================
# SpinalHDL Pipeline Tests (generated from JopPipelineTestRom)
#
# Generate VHDL: sbt "runMain jop.JopPipeline<Name>TestTbVhdl"
# ==========================================================================

test_core_alu:
	@echo "Testing pipeline ALU operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineAluTestTb.vhd $(GENERATED_DIR)/JopPipelineAluTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineAluTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineAluTestTb.vhd" TOPLEVEL=joppipelinealutesttb MODULE=tests.test_core_alu

test_core_shift:
	@echo "Testing pipeline shift operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineShiftTestTb.vhd $(GENERATED_DIR)/JopPipelineShiftTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineShiftTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineShiftTestTb.vhd" TOPLEVEL=joppipelineshifttesttb MODULE=tests.test_core_shift

test_core_loadstore:
	@echo "Testing pipeline load/store operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineLoadStoreTestTb.vhd $(GENERATED_DIR)/JopPipelineLoadStoreTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineLoadStoreTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineLoadStoreTestTb.vhd" TOPLEVEL=joppipelineloadstoretesttb MODULE=tests.test_core_loadstore

test_core_branch:
	@echo "Testing pipeline branch operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineBranchTestTb.vhd $(GENERATED_DIR)/JopPipelineBranchTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineBranchTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineBranchTestTb.vhd" TOPLEVEL=joppipelinebranchtesttb MODULE=tests.test_core_branch

test_core_stack:
	@echo "Testing pipeline stack operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineStackTestTb.vhd $(GENERATED_DIR)/JopPipelineStackTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineStackTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineStackTestTb.vhd" TOPLEVEL=joppipelinestacktesttb MODULE=tests.test_core_stack

test_core_regstore:
	@echo "Testing pipeline register store operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineRegStoreTestTb.vhd $(GENERATED_DIR)/JopPipelineRegStoreTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineRegStoreTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineRegStoreTestTb.vhd" TOPLEVEL=joppipelineregstoretesttb MODULE=tests.test_core_regstore

test_core_regload:
	@echo "Testing pipeline register load operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineRegLoadTestTb.vhd $(GENERATED_DIR)/JopPipelineRegLoadTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineRegLoadTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineRegLoadTestTb.vhd" TOPLEVEL=joppipelineregloadtesttb MODULE=tests.test_core_regload

test_core_store:
	@echo "Testing pipeline store operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineStoreTestTb.vhd $(GENERATED_DIR)/JopPipelineStoreTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineStoreTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineStoreTestTb.vhd" TOPLEVEL=joppipelinestoretesttb MODULE=tests.test_core_store

test_core_load:
	@echo "Testing pipeline load operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineLoadTestTb.vhd $(GENERATED_DIR)/JopPipelineLoadTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineLoadTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineLoadTestTb.vhd" TOPLEVEL=joppipelineloadtesttb MODULE=tests.test_core_load

test_core_loadopd:
	@echo "Testing pipeline load operand operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineLoadOpdTestTb.vhd $(GENERATED_DIR)/JopPipelineLoadOpdTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineLoadOpdTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineLoadOpdTestTb.vhd" TOPLEVEL=joppipelineloadopdtesttb MODULE=tests.test_core_loadopd

test_core_mmu:
	@echo "Testing pipeline MMU operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineMmuTestTb.vhd $(GENERATED_DIR)/JopPipelineMmuTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineMmuTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineMmuTestTb.vhd" TOPLEVEL=joppipelinemmutesttb MODULE=tests.test_core_mmu

test_core_control:
	@echo "Testing pipeline control operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineControlTestTb.vhd $(GENERATED_DIR)/JopPipelineControlTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineControlTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineControlTestTb.vhd" TOPLEVEL=joppipelinecontroltesttb MODULE=tests.test_core_control

test_core_multiplier:
	@echo "Testing pipeline multiplier integration (stmul/ldmul)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineMultiplierTestTb.vhd $(GENERATED_DIR)/JopPipelineMultiplierTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineMultiplierTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineMultiplierTestTb.vhd" TOPLEVEL=joppipelinemultipliertesttb MODULE=tests.test_core_multiplier

test_jvm_sequences:
	@echo "Testing pipeline JVM instruction sequences..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopPipelineJvmSequencesTestTb.vhd $(GENERATED_DIR)/JopPipelineJvmSequencesTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopPipelineJvmSequencesTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopPipelineJvmSequencesTestTb.vhd" TOPLEVEL=joppipelinejvmsequencestesttb MODULE=tests.test_jvm_sequences

# ==========================================================================
# SpinalHDL Component Tests
# ==========================================================================

# Test MethodCache (method cache tag lookup)
test_method_cache:
	@echo "Testing MethodCache (method cache tag lookup)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/MethodCacheTb.vhd $(GENERATED_DIR)/MethodCacheTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.memory.MethodCacheTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/MethodCacheTb.vhd" TOPLEVEL=methodcachetb MODULE=tests.test_method_cache

# Test MainMemorySim (simulated main memory)
test_main_memory_sim:
	@echo "Testing MainMemorySim (simulated main memory)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/MainMemorySimTb.vhd $(GENERATED_DIR)/MainMemorySimTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.memory.MainMemorySimTb\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/MainMemorySimTb.vhd" TOPLEVEL=mainmemorysimtb MODULE=tests.test_main_memory_sim

# Test Interrupt/Exception handling
test_interrupt:
	@echo "Testing Interrupt/Exception handling..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/InterruptTestTb.vhd $(GENERATED_DIR)/InterruptTestTb.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.pipeline.InterruptTestTb\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/InterruptTestTb.vhd" TOPLEVEL=interrupttesttb MODULE=tests.test_interrupt

# Test JOP Simulator (full system with HelloWorld.jop)
# Runs the Smallest/HelloWorld.jop program and logs:
# - Bytecode execution
# - UART output ("Hello World!")
# - Watchdog toggles
test_jop_simulator:
	@echo "Testing JOP Simulator with HelloWorld.jop..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopSimulator.vhd $(GENERATED_DIR)/JopSimulator.vhd || \
		(echo "ERROR: Run 'sbt \"runMain jop.JopSimulatorTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopSimulator.vhd" TOPLEVEL=jopsimulator MODULE=tests.test_jop_simulator

# ==========================================================================
# Help
# ==========================================================================

help:
	@echo "JOP CocoTB Test Makefile"
	@echo ""
	@echo "VHDL Reference Tests:"
	@echo "  make test_mul             - Test mul.vhd (bit-serial multiplier)"
	@echo "  make test_shift           - Test shift.vhd (barrel shifter)"
	@echo "  make test_fetch           - Test fetch.vhd (microcode ROM fetch stage)"
	@echo "  make test_decode          - Test decode.vhd (microcode instruction decoder)"
	@echo "  make test_stack           - Test stack.vhd (stack/execute stage)"
	@echo "  make test_bcfetch         - Test bcfetch.vhd (bytecode fetch stage)"
	@echo ""
	@echo "SpinalHDL Pipeline Tests (generate first: sbt \"runMain jop.JopPipeline<Name>TestTbVhdl\"):"
	@echo "  make test_core_alu        - ALU operations"
	@echo "  make test_core_shift      - Shift operations"
	@echo "  make test_core_loadstore  - Load/store operations"
	@echo "  make test_core_branch     - Branch operations"
	@echo "  make test_core_stack      - Stack operations"
	@echo "  make test_core_regstore   - Register store operations"
	@echo "  make test_core_regload    - Register load operations"
	@echo "  make test_core_store      - Store operations"
	@echo "  make test_core_load       - Load operations"
	@echo "  make test_core_loadopd    - Load operand operations"
	@echo "  make test_core_mmu        - MMU operations"
	@echo "  make test_core_control    - Control operations"
	@echo "  make test_core_multiplier - Multiplier integration"
	@echo "  make test_jvm_sequences   - JVM instruction sequences"
	@echo ""
	@echo "SpinalHDL Component Tests:"
	@echo "  make test_method_cache    - MethodCache tag lookup"
	@echo "  make test_main_memory_sim - MainMemorySim"
	@echo "  make test_interrupt       - Interrupt/exception handling"
	@echo "  make test_jop_simulator   - Full system (HelloWorld.jop)"
	@echo ""
	@echo "Utilities:"
	@echo "  make test_all             - Run all VHDL reference tests"
	@echo "  make clean                - Clean build artifacts"
	@echo ""
	@echo "Variables:"
	@echo "  JOP_HOME=/path/to/jop    - Original JOP repository (default: /home/peter/git/jop)"
	@echo "  SIM=ghdl                 - Simulator (default: ghdl)"
	@echo "  WAVES=1                  - Enable waveform generation (default: 1)"
