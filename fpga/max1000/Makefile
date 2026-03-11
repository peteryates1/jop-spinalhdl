#
# JOP MAX1000 — Minimal fit-check build
#

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin
PROJECT_ROOT = ../..
ASM_DIR = ../../asm
QP = jop_max1000
OUTPUT_DIR = output_files
SERIAL_PORT ?= /dev/ttyUSB0
BAUD_RATE = 2000000
JOP_FILE ?= $(PROJECT_ROOT)/java/apps/Smallest/HelloWorld.jop

.PHONY: all microcode generate build program download monitor run full clean

all: microcode generate build

full: microcode generate build run

run: program download monitor

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
	$(QUARTUS_BIN)/quartus_pgm -c USB-Blaster -m jtag -o "p;$(OUTPUT_DIR)/$(QP).sof"

download:
	cd $(PROJECT_ROOT) && java -cp java/tools/dist/jop-tools.jar com.jopdesign.tools.SerialDownload $(SERIAL_PORT) $(JOP_FILE) $(BAUD_RATE)

monitor:
	sudo timeout 30 python3 $(PROJECT_ROOT)/fpga/scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

clean:
	rm -rf $(OUTPUT_DIR) db incremental_db
