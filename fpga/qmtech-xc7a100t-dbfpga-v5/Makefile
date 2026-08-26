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

# The Vivado flow lives in two shared scripts, driven by environment. Six
# near-identical create/build scripts for the three bring-up jigs below became
# six invocations of these; see their headers for what the copies had drifted
# into. Paths are made absolute here because the scripts normalise relative to
# Vivado's working directory, which is not always this one.
PART        := xc7a100tfgg676-2
CREATE_TCL  := $(REPO_ROOT)/fpga/scripts/vivado_create_project.tcl
BUILD_TCL   := $(REPO_ROOT)/fpga/scripts/vivado_build_project.tcl
ABS_BUILD   := $(CURDIR)/$(BUILD_DIR)
CONSTRAINTS := $(CURDIR)/$(VIVADO_DIR)/constraints
BOARD_RTL   := $(CURDIR)/$(VIVADO_DIR)/rtl

# DirtyJTAG programming (RP2040 on DB_FPGA V5)
#
# THERE ARE TWO dirtyJtag PROBES ON THIS HOST -- this board's RP2040 and the
# Wukong's Pico 2 W -- and a bare `-c dirtyJtag` takes whichever enumerated
# FIRST, which is the Wukong. So every program target here was capable of
# silently programming the WRONG BOARD.
#
# This used to be an empty `BUSDEV ?=` with a comment telling the reader to
# pass the selection by hand:
#   make ddr3-program BUSDEV="--busdev-num $$(... --busdev xc7a100t)"
# A hazard that is only avoided when someone remembers to type the workaround
# is not avoided. Resolved by SERIAL now, as on the Wukong. Needs the PATCHED
# openFPGALoader in /usr/local/bin; a bogus `--busdev-num 099:099` must FAIL
# for the selection to mean anything.
CABLE  := dirtyJtag
BUSDEV ?= --busdev-num $(shell $(REPO_ROOT)/fpga/scripts/jtag_probe_map --busdev xc7a100t)

# UART via the RP2040's CDC0 (DB_FPGA V5 UART bridge). THE NUMBER MOVES: it is
# whichever ttyACM this board's Pico enumerated as, and every other CMSIS-DAP /
# dirtyJtag probe on the host shifts it. With a second dirtyJtag attached it was
# ttyACM4, not ttyACM1. Re-map before use:
#   fpga/scripts/usb_serial_map | grep <this probe's serial>
# Was /dev/ttyACM1, which is whichever Pico enumerated first -- with the Wukong
# also attached that number has pointed at the WRONG BOARD's Pico. Resolved from
# this board's Pico serial instead.
#
# The console is the FIRST CDC (if01), verified on hardware 2026-08-22: it
# carries the 0xAA serial-boot ready pattern at 2 Mbaud and completes a
# download, while if03 is silent at every baud. An earlier revision guessed
# if03 from the old /dev/ttyACM1 default -- that guess was wrong, and ttyACM1
# was simply the second node of whichever Pico enumerated first.
UART_DEV ?= $(shell $(REPO_ROOT)/fpga/scripts/usb_serial_map --by-id xc7a100t-pico-0 2>/dev/null \
              || echo /dev/serial/by-id/usb-Jean_THOMAS_Pico_Dirty_JTAG_E662C876C3506A26-if01)
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
	$(VIVADO_ENV) && JOP_PROJ=uart_echo JOP_PART=$(PART) JOP_TOP=UartEchoTop \
	  JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_RTL=$(REPO_ROOT)/spinalhdl/generated/UartEchoTop.v \
	  JOP_XDC=$(CONSTRAINTS)/uart_echo.xdc \
	  JOP_GEN_HINT="run: make generate-uart-echo" \
	  $(VIVADO) -mode batch -source $(CREATE_TCL)

build-uart-echo: create-uart-echo
	$(VIVADO_ENV) && JOP_PROJ=uart_echo JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_BIT=UartEchoTop.bit \
	  $(VIVADO) -mode batch -source $(BUILD_TCL)

program-uart-echo:
	sudo openFPGALoader -c $(CABLE) $(BUSDEV) \
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
	$(VIVADO_ENV) && JOP_PROJ=uart_loopback JOP_PART=$(PART) JOP_TOP=UartLoopback \
	  JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_RTL=$(BOARD_RTL)/UartLoopback.v \
	  JOP_XDC=$(CONSTRAINTS)/uart_loopback.xdc \
	  $(VIVADO) -mode batch -source $(CREATE_TCL)

loopback-build: loopback-create
	$(VIVADO_ENV) && JOP_PROJ=uart_loopback JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_BIT=UartLoopback.bit \
	  $(VIVADO) -mode batch -source $(BUILD_TCL)

loopback-program:
	sudo openFPGALoader -c $(CABLE) $(BUSDEV) \
		$(BUILD_DIR)/uart_loopback/uart_loopback.runs/impl_1/UartLoopback.bit

uart-loopback: loopback-build loopback-program
	@echo "=== UART loopback programmed — test with: python3 ../../fpga/scripts/uart_loopback.py ==="

# === UART TX generator (FPGA independently drives 0xAA on A5 + P25) ===
# Tests FPGA→GPIO RX path without needing GPIO TX to work.
# With UART0 firmware: ttyACM0 should receive 0xAA from A5→GPIO1 (known good ref)
# With UART1 firmware: ttyACM0 should receive 0xAA from P25→GPIO5 (tests J2 path)

txgen-create:
	$(VIVADO_ENV) && JOP_PROJ=uart_txgen JOP_PART=$(PART) JOP_TOP=UartTxGen \
	  JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_RTL=$(BOARD_RTL)/UartTxGen.v \
	  JOP_XDC=$(CONSTRAINTS)/uart_txgen.xdc \
	  $(VIVADO) -mode batch -source $(CREATE_TCL)

txgen-build: txgen-create
	$(VIVADO_ENV) && JOP_PROJ=uart_txgen JOP_BUILD_DIR=$(ABS_BUILD) \
	  JOP_BIT=UartTxGen.bit \
	  $(VIVADO) -mode batch -source $(BUILD_TCL)

txgen-program:
	sudo openFPGALoader -c $(CABLE) $(BUSDEV) \
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
