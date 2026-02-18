# SDRAM Exerciser Test - Build & Program

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin
BLASTER_TYPE ?= USB-Blaster
SERIAL_PORT ?= /dev/ttyUSB0
BAUD_RATE = 1000000

PROJECT_ROOT = ../..
GENERATED_V = $(PROJECT_ROOT)/spinalhdl/generated/SdramExerciserTop.v
MONITOR_PY = ../qmtech-ep4cgx150-bram/monitor.py

QP = sdram_test
OUTPUT_DIR = output_files
SOF_FILE = $(OUTPUT_DIR)/$(QP).sof

.PHONY: all generate build program monitor run clean

all: generate build

run: program monitor

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.SdramExerciserTopVerilog"

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

monitor:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD_RATE)

clean:
	rm -rf output_files db incremental_db
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
