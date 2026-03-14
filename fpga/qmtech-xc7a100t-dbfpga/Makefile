# QMTECH XC7A100T + DB_FPGA V5 build targets
#
# Prerequisites:
#   - Vivado in PATH
#   - openFPGALoader installed
#   - sbt for Verilog generation
#
# Quick start:
#   make uart-echo        # Full flow: generate + create + build + program

REPO_ROOT := $(shell cd ../.. && pwd)
VIVADO := /opt/xilinx/2025.2/Vivado/bin/vivado
VIVADO_DIR := vivado
BUILD_DIR := $(VIVADO_DIR)/build
GENERATED := $(REPO_ROOT)/spinalhdl/generated

# DirtyJTAG programming
CABLE := dirtyJtag

.PHONY: generate-uart-echo create-uart-echo build-uart-echo program-uart-echo uart-echo test-uart-echo

# === UART Echo Test ===

generate-uart-echo:
	cd $(REPO_ROOT) && sbt "runMain jop.system.UartEchoTopVerilog"

create-uart-echo: generate-uart-echo
	cd $(REPO_ROOT)/fpga/qmtech-xc7a100t-dbfpga && \
	$(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_uart_echo_project.tcl

build-uart-echo: create-uart-echo
	cd $(REPO_ROOT)/fpga/qmtech-xc7a100t-dbfpga && \
	$(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/build_uart_echo_bitstream.tcl

program-uart-echo:
	@echo "Programming via DirtyJTAG..."
	sudo openFPGALoader -c $(CABLE) \
		$(BUILD_DIR)/uart_echo/uart_echo.runs/impl_1/UartEchoTop.bit

# Full flow
uart-echo: build-uart-echo program-uart-echo
	@echo "=== UART echo programmed ==="
	@echo "Test with: python3 $(REPO_ROOT)/fpga/scripts/uart_echo_test.py"

# Interactive test
test-uart-echo:
	python3 $(REPO_ROOT)/fpga/scripts/uart_echo_test.py
