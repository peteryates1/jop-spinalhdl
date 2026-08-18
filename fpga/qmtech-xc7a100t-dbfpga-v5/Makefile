# QMTECH XC7A100T Core Board + DB_FPGA V5 build targets
#
# Prerequisites:
#   - Vivado in PATH (set VIVADO below)
#   - openFPGALoader installed
#   - sbt for Verilog generation
#
# Quick start (UART echo board bring-up test):
#   make uart-echo
#
# JOP DDR3 bring-up:
#   make ddr3-create-ip   # generate clk_wiz + MIG IPs (once)
#   make ddr3-build       # generate Verilog + build bitstream
#   make ddr3-program     # program via DirtyJTAG
#   make ddr3-download    # download HelloWorld.jop via ttyACM0
#   make ddr3-monitor     # monitor output on ttyACM0

REPO_ROOT := $(shell cd ../.. && pwd)
VIVADO := /opt/xilinx/2025.2/Vivado/bin/vivado
VIVADO_ENV := export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
VIVADO_DIR := vivado
BUILD_DIR := $(VIVADO_DIR)/build
GENERATED := $(REPO_ROOT)/spinalhdl/generated

# DirtyJTAG programming (RP2040 on DB_FPGA V5)
# There can be MORE THAN ONE dirtyJtag probe on the host, and a bare
# `-c dirtyJtag` then picks whichever it finds first -- i.e. possibly another
# board. Select explicitly:
#   make ddr3-program BUSDEV="--busdev-num $$(fpga/scripts/jtag_probe_map --busdev xc7a100t)"
CABLE := dirtyJtag
BUSDEV ?=

# UART via the RP2040's CDC0 (DB_FPGA V5 UART bridge). THE NUMBER MOVES: it is
# whichever ttyACM this board's Pico enumerated as, and every other CMSIS-DAP /
# dirtyJtag probe on the host shifts it. With a second dirtyJtag attached it was
# ttyACM4, not ttyACM1. Re-map before use:
#   fpga/scripts/usb_serial_map | grep <this probe's serial>
UART_DEV ?= /dev/ttyACM1
UART_BAUD := 2000000

# Bitstream paths
DDR3_BITSTREAM := $(BUILD_DIR)/xc7a100t_ddr3/JopDdr3Top.bit

.PHONY: generate-uart-echo create-uart-echo build-uart-echo program-uart-echo uart-echo test-uart-echo
.PHONY: loopback-create loopback-build loopback-program uart-loopback
.PHONY: ddr3-generate ddr3-create-ip ddr3-build ddr3-program ddr3-monitor ddr3-download


# === UART Echo Test (board bring-up) ===

generate-uart-echo:
	cd $(REPO_ROOT) && sbt "runMain jop.system.UartEchoTopVerilog"

create-uart-echo: generate-uart-echo
	cd $(REPO_ROOT)/fpga/qmtech-xc7a100t-dbfpga-v5 && \
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_uart_echo_project.tcl

build-uart-echo: create-uart-echo
	cd $(REPO_ROOT)/fpga/qmtech-xc7a100t-dbfpga-v5 && \
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/build_uart_echo_bitstream.tcl

program-uart-echo:
	sudo openFPGALoader -c $(CABLE) \
		$(BUILD_DIR)/uart_echo/uart_echo.runs/impl_1/UartEchoTop.bit

uart-echo: build-uart-echo program-uart-echo
	@echo "=== UART echo programmed — test with: make test-uart-echo ==="

test-uart-echo:
	python3 $(REPO_ROOT)/fpga/scripts/uart_echo_test.py

# === UART Loopback (FPGA wires B5 rxd → A5 txd, no logic) ===
# Programs FPGA with a trivial combinational loopback.
# Any byte sent from RP2040 GPIO0 exits immediately on A5 back to GPIO1.
# Run uart_loopback.py to verify the full GPIO0→B5→A5→GPIO1 path.

loopback-create:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_uart_loopback_project.tcl

loopback-build: loopback-create
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/build_uart_loopback_bitstream.tcl

loopback-program:
	sudo openFPGALoader -c $(CABLE) \
		$(BUILD_DIR)/uart_loopback/uart_loopback.runs/impl_1/UartLoopback.bit

uart-loopback: loopback-build loopback-program
	@echo "=== UART loopback programmed — test with: python3 ../../fpga/scripts/uart_loopback.py ==="

# === UART TX generator (FPGA independently drives 0xAA on A5 + P25) ===
# Tests FPGA→GPIO RX path without needing GPIO TX to work.
# With UART0 firmware: ttyACM0 should receive 0xAA from A5→GPIO1 (known good ref)
# With UART1 firmware: ttyACM0 should receive 0xAA from P25→GPIO5 (tests J2 path)

txgen-create:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_uart_txgen_project.tcl

txgen-build: txgen-create
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/build_uart_txgen_bitstream.tcl

txgen-program:
	sudo openFPGALoader -c $(CABLE) \
		$(BUILD_DIR)/uart_txgen/uart_txgen.runs/impl_1/UartTxGen.bit

uart-txgen: txgen-build txgen-program
	@echo "=== TX generator programmed — python3 ../../fpga/scripts/uart_loopback.py --rx-only ==="

# === JOP DDR3 (xc7a100tDbSerial preset) ===

ddr3-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog xc7a100tDbSerial"

ddr3-create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_ddr3_clk_wiz.tcl
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_mig.tcl

ddr3-build: ddr3-generate
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/build_ddr3_bitstream.tcl

ddr3-program:
	sudo openFPGALoader -c $(CABLE) $(BUSDEV) $(DDR3_BITSTREAM)

ddr3-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

ddr3-download:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -e \
	  $(REPO_ROOT)/java/apps/Smallest/HelloWorld.jop \
	  $(UART_DEV) $(UART_BAUD)

clean:
	rm -rf vivado/build vivado/ip
