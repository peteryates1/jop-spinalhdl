# CocoTB Makefile for JOP core testing

# Defaults
SIM ?= ghdl
TOPLEVEL_LANG ?= vhdl
WAVES ?= 1

# Directories
VHDL_SOURCES_DIR = ../../original/vhdl/core
SPINALHDL_VHDL_DIR = ../../core/spinalhdl/generated
TEST_VECTORS_DIR = ../test-vectors

# VHDL source files (will be populated based on module)
VHDL_SOURCES =

# Test module
MODULE ?= tests.test_example

# Top-level entity
TOPLEVEL ?= example

# Python search path
export PYTHONPATH := $(PWD):$(PYTHONPATH)

# Waveform configuration
ifeq ($(WAVES),1)
    EXTRA_ARGS += --wave=waveforms/$(TOPLEVEL).ghw
endif

# GHDL specific flags
GHDL_ARGS = --std=08 --ieee=synopsys -frelaxed

# Include cocotb makefiles
include $(shell cocotb-config --makefiles)/Makefile.sim

# Phony targets
.PHONY: clean test_all help

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
	# Add test targets here as modules are implemented

# Help
help:
	@echo "JOP CocoTB Test Makefile"
	@echo ""
	@echo "Targets:"
	@echo "  make                 - Run default test"
	@echo "  make test_all        - Run all tests"
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
