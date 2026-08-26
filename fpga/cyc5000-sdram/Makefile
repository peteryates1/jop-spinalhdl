#
# JOP SDRAM - Trenz CYC5000 Build & Program
#
# Targets:
#   make microcode     - Build serial-boot microcode
#   make generate      - Generate Verilog from SpinalHDL
#   make generate-smp  - Generate SMP Verilog (2 cores)
#   make build         - Quartus compile (map, fit, asm, sta)
#   make program       - Program FPGA via JTAG
#   make download      - Send HelloWorld.jop via serial
#   make monitor       - Monitor UART output (1 Mbaud)
#   make build-smp     - Quartus compile SMP (2 cores)
#   make program-smp   - Program SMP FPGA via JTAG
#   make all           - microcode + generate + build
#   make run           - program + download + monitor
#   make full          - microcode + generate + build + run
#   make full-smp      - microcode + generate-smp + build-smp + run-smp
#

# Quartus installation
QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin

# JTAG programmer.
#
# The on-board programmer is an FT2232H ("Arrow USB Blaster TEI0050",
# 0403:6010), not an Altera Blaster. openFPGALoader knows it as board `cyc5000`
# and needs nothing special — use `make program` below.
#
# Quartus CAN see it, but only with BOTH of:
#   1. libjtag_hw_arrow.so present in $(QUARTUS_DIR)/linux64/. This does NOT
#      ship with Quartus, any version — it is obtained from Arrow/Trenz and
#      copied in by hand. It works in 18.1 as well as 25.1.
#   2. ftdi_sio unbound from JTAG interface 0, which it claims on plug-in:
#        echo -n 1-8:1.0 | sudo tee /sys/bus/usb/drivers/ftdi_sio/unbind
#      (substitute the real port path; fpga/scripts/jtag_probe_map shows it).
#      Leave interface 1 bound — that one is the UART, see SERIAL_PORT below.
#   Then `pkill jtagd` so the daemon rescans. Verified 2026-08-07: jtagconfig
#   then lists "Arrow-USB-Blaster [ARA30135-TEI0050]" with device 5CE(BA2|FA2).
#
# openFPGALoader also will not take a .sof for SRAM programming ("please use
# rbf or svf file"), so the sof is converted with quartus_cpf first.
BLASTER_TYPE ?= Arrow-USB-Blaster
OFL ?= openFPGALoader

# Serial port for UART.
#
# The FT2232H exposes TWO interfaces and ftdi_sio claims both: interface A
# (if00) carries JTAG, interface B (if01) is the FPGA UART (F16/E18). So the
# console is the SECOND ttyUSB of the pair, not the first. Numbering moves on
# every replug — re-check with fpga/scripts/usb_serial_map rather than trusting
# this default.
SERIAL_PORT ?= $(shell ../scripts/usb_serial_map --by-id cyc5000 2>/dev/null \
                 || echo /dev/serial/by-id/usb-Arrow_Arrow_USB_Blaster_TEI0050_ARA30135-if01-port0)
# 2 Mbaud: cyc5000Serial runs at 80 MHz and UartCtrl divides by baud x 5, so
# the divider is exactly 8 — 0.00% error.
BAUD_RATE = 2000000

# JOP application file
JOP_FILE ?= ../../java/apps/Smallest/HelloWorld.jop

# Project paths (sbt runs from repo root)
PROJECT_ROOT = ../..
ASM_DIR = ../../asm

# CONVERTED 2026-08-25. The Quartus flow and console come from ../quartus.mk.
CFG          ?= cyc5000Serial
REV          ?= jop_cyc5000
CORES        ?= 2
BOARD_ALIAS   = cyc5000
CONSOLE_ALIAS = cyc5000

include ../quartus.mk

smp:
	$(MAKE) generate build CFG="cyc5000Smp $(CORES)" REV=jop_smp_cyc5000

smp-program:
	$(MAKE) program CFG="cyc5000Smp $(CORES)" REV=jop_smp_cyc5000

# --------------------------------------------------------------------------
# Quartus build
# --------------------------------------------------------------------------




# --------------------------------------------------------------------------
# FPGA programming
# --------------------------------------------------------------------------

$(QUARTUS_PRJ)/$(REV).cdf: $(SOF_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(5CEBA2) Path("$(QUARTUS_PRJ)/output_files/") File("$(REV).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

RBF_FILE = $(QUARTUS_PRJ)/output_files/$(REV).rbf

$(RBF_FILE): $(SOF_FILE)
	$(QUARTUS_BIN)/quartus_cpf -c $(SOF_FILE) $(RBF_FILE)

# openFPGALoader claims the FTDI interface from ftdi_sio itself, so this needs
# no unbinding — but it does need root for raw USB access.
program: $(RBF_FILE)
	sudo $(OFL) -b cyc5000 $(RBF_FILE)
	@echo "=== FPGA programmed ==="

# Needs the two conditions in the JTAG programmer note above (hand-installed
# libjtag_hw_arrow.so, ftdi_sio unbound from interface 0). Without them this
# fails with "Unable to scan device chain". `make program` needs neither.
program-quartus: $(SOF_FILE) $(QUARTUS_PRJ)/$(REV).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QUARTUS_PRJ)/$(REV).cdf

$(REV).cdf: $(SOF_SMP_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(5CEBA2) Path("$(QUARTUS_PRJ)/output_files/") File("$(REV).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@


# --------------------------------------------------------------------------
# Serial download and UART monitor
# --------------------------------------------------------------------------



# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf output_files db incremental_db $(QUARTUS_PRJ)/$(REV).cdf $(REV).cdf
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
