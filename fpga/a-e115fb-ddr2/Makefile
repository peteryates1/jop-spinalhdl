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

PROJECT ?= ddr2_exerciser

# The DDR2 controller is REGENERATED from the variation file in ip-src/, which is
# ~30 KB and under version control; the ~3.7 MB of generated Verilog is derived
# and is not. Regeneration needs Quartus 18.1 — Intel dropped Cyclone IV DDR2
# ALTMEMPHY after that, which is the whole reason 18.1 is installed.
#
# ip-src/ddr2_64bit.v is the vendor's variation with local_if_drate switched from
# Full to Half: 256-bit local interface at 83 MHz instead of 128-bit at 166 MHz.
# The wizard reads its parameters from the "Retrieval info" comments in that file.
IP_SRC := ip-src/ddr2_64bit.v

# Board selection resolves from the probe's USB serial, since port paths move on
# every replug — see fpga/scripts/jtag_probe_map.
CABLE = $(shell $(ROOT)/fpga/scripts/jtag_probe_map --cable ae115fb)

# The board's own CH340, not the Pico (which runs pico-usb-blaster and has no
# CDC interface).
UART_DEV  ?= /dev/ttyUSB0
UART_BAUD ?= 2000000
JOP_FILE  ?= $(ROOT)/java/apps/Smallest/HelloWorld.jop

.PHONY: all generate ip build program run monitor clean assert-device

all: generate ip build

generate:
	cd $(ROOT) && sbt "runMain jop.system.Ddr2ExerciserTopVerilog"

ip: ip/ddr2_64bit/ddr2_64bit.qip

ip/ddr2_64bit/ddr2_64bit.qip: $(IP_SRC)
	@echo "=== regenerating DDR2 IP (half rate) from $(IP_SRC) ==="
	rm -rf ip/ddr2_64bit
	mkdir -p ip/ddr2_64bit
	cp $(IP_SRC) ip/ddr2_64bit/
	@# qmegawiz generates against the project's family/device, so it needs one.
	printf 'QUARTUS_VERSION = "18.1"\nPROJECT_REVISION = "gen"\n' > ip/ddr2_64bit/gen.qpf
	printf 'set_global_assignment -name FAMILY "Cyclone IV E"\nset_global_assignment -name DEVICE EP4CE115F23I7\nset_global_assignment -name TOP_LEVEL_ENTITY ddr2_64bit\n' > ip/ddr2_64bit/gen.qsf
	cd ip/ddr2_64bit && $(QUARTUS_BIN)/qmegawiz -silent ddr2_64bit.v
	@grep -E "local_if_drate|local_if_dwidth" ip/ddr2_64bit/ddr2_64bit_phy_ddr_timing.sdc | head -2

build:
	$(QUARTUS_BIN)/quartus_map $(PROJECT)
	$(QUARTUS_BIN)/quartus_fit $(PROJECT)
	$(QUARTUS_BIN)/quartus_asm $(PROJECT)
	$(QUARTUS_BIN)/quartus_sta $(PROJECT)
	@echo "=== built output_files/$(PROJECT).sof ==="

# The Terasic blaster is SHARED with the EP4CGX150 and gets moved between the two
# boards, so the alias alone cannot tell you which one is on the end of it -- and
# programming the wrong Altera board succeeds silently. This is a read-only JTAG
# chain scan that fails unless the IDCODE is the EP4CE115's.
assert-device:
	@$(ROOT)/fpga/scripts/jtag_probe_map --assert-device ae115fb

program: assert-device
	@echo "=== programming via cable: $(CABLE) ==="
	$(QUARTUS_BIN)/quartus_pgm -c "$(CABLE)" -m JTAG \
	  -o "p;output_files/$(PROJECT).sof"

# Reset the core over UART, no JTAG and no reprogramming. On DDR2 this is a
# CORE-ONLY reset: the ALTMEMPHY keeps its calibration (global_reset_n and
# soft_reset_n stay on the outer reset), so it costs microseconds rather than
# the seconds a retrain would. Needs a bitstream from 2026-08-18 or later.
reset:
	python3 $(ROOT)/fpga/scripts/download.py -R $(UART_DEV) $(UART_BAUD)

# Swap the running application without reprogramming the FPGA.
redownload:
	python3 $(ROOT)/fpga/scripts/download.py -e -r $(JOP_FILE) $(UART_DEV) $(UART_BAUD)

monitor:
	@echo "=== $(UART_DEV) @ $(UART_BAUD) — expect 'DDR2 PASS 0000' per pass ==="
	python3 -c "import serial,sys; s=serial.Serial('$(UART_DEV)',$(UART_BAUD)); \
	  [sys.stdout.write(s.read(1).decode('ascii','replace')) or sys.stdout.flush() for _ in iter(int,1)]"

run: program monitor

clean:
	rm -rf db incremental_db output_files ip
