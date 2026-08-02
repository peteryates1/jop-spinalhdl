# =============================================================================
# A-E115FB DDR2 exerciser — build / program / monitor
#
# First hardware milestone of the DDR2 bring-up. See
# docs/boards/ae115fb-ddr2-bringup.md.
#
#   make generate   SpinalHDL -> Verilog
#   make ip         link the vendor DDR2 IP into ./ip
#   make build      Quartus compile
#   make program    configure the FPGA over JTAG
#   make monitor    watch the UART report
#   make all        generate + ip + build
#   make run        program + monitor
# =============================================================================

ROOT := ../..

# Quartus 18.1 is the last version that supports Cyclone IV DDR2 ALTMEMPHY. The
# IP is pre-generated so 25.1 can compile it, but 18.1 is what generated it and
# is the safer choice until the flow is proven.
QUARTUS_DIR ?= /opt/altera/18.1/quartus
QUARTUS_BIN  = $(QUARTUS_DIR)/bin

PROJECT := ddr2_exerciser

# Vendor-generated DDR2 controller. Not vendored into this repo: ~3.7 MB of
# generated Verilog under a vendor licence, and Quartus >18.1 cannot regenerate
# it. Override if the reference projects live elsewhere.
DDR2_IP_DIR ?= /srv/git/cycloneEthernet/A-E115FB_core_V2/E115_core_test/read_write_1G/DDR667_read_write/ddr2_64bit

# Board selection resolves from the probe's USB serial, since port paths move on
# every replug — see fpga/scripts/jtag_probe_map.
CABLE = $(shell $(ROOT)/fpga/scripts/jtag_probe_map --cable ae115fb)

# The board's own CH340, not the Pico (which runs pico-usb-blaster and has no
# CDC interface).
UART_DEV  ?= /dev/ttyUSB0
UART_BAUD ?= 115200

.PHONY: all generate ip build program run monitor clean

all: generate ip build

generate:
	cd $(ROOT) && sbt "runMain jop.system.Ddr2ExerciserTopVerilog"

ip:
	@if [ ! -d "$(DDR2_IP_DIR)" ]; then \
	  echo "ERROR: DDR2 IP not found at $(DDR2_IP_DIR)"; \
	  echo "Set DDR2_IP_DIR to the generated ddr2_64bit directory."; \
	  exit 1; \
	fi
	mkdir -p ip
	rm -f ip/ddr2_64bit
	ln -s "$(DDR2_IP_DIR)" ip/ddr2_64bit
	@echo "=== DDR2 IP linked from $(DDR2_IP_DIR) ==="

build:
	$(QUARTUS_BIN)/quartus_map $(PROJECT)
	$(QUARTUS_BIN)/quartus_fit $(PROJECT)
	$(QUARTUS_BIN)/quartus_asm $(PROJECT)
	$(QUARTUS_BIN)/quartus_sta $(PROJECT)
	@echo "=== built output_files/$(PROJECT).sof ==="

program:
	@echo "=== programming via cable: $(CABLE) ==="
	$(QUARTUS_BIN)/quartus_pgm -c "$(CABLE)" -m JTAG \
	  -o "p;output_files/$(PROJECT).sof"

monitor:
	@echo "=== $(UART_DEV) @ $(UART_BAUD) — expect 'DDR2 PASS 0000' per pass ==="
	python3 -c "import serial,sys; s=serial.Serial('$(UART_DEV)',$(UART_BAUD)); \
	  [sys.stdout.write(s.read(1).decode('ascii','replace')) or sys.stdout.flush() for _ in iter(int,1)]"

run: program monitor

clean:
	rm -rf db incremental_db output_files ip
