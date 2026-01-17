# CocoTB Makefile for JOP core testing

# Defaults
SIM ?= ghdl
TOPLEVEL_LANG ?= vhdl
WAVES ?= 1

# Directories (avoid VHDL_SOURCES_* prefix as CocoTB treats those as source lists)
ORIG_VHDL_DIR = ../../original/vhdl/core
# Local VHDL testbenches (hand-written)
VHDL_TB_DIR = vhdl
# Generated VHDL files (copied from SpinalHDL for testing)
GENERATED_DIR = generated
SPINALHDL_GEN_DIR = ../../core/spinalhdl/generated
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
.PHONY: clean test_all test_mul test_shift test_fetch test_decode test_stack test_stack_spinal test_bcfetch test_core test_core_alu test_core_shift test_core_loadstore test_core_branch test_core_stack test_core_regstore test_core_regload test_core_store test_core_load test_core_loadopd test_core_mmu test_core_control test_method_cache test_memory_controller help

# Clean build artifacts
clean::
	@rm -rf sim_build
	@rm -rf waveforms
	@rm -rf __pycache__
	@rm -rf .pytest_cache
	@rm -f results.xml

# Test all modules
test_all:
	@echo "Running all module tests..."
	$(MAKE) test_mul
	$(MAKE) test_shift
	$(MAKE) test_fetch
	$(MAKE) test_decode
	$(MAKE) test_stack
	$(MAKE) test_bcfetch

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

# Test SpinalHDL-generated StackStageTb (legacy - generator no longer exists)
# Uses SpinalHDL-generated VHDL with same interface as stack_tb.vhd
# Note: Use lowercase toplevel name for GHDL compatibility (VHDL case insensitive)
# Note: StackStageTb.vhd must be manually placed in vhdl/ directory if needed
test_stack_spinal:
	@echo "Testing SpinalHDL StackStageTb..."
	@echo "WARNING: This target uses a legacy file. Use test_stack instead."
	@mkdir -p waveforms $(VHDL_TB_DIR)
	$(MAKE) VHDL_SOURCES="$(VHDL_TB_DIR)/StackStageTb.vhd" TOPLEVEL=stackstagetb MODULE=tests.test_stack

# Test JopCore (integrated Fetch → Decode → Stack pipeline)
# Uses SpinalHDL-generated JopCoreTb
test_core:
	@echo "Testing JopCore (Fetch → Decode → Stack pipeline)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@echo "Copying JopCoreTb.vhd from SpinalHDL generated directory..."
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreTb.vhd $(GENERATED_DIR)/JopCoreTb.vhd || \
		(echo "ERROR: JopCoreTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreTb.vhd" TOPLEVEL=jopcoretb MODULE=tests.test_core

# Test JopCore with ALU operations (custom ROM)
# Uses SpinalHDL-generated JopCoreAluTestTb with ALU instruction ROM
test_core_alu:
	@echo "Testing JopCore ALU operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@echo "Copying JopCoreAluTestTb.vhd from SpinalHDL generated directory..."
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreAluTestTb.vhd $(GENERATED_DIR)/JopCoreAluTestTb.vhd || \
		(echo "ERROR: JopCoreAluTestTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreAluTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreAluTestTb.vhd" TOPLEVEL=jopcorealutesttb MODULE=tests.test_core_alu

# Test JopCore with shift operations (custom ROM)
# Uses SpinalHDL-generated JopCoreShiftTestTb with shift instruction ROM
test_core_shift:
	@echo "Testing JopCore shift operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@echo "Copying JopCoreShiftTestTb.vhd from SpinalHDL generated directory..."
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreShiftTestTb.vhd $(GENERATED_DIR)/JopCoreShiftTestTb.vhd || \
		(echo "ERROR: JopCoreShiftTestTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreShiftTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreShiftTestTb.vhd" TOPLEVEL=jopcoreshifttesttb MODULE=tests.test_core_shift

# Test JopCore with load/store operations (custom ROM)
# Uses SpinalHDL-generated JopCoreLoadStoreTestTb with load/store instruction ROM
test_core_loadstore:
	@echo "Testing JopCore load/store operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@echo "Copying JopCoreLoadStoreTestTb.vhd from SpinalHDL generated directory..."
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreLoadStoreTestTb.vhd $(GENERATED_DIR)/JopCoreLoadStoreTestTb.vhd || \
		(echo "ERROR: JopCoreLoadStoreTestTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreLoadStoreTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreLoadStoreTestTb.vhd" TOPLEVEL=jopcoreloadstoretesttb MODULE=tests.test_core_loadstore

# Test JopCore with branch operations (custom ROM)
# Uses SpinalHDL-generated JopCoreBranchTestTb with branch instruction ROM
test_core_branch:
	@echo "Testing JopCore branch operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@echo "Copying JopCoreBranchTestTb.vhd from SpinalHDL generated directory..."
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreBranchTestTb.vhd $(GENERATED_DIR)/JopCoreBranchTestTb.vhd || \
		(echo "ERROR: JopCoreBranchTestTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreBranchTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreBranchTestTb.vhd" TOPLEVEL=jopcorebranchtesttb MODULE=tests.test_core_branch

# Test JopCore with stack operations (custom ROM)
test_core_stack:
	@echo "Testing JopCore stack operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreStackTestTb.vhd $(GENERATED_DIR)/JopCoreStackTestTb.vhd || \
		(echo "ERROR: JopCoreStackTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreStackTestTb.vhd" TOPLEVEL=jopcorestacktesttb MODULE=tests.test_core_stack

# Test JopCore with register store operations (custom ROM)
test_core_regstore:
	@echo "Testing JopCore register store operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreRegStoreTestTb.vhd $(GENERATED_DIR)/JopCoreRegStoreTestTb.vhd || \
		(echo "ERROR: JopCoreRegStoreTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreRegStoreTestTb.vhd" TOPLEVEL=jopcoreregstoretesttb MODULE=tests.test_core_regstore

# Test JopCore with register load operations (custom ROM)
test_core_regload:
	@echo "Testing JopCore register load operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreRegLoadTestTb.vhd $(GENERATED_DIR)/JopCoreRegLoadTestTb.vhd || \
		(echo "ERROR: JopCoreRegLoadTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreRegLoadTestTb.vhd" TOPLEVEL=jopcoreregloadtesttb MODULE=tests.test_core_regload

# Test JopCore with store operations (custom ROM)
test_core_store:
	@echo "Testing JopCore store operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreStoreTestTb.vhd $(GENERATED_DIR)/JopCoreStoreTestTb.vhd || \
		(echo "ERROR: JopCoreStoreTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreStoreTestTb.vhd" TOPLEVEL=jopcorestoretesttb MODULE=tests.test_core_store

# Test JopCore with load operations (custom ROM)
test_core_load:
	@echo "Testing JopCore load operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreLoadTestTb.vhd $(GENERATED_DIR)/JopCoreLoadTestTb.vhd || \
		(echo "ERROR: JopCoreLoadTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreLoadTestTb.vhd" TOPLEVEL=jopcoreloadtesttb MODULE=tests.test_core_load

# Test JopCore with load operand operations (custom ROM)
test_core_loadopd:
	@echo "Testing JopCore load operand operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreLoadOpdTestTb.vhd $(GENERATED_DIR)/JopCoreLoadOpdTestTb.vhd || \
		(echo "ERROR: JopCoreLoadOpdTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreLoadOpdTestTb.vhd" TOPLEVEL=jopcoreloadopdtesttb MODULE=tests.test_core_loadopd

# Test JopCore with MMU operations (custom ROM)
test_core_mmu:
	@echo "Testing JopCore MMU operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreMmuTestTb.vhd $(GENERATED_DIR)/JopCoreMmuTestTb.vhd || \
		(echo "ERROR: JopCoreMmuTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreMmuTestTb.vhd" TOPLEVEL=jopcoremmutesttb MODULE=tests.test_core_mmu

# Test JopCore with control operations (custom ROM)
test_core_control:
	@echo "Testing JopCore control operations (custom ROM)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreControlTestTb.vhd $(GENERATED_DIR)/JopCoreControlTestTb.vhd || \
		(echo "ERROR: JopCoreControlTestTb.vhd not found." && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreControlTestTb.vhd" TOPLEVEL=jopcorecontroltesttb MODULE=tests.test_core_control

# Test JopCore with multiplier operations (stmul/ldmul)
test_core_multiplier:
	@echo "Testing JopCore multiplier integration (stmul/ldmul)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreMultiplierTestTb.vhd $(GENERATED_DIR)/JopCoreMultiplierTestTb.vhd || \
		(echo "ERROR: JopCoreMultiplierTestTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreMultiplierTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreMultiplierTestTb.vhd" TOPLEVEL=jopcoremultipliertesttb MODULE=tests.test_core_multiplier

# Test JopCore with JVM instruction sequences (Phase 3.1)
test_jvm_sequences:
	@echo "Testing JopCore JVM instruction sequences (Phase 3.1)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreJvmSequencesTestTb.vhd $(GENERATED_DIR)/JopCoreJvmSequencesTestTb.vhd || \
		(echo "ERROR: JopCoreJvmSequencesTestTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreJvmSequencesTestTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreJvmSequencesTestTb.vhd" TOPLEVEL=jopcorejvmsequencestesttb MODULE=tests.test_jvm_sequences

# Test JopCoreWithBytecode integration (BytecodeFetch + Microcode Pipeline)
test_core_bytecode:
	@echo "Testing JopCoreWithBytecode integration..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreWithBytecodeTb.vhd $(GENERATED_DIR)/JopCoreWithBytecodeTb.vhd || \
		(echo "ERROR: JopCoreWithBytecodeTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreWithBytecodeTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/JopCoreWithBytecodeTb.vhd" TOPLEVEL=jopcorewithbytecodetb MODULE=tests.test_core_with_bytecode

# Test MemorySubsystem (integrated memory subsystem with JOP data)
test_memory_subsystem:
	@echo "Testing MemorySubsystem (integrated memory subsystem)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/MemorySubsystemTb.vhd $(GENERATED_DIR)/MemorySubsystemTb.vhd || \
		(echo "ERROR: MemorySubsystemTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.memory.MemorySubsystemTb\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/MemorySubsystemTb.vhd" TOPLEVEL=memorysubsystemtb MODULE=tests.test_memory_subsystem

# Test MainMemorySim (simulated main memory)
test_main_memory_sim:
	@echo "Testing MainMemorySim (simulated main memory)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/MainMemorySimTb.vhd $(GENERATED_DIR)/MainMemorySimTb.vhd || \
		(echo "ERROR: MainMemorySimTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.memory.MainMemorySimTb\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/MainMemorySimTb.vhd" TOPLEVEL=mainmemorysimtb MODULE=tests.test_main_memory_sim

# Test MethodCache (method cache tag lookup)
test_method_cache:
	@echo "Testing MethodCache (method cache tag lookup)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/MethodCacheTb.vhd $(GENERATED_DIR)/MethodCacheTb.vhd || \
		(echo "ERROR: MethodCacheTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.memory.MethodCacheTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/MethodCacheTb.vhd" TOPLEVEL=methodcachetb MODULE=tests.test_method_cache

# Test MemoryController (automatic cache miss handling)
test_memory_controller:
	@echo "Testing MemoryController (automatic cache miss handling)..."
	@mkdir -p waveforms $(GENERATED_DIR)
	@cp -f $(SPINALHDL_GEN_DIR)/MemoryControllerTb.vhd $(GENERATED_DIR)/MemoryControllerTb.vhd || \
		(echo "ERROR: MemoryControllerTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.memory.MemoryControllerTb\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(GENERATED_DIR)/MemoryControllerTb.vhd" TOPLEVEL=memorycontrollertb MODULE=tests.test_memory_controller

# Help
help:
	@echo "JOP CocoTB Test Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  make                 - Run default test"
	@echo "  make test_all        - Run all tests"
	@echo "  make test_mul        - Test mul.vhd (bit-serial multiplier)"
	@echo "  make test_shift      - Test shift.vhd (barrel shifter)"
	@echo "  make test_fetch      - Test fetch.vhd (microcode ROM fetch stage)"
	@echo "  make test_decode     - Test decode.vhd (microcode instruction decoder)"
	@echo "  make test_stack      - Test stack.vhd (stack/execute stage)"
	@echo "  make test_bcfetch    - Test bcfetch.vhd (bytecode fetch stage)"
	@echo "  make clean           - Clean build artifacts"
	@echo ""
	@echo "Variables:"
	@echo "  SIM=ghdl            - Simulator (default: ghdl)"
	@echo "  WAVES=1             - Enable waveform generation (default: 1)"
	@echo "  MODULE=tests.test_X - Test module to run"
	@echo "  TOPLEVEL=X          - Top-level VHDL entity"
	@echo ""
	@echo "Example:"
	@echo "  make TOPLEVEL=bytecode_fetch MODULE=tests.test_fetch"
