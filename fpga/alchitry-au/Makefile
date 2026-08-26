# ---------------------------------------------------------------------------
# JOP DDR3 — Alchitry Au V2 (XC7A35T-2FTG256 + DDR3)
#
#   make all       microcode + generate + ips + project + bitstream
#   make run       program + download + monitor
#   make full      all + run
#
# Flash boot (autonomous boot from SPI config flash) — see FLASH BOOT below:
#   make flash-programmer   build and load the UART flash programmer
#   make program-flash      write flash_image.bin over UART
#
# RETIRED 2026-08-26. Three flows and one dead twin went, with 14 of the 25
# Vivado Tcl scripts:
#
#  1. SMP (generate/project/bitstream/program/run-smp). `JopDdr3SmpTopVerilog`
#     was deleted by commit 7258661 on 2026-03-13 and there is no `auSmp`
#     preset to replace it — this board has only auSerial and auMinimal, and an
#     XC7A35T is the smallest part here anyway.
#  2. GC exerciser and 3. trace replayer — superseded as diagnostics; their
#     mains are still live but nothing has run them since the DDR3 bring-up.
#  4. SPI diagnostic, whose four build scripts included two (build_spi_diagnostic
#     and build_spi_diag_minimal) that no target ever referenced, alongside
#     program_exerciser and build_bitstream_flash_launch — also unreferenced.
#
# `Ddr3GcExerciserTop.scala`, `Ddr3TraceReplayerTop.scala` and
# `SpiDiagnosticTop.scala` are now built by nothing. They are left in place
# deliberately: deleting RTL is a separate decision from deleting the
# scaffolding around it.
# ---------------------------------------------------------------------------

SHELL := /usr/bin/env bash

VIVADO_HOME ?= /opt/xilinx/2025.2/Vivado
RUN_VIVADO  := VIVADO_HOME=$(VIVADO_HOME) ./scripts/run_vivado.sh

# Was find_alchitry_ports.sh, which matched on vid:pid 0403:6010 alone -- the
# same id the CYC5000's Arrow blaster uses, so it could return the Arrow's UART.
# usb_serial_map's alchitry entry also matches the product string.
SERIAL_PORT ?= $(shell ../scripts/usb_serial_map --by-id alchitry 2>/dev/null \
                 || echo /dev/serial/by-id/usb-Xilinx_Alchitry_Au_V2_000000-if01-port0)
BAUD_RATE    = 2000000

JOP          ?= ../../java/apps/Smallest/HelloWorld.jop
PROJECT_ROOT  = ../..
ASM_DIR       = ../../asm

# SST26VF032B = 4 MB total; XC7A35T bitstream ~2.1 MB; offset at 2.25 MB
JOP_OFFSET   ?= 0x240000

.PHONY: all full run microcode generate ips project bitstream program \
        download monitor check-board find-serial-port \
        microcode-flash generate-flash project-flash bitstream-flash flash-image \
        flash-programmer program-flash clean

all:  microcode generate ips project bitstream
full: all run
run:  program download monitor

microcode:
	cd $(ASM_DIR) && $(MAKE) serial
	@echo "=== Serial microcode generated ==="

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog auSerial"

ips:
	$(RUN_VIVADO) vivado/tcl/create_clk_wiz.tcl
	$(RUN_VIVADO) vivado/tcl/create_mig.tcl
	@echo "=== IPs generated ==="

project:
	$(RUN_VIVADO) vivado/tcl/create_project.tcl

bitstream:
	$(RUN_VIVADO) vivado/tcl/build_bitstream.tcl

program:
	$(RUN_VIVADO) vivado/tcl/program_bitstream.tcl

download:
	python3 ../scripts/download.py -e $(JOP) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 ../scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

check-board:
	./scripts/check_board.sh

find-serial-port:
	./scripts/find_alchitry_ports.sh --serial

# --------------------------------------------------------------------------
# FLASH BOOT — the programmer works; the image it programs cannot be built
#
# This board booted autonomously from SPI flash in March 2026 and
# docs/boards/flash-boot-artix7.md still records every milestone as Done,
# including "Autonomous boot after power-cycle".
#
# It has been unbuildable since 2026-03-13. Commit 7258661 ("Remove IoConfig
# and legacy tops JopSdramTop/JopDdr3Top") deleted JopDdr3Top.scala, which
# carried `JopDdr3FlashTopVerilog` -- the main that generated the flash-boot
# top. The refactor moved every SERIAL flow to JopTopVerilog preset names and
# provided no flash equivalent: NO JopConfig preset anywhere sets
# `bootMode = BootMode.Flash`. The capability was removed as collateral and
# nothing noticed, because no CI job builds an FPGA and the targets stayed.
#
# Everything else survived intact: asm/Makefile still builds flash microcode in
# its `all` (build/microcode/flash/ is populated right now), MicrocodePaths is
# keyed on the mode, the XDC and the Tcl are here, and the UART flash
# programmer below is live and unaffected.
#
# The fix is a preset, not a Makefile: an `auFlash` with bootMode Flash, which
# would restore this board and the EP4CGX150 (whose JopCfgFlashTopVerilog went
# in the same commit) together. Tracked as status item 82.
# --------------------------------------------------------------------------

microcode-flash:
	cd $(ASM_DIR) && $(MAKE) flash-alchitry
	@echo "=== Flash microcode generated (Artix-7, offset $(JOP_OFFSET)) ==="

generate-flash:
	@echo "BLOCKED: flash boot needs a JopConfig preset with bootMode = BootMode.Flash." >&2
	@echo "  JopDdr3FlashTopVerilog was deleted by 7258661 (2026-03-13) and nothing" >&2
	@echo "  replaced it. See the FLASH BOOT note in this Makefile and status item 82." >&2
	@exit 1

project-flash:
	$(RUN_VIVADO) vivado/tcl/create_project_flash.tcl

bitstream-flash:
	$(RUN_VIVADO) vivado/tcl/build_bitstream_flash.tcl

flash-image:
	python3 ../scripts/make_flash_image.py --no-bitrev \
		vivado/build/jop_flash.bin $(JOP) \
		vivado/build/flash_image.bin --jop-offset $(JOP_OFFSET)

# The UART flash programmer itself — LIVE. Vivado cannot program an
# SST26VF032B, so the FPGA is loaded with a bitstream that does it over UART.
flash-programmer:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.FlashProgrammerDdr3TopVerilog"
	$(RUN_VIVADO) vivado/tcl/create_project_flash_programmer.tcl
	$(RUN_VIVADO) vivado/tcl/build_bitstream_flash_programmer.tcl
	$(RUN_VIVADO) vivado/tcl/program_flash_programmer.tcl

program-flash:
	sudo python3 ../scripts/flash_program.py --sst26 \
		vivado/build/flash_image.bin --port $(SERIAL_PORT) -v

clean:
	rm -rf vivado/build .Xil Xil *.jou *.log *.str
