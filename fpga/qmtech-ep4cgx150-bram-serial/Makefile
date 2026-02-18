# JOP BRAM Serial - Build & Program
#
# BRAM-backed JOP with serial download (like SDRAM version but with BRAM)
# Used to isolate serial download issues from SDRAM controller issues

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin
BLASTER_TYPE ?= USB-Blaster
SERIAL_PORT ?= /dev/ttyUSB0
BAUD_RATE = 1000000

PROJECT_ROOT = ../..
GENERATED_V = $(PROJECT_ROOT)/spinalhdl/generated/JopBramSerialTop.v
DOWNLOAD_PY = ../qmtech-ep4cgx150-sdram/download.py
MONITOR_PY = ../qmtech-ep4cgx150-bram/monitor.py

QP = jop_bram_serial
OUTPUT_DIR = output_files
SOF_FILE = $(OUTPUT_DIR)/$(QP).sof

.PHONY: all generate build program download monitor run clean

all: generate build

run: program download monitor

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopBramSerialTopVerilog"

build: $(SOF_FILE)

$(SOF_FILE): $(GENERATED_V) $(QP).qsf $(QP).sdc
	$(QUARTUS_BIN)/quartus_map $(QP)
	$(QUARTUS_BIN)/quartus_fit $(QP)
	$(QUARTUS_BIN)/quartus_asm $(QP)
	$(QUARTUS_BIN)/quartus_sta $(QP)
	@echo "=== Build complete: $(SOF_FILE) ==="

program:
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG -o "p;$(SOF_FILE)"
	@echo "=== FPGA programmed ==="

download:
	python3 $(DOWNLOAD_PY) $(PROJECT_ROOT)/java/apps/Small/HelloWorld.jop $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD_RATE)

clean:
	rm -rf output_files db incremental_db
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
