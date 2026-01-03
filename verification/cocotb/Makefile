# CocoTB Makefile for JOP core testing

# Defaults
SIM ?= ghdl
TOPLEVEL_LANG ?= vhdl
WAVES ?= 1

# Directories (avoid VHDL_SOURCES_* prefix as CocoTB treats those as source lists)
ORIG_VHDL_DIR = ../../original/vhdl/core
# Local copy for CocoTB testing
LOCAL_VHDL_DIR = vhdl
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
.PHONY: clean test_all test_mul test_shift test_fetch test_decode test_stack test_stack_spinal test_core help

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

# Test mul.vhd (multiplier)
test_mul:
	@echo "Testing mul.vhd (bit-serial multiplier)..."
	@mkdir -p waveforms $(LOCAL_VHDL_DIR)
	@cp -f $(ORIG_VHDL_DIR)/mul.vhd $(LOCAL_VHDL_DIR)/mul.vhd
	$(MAKE) VHDL_SOURCES="$(LOCAL_VHDL_DIR)/mul.vhd" TOPLEVEL=mul MODULE=tests.test_mul

# Test shift.vhd (barrel shifter)
test_shift:
	@echo "Testing shift.vhd (barrel shifter)..."
	@mkdir -p waveforms $(LOCAL_VHDL_DIR)
	@cp -f $(ORIG_VHDL_DIR)/shift.vhd $(LOCAL_VHDL_DIR)/shift.vhd
	$(MAKE) VHDL_SOURCES="$(LOCAL_VHDL_DIR)/shift.vhd" TOPLEVEL=shift MODULE=tests.test_shift

# Test fetch.vhd (microcode ROM fetch stage)
# Uses fetch_tb.vhd wrapper which includes integrated ROM for testing
test_fetch:
	@echo "Testing fetch.vhd (microcode ROM fetch stage)..."
	@mkdir -p waveforms $(LOCAL_VHDL_DIR)
	$(MAKE) VHDL_SOURCES="$(LOCAL_VHDL_DIR)/fetch_tb.vhd" TOPLEVEL=fetch_tb MODULE=tests.test_fetch

# Test decode.vhd (microcode instruction decoder)
# Uses decode_tb.vhd wrapper which includes embedded configuration constants
# and exposes mem_in record fields as individual ports
test_decode:
	@echo "Testing decode.vhd (microcode instruction decoder)..."
	@mkdir -p waveforms $(LOCAL_VHDL_DIR)
	$(MAKE) VHDL_SOURCES="$(LOCAL_VHDL_DIR)/decode_tb.vhd" TOPLEVEL=decode_tb MODULE=tests.test_decode

# Test stack.vhd (stack/execute stage)
# Uses stack_tb.vhd wrapper which includes:
# - Embedded shift component (barrel shifter)
# - Embedded dual-port RAM for stack storage
# - Debug outputs for internal state visibility
test_stack:
	@echo "Testing stack.vhd (stack/execute stage)..."
	@mkdir -p waveforms $(LOCAL_VHDL_DIR)
	$(MAKE) VHDL_SOURCES="$(LOCAL_VHDL_DIR)/stack_tb.vhd" TOPLEVEL=stack_tb MODULE=tests.test_stack

# Test SpinalHDL-generated StackStageTb
# Uses SpinalHDL-generated VHDL with same interface as stack_tb.vhd
# Note: Use lowercase toplevel name for GHDL compatibility (VHDL case insensitive)
test_stack_spinal:
	@echo "Testing SpinalHDL StackStageTb..."
	@mkdir -p waveforms $(LOCAL_VHDL_DIR)
	$(MAKE) VHDL_SOURCES="$(LOCAL_VHDL_DIR)/StackStageTb.vhd" TOPLEVEL=stackstagetb MODULE=tests.test_stack

# Test JopCore (integrated Fetch → Decode → Stack pipeline)
# Uses SpinalHDL-generated JopCoreTb
test_core:
	@echo "Testing JopCore (Fetch → Decode → Stack pipeline)..."
	@mkdir -p waveforms $(LOCAL_VHDL_DIR)
	@echo "Copying JopCoreTb.vhd from SpinalHDL generated directory..."
	@cp -f $(SPINALHDL_GEN_DIR)/JopCoreTb.vhd $(LOCAL_VHDL_DIR)/JopCoreTb.vhd || \
		(echo "ERROR: JopCoreTb.vhd not found. Run 'cd ../../core/spinalhdl && sbt \"runMain jop.JopCoreTbVhdl\"' first" && exit 1)
	$(MAKE) VHDL_SOURCES="$(LOCAL_VHDL_DIR)/JopCoreTb.vhd" TOPLEVEL=jopcoretb MODULE=tests.test_core

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
