# ---------------------------------------------------------------------------
# JOP — Arrow MAX1000 (10M08 + SDRAM)
#
#   make all       microcode + generate + build
#   make run       program + download + monitor
#   make full      all + run
#
# THE BOARD IS REAL, IT IS JUST NOT AT THIS SITE. It was the project's original
# target and remains one; nothing here has been exercised on it recently, so
# treat the programming and console targets as UNVERIFIED rather than trusted.
# Until then this flow doubles as a FIT CHECK on the smallest part targeted --
# a 10M08 has 8k LEs against the EP4CGX150's 149k, so a change that quietly
# doubles an area cost shows up here first.
#
# OPEN: a 1- or 2-core configuration that fits the 10M08. `max1000Sdram` is
# single-core at 80 MHz and has no recorded fit result. See status item 84.
#
# REPAIRED 2026-08-26. Both of these were broken, not merely unverified:
#
#   download  invoked `java -cp java/tools/dist/jop-tools.jar
#             com.jopdesign.tools.SerialDownload`. NEITHER exists -- dist holds
#             jopa.jar, jopizer.jar and jopsim.jar, and there is no
#             SerialDownload class anywhere in java/tools/src. The project's
#             downloader has been fpga/scripts/download.py for a long time.
#
#   program   used a bare `-c USB-Blaster`, which takes whichever blaster
#             enumerated first -- and more than one is attached to this host.
#             Resolved by SERIAL like every other flow, with a fallback so the
#             target still runs where jtag_probe_map has no entry yet. ADD A
#             max1000 ENTRY to fpga/scripts/jtag_probe_map when the board is
#             next connected.
#
# STILL IN-TREE. jop_max1000.qsf reads ../../spinalhdl/generated/, the pre-
# build-tree location, so `generate` below deliberately omits `buildtree`. The
# conversion needs a MAX1000 BoardDesign; see docs/architecture/build-port-
# triage.md.
# ---------------------------------------------------------------------------

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN  = $(QUARTUS_DIR)/bin
PROJECT_ROOT = ../..
ASM_DIR      = ../../asm
QP           = jop_max1000
OUTPUT_DIR   = output_files

# Resolved by serial where possible; the bare name is a fallback for a host
# where this board has no jtag_probe_map entry yet.
BLASTER_TYPE ?= $(shell $(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --cable max1000 2>/dev/null \
                  || echo USB-Blaster)
SERIAL_PORT  ?= $(shell $(PROJECT_ROOT)/fpga/scripts/usb_serial_map --by-id max1000 2>/dev/null \
                  || echo /dev/ttyUSB0)
BAUD_RATE    ?= 2000000
JOP_FILE     ?= $(PROJECT_ROOT)/java/apps/Smallest/HelloWorld.jop

.PHONY: all full run microcode generate build program download monitor clean

all:  microcode generate build
full: all run
run:  program download monitor

microcode:
	cd $(ASM_DIR) && $(MAKE) serial
	@echo "=== Serial microcode generated ==="

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog max1000Sdram"
	@echo "=== Verilog generated ==="

build:
	$(QUARTUS_BIN)/quartus_sh --flow compile $(QP)
	@echo "=== Build complete ==="
	@grep "Total logic elements" $(OUTPUT_DIR)/$(QP).fit.summary 2>/dev/null || true

program:
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG -o "p;$(OUTPUT_DIR)/$(QP).sof"
	@echo "=== FPGA programmed: $(OUTPUT_DIR)/$(QP).sof ==="

download:
	python3 $(PROJECT_ROOT)/fpga/scripts/download.py -e $(JOP_FILE) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 $(PROJECT_ROOT)/fpga/scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

clean:
	rm -rf $(OUTPUT_DIR) db incremental_db
