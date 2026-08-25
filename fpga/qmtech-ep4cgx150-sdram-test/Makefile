# SDRAM Exerciser Test - Build & Program

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin
SERIAL_PORT ?= $(shell ../scripts/usb_serial_map --by-id ep4cgx150 2>/dev/null \
                 || echo /dev/serial/by-id/usb-Silicon_Labs_CP2102N_USB_to_UART_Bridge_Controller_de4cebf79bd1eb11910b7b54e93fd3f1-if00-port0)
BAUD_RATE = 1000000

PROJECT_ROOT = ../..
MONITOR_PY = ../qmtech-ep4cgx150-bram/monitor.py

# CONVERTED (2026-08-25). Verilog, PLL, SDC and the Quartus project are all
# generated from SdramExerciserDesign into build/sdramExerciser/; Quartus writes
# its own .qsf/.qpf from setup_proj.tcl. The hand-written sdram_test.qsf/.qpf/
# .sdc were proven equivalent -- 43 pins and the SDC line for line -- then
# deleted.
QP = sdram_test
CFG_DIR = $(PROJECT_ROOT)/build/sdramExerciser
QUARTUS_PRJ = $(CFG_DIR)/quartus
SOF_FILE = $(QUARTUS_PRJ)/output_files/$(QP).sof

# The probe is resolved by SERIAL, like every other flow here -- a bare
# "USB-Blaster" picks whichever cable enumerated first.
BLASTER_TYPE := $(shell $(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --cable ep4cgx150 2>/dev/null || echo USB-Blaster)

.PHONY: all generate build program monitor run clean

all: generate build

run: program monitor

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.SdramExerciserBuild"
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sh -t setup_proj.tcl

build:
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_map $(QP)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_fit $(QP)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_asm $(QP)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sta $(QP)
	@echo "=== Build complete: $(SOF_FILE) ==="

program:
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG -o "p;$(SOF_FILE)"
	@echo "=== FPGA programmed ==="

monitor:
	python3 $(MONITOR_PY) $(SERIAL_PORT) $(BAUD_RATE)

clean:
	rm -rf $(CFG_DIR)
