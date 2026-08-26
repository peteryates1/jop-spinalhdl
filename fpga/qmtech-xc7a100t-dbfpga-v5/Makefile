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
PROJECT_ROOT = ../..
VIVADO_DIR := vivado
BUILD_DIR := $(VIVADO_DIR)/build

# The Vivado flow, the config directory and the console come from ../vivado.mk
# and ../console.mk; the build itself is in fpga/scripts/vivado_*.tcl.
CFG          ?= xc7a100tDbSerial
BOARD_ALIAS   = xc7a100t
CONSOLE_ALIAS = xc7a100t-pico-0

include ../vivado.mk

# CONVERTED to the build tree 2026-08-26. This was the LAST board still
# generating into spinalhdl/generated -- the one place the "everything
# generated lives under build/<config>/" rule was still not true. The JOP flow
# now writes to build/xc7a100tDbSerial/; the three bring-up jigs below stay
# in-tree deliberately, because they are not JopConfig presets and have no
# config directory to belong to (UartEchoTop has its own generator main;
# UartLoopback and UartTxGen are hand-written Verilog).
#
# UART_BAUD := 2000000 is gone with it. console.mk reads the rate the bitstream
# actually recorded; the identical constant on the Wukong turned out to be
# WRONG for two of its flows (status item 87), and this one was never checked.
PART        := xc7a100tfgg676-2
ABS_BUILD   := $(CURDIR)/$(BUILD_DIR)
CONSTRAINTS := $(CURDIR)/$(VIVADO_DIR)/constraints
BOARD_RTL   := $(CURDIR)/$(VIVADO_DIR)/rtl
IP_DIR      := $(CURDIR)/$(VIVADO_DIR)/ip
GEN_RTL     := $(REPO_ROOT)/spinalhdl/generated

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
# The cable name and the busdev selection now come from vivado.mk, which
# resolves the probe from BOARD_ALIAS by serial.

# THE CONSOLE IS THE FIRST CDC (if01), verified on hardware 2026-08-22: it
# carries the 0xAA serial-boot ready pattern and completes a download, while
# if03 is silent at every baud. An earlier revision guessed if03 from an old
# /dev/ttyACM1 default -- that guess was wrong, and ttyACM1 was simply the
# second node of whichever Pico enumerated first. That is what CONSOLE_ALIAS
# xc7a100t-pico-0 names; the ttyACM number itself moves whenever any other
# CMSIS-DAP or dirtyJtag probe is plugged in (it has been ttyACM4).

# Bitstream paths
DDR3_BITSTREAM = $(CFG_DIR)/vivado/build/JopDdr3Top.bit

.PHONY: generate-uart-echo create-uart-echo build-uart-echo program-uart-echo uart-echo test-uart-echo
.PHONY: loopback-create loopback-build loopback-program uart-loopback
.PHONY: ddr3-generate ddr3-create-ip ddr3-build ddr3-program ddr3-monitor ddr3-download ddr3-reset ddr3-redownload


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
	$(MAKE) program-bit BIT_FILE=$(ABS_BUILD)/uart_echo/uart_echo.runs/impl_1/UartEchoTop.bit

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
	$(MAKE) program-bit BIT_FILE=$(ABS_BUILD)/uart_loopback/uart_loopback.runs/impl_1/UartLoopback.bit

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
	$(MAKE) program-bit BIT_FILE=$(ABS_BUILD)/uart_txgen/uart_txgen.runs/impl_1/UartTxGen.bit

uart-txgen: txgen-build txgen-program
	@echo "=== TX generator programmed — python3 ../../fpga/scripts/uart_loopback.py --rx-only ==="

# === JOP DDR3 (xc7a100tDbSerial preset) ===

ddr3-generate: generate

ddr3-create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_ddr3_clk_wiz.tcl
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source $(VIVADO_DIR)/tcl/create_mig.tcl

# The directives are this design's, not a house style: PerformanceOptimized +
# retiming through Explore/ExtraTimingOpt/AggressiveExplore is what closes
# timing here (WNS +0.117 ns). See vivado_build_nonproject.tcl's header for why
# they stayed parameters rather than being normalised away.
ddr3-build: ddr3-generate
	$(VIVADO_ENV) && JOP_TOP=JopDdr3Top JOP_PART=$(PART) \
	  JOP_BUILD_DIR=$(CFG_DIR)/vivado/build \
	  JOP_RTL=$(CFG_DIR)/rtl/JopDdr3Top.v \
	  JOP_IP="$(IP_DIR)/clk_wiz_0/clk_wiz_0.xci $(IP_DIR)/mig_7series_0/mig_7series_0.xci" \
	  JOP_XDC=$(CONSTRAINTS)/xc7a100t_dbv5_base.xdc \
	  JOP_SYNTH_DIRECTIVE=PerformanceOptimized JOP_SYNTH_RETIMING=yes \
	  JOP_OPT_DIRECTIVE=Explore JOP_PLACE_DIRECTIVE=ExtraTimingOpt \
	  JOP_PHYS_OPT_DIRECTIVE=AggressiveExplore JOP_ROUTE_DIRECTIVE=AggressiveExplore \
	  JOP_SUMMARY=$(CFG_DIR)/rtl/JopDdr3Top.summary.txt \
	  $(VIVADO) -mode batch -source $(NP_TCL)

ddr3-program:
	$(MAKE) program-bit BIT_FILE=$(DDR3_BITSTREAM)

ddr3-monitor:
	$(MAKE) monitor

ddr3-download:
	$(MAKE) download

ddr3-reset:
	$(MAKE) reset

ddr3-redownload:
	$(MAKE) redownload

clean:
	rm -rf vivado/build vivado/ip
