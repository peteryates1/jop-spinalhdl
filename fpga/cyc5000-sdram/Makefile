# ---------------------------------------------------------------------------
# JOP SDRAM — Trenz CYC5000
#
# The Quartus flow, the console and the microcode come from ../quartus.mk;
# what remains below is what is genuinely specific to this board.
#
#   make all              microcode + generate + build
#   make full             all + program + download + monitor
#   make smp CORES=n      the SMP configuration of the same hardware project
#   make program          load the .rbf with openFPGALoader (the easy path)
#   make program-quartus  load with quartus_pgm (needs the two conditions below)
#
# TIDIED 2026-08-26, finishing the 2026-08-25 conversion. What was left behind:
# four variables that nothing read -- among them BAUD_RATE = 2000000, which
# LOOKED like the authority on the wire rate while console.mk was deriving BAUD
# from the build's own summary (status item 70) -- a second .cdf generator rule
# whose prerequisite $(SOF_SMP_FILE) was never defined anywhere, and a header
# advertising all/run/full/full-smp/build-smp/generate-smp, none of which
# existed. The .cdf class is gone entirely now: quartus_pgm takes an explicit
# operation, so the .sof goes to it directly, as on the EP4CGX150.
# ---------------------------------------------------------------------------

PROJECT_ROOT  = ../..
CFG          ?= cyc5000Serial
REV          ?= jop_cyc5000
CORES        ?= 2
BOARD_ALIAS   = cyc5000
CONSOLE_ALIAS = cyc5000

# Serial console and baud are console.mk's job -- it resolves the port from
# CONSOLE_ALIAS by serial and reads the rate the bitstream actually recorded.
# This board's own copies of both were deleted rather than left to disagree.
#
# The FT2232H exposes TWO interfaces and ftdi_sio claims both: interface A
# (if00) carries JTAG, interface B (if01) is the FPGA UART (F16/E18). So the
# console is the SECOND ttyUSB of the pair, not the first -- which is what
# `usb_serial_map --by-id cyc5000` returns.

include ../quartus.mk

.PHONY: all full smp smp-program program program-quartus clean

all:  microcode generate build
full: all program download monitor

# --------------------------------------------------------------------------
# The SMP configuration of this same hardware project
#
# Re-enters the parameterised rules with a different CFG rather than carrying a
# copy of them. BuildLayout keys on the invocation, so each core count lands in
# its own build/<config>/ and none can overwrite another's PLL.
# --------------------------------------------------------------------------

smp:
	$(MAKE) generate build CFG="cyc5000Smp $(CORES)" REV=jop_smp_cyc5000

smp-program:
	$(MAKE) program CFG="cyc5000Smp $(CORES)" REV=jop_smp_cyc5000

# --------------------------------------------------------------------------
# FPGA programming
#
# The on-board programmer is an FT2232H ("Arrow USB Blaster TEI0050",
# 0403:6010), not an Altera Blaster. openFPGALoader knows it as board `cyc5000`
# and needs nothing special, so `program` below is the path to use.
#
# Quartus CAN see it, but only with BOTH of:
#   1. libjtag_hw_arrow.so present in $(QUARTUS_DIR)/linux64/. This does NOT
#      ship with Quartus, any version — it is obtained from Arrow/Trenz and
#      copied in by hand. It works in 18.1 as well as 25.1.
#   2. ftdi_sio unbound from JTAG interface 0, which it claims on plug-in:
#        echo -n 1-8:1.0 | sudo tee /sys/bus/usb/drivers/ftdi_sio/unbind
#      (substitute the real port path; fpga/scripts/jtag_probe_map shows it).
#      Leave interface 1 bound — that one is the UART.
#   Then `pkill jtagd` so the daemon rescans. Verified 2026-08-07: jtagconfig
#   then lists "Arrow-USB-Blaster [ARA30135-TEI0050]" with device 5CE(BA2|FA2).
#
# openFPGALoader will not take a .sof for SRAM programming ("please use rbf or
# svf file"), so the sof is converted with quartus_cpf first.
# --------------------------------------------------------------------------

BLASTER_TYPE ?= Arrow-USB-Blaster
OFL          ?= openFPGALoader
RBF_FILE      = $(QUARTUS_PRJ)/output_files/$(REV).rbf

$(RBF_FILE): $(SOF_FILE)
	$(QUARTUS_BIN)/quartus_cpf -c $(SOF_FILE) $(RBF_FILE)

# openFPGALoader claims the FTDI interface from ftdi_sio itself, so this needs
# no unbinding — but it does need root for raw USB access.
program: $(RBF_FILE)
	sudo $(OFL) -b cyc5000 $(RBF_FILE)
	@echo "=== FPGA programmed: $(RBF_FILE) ==="

# Needs the two conditions in the note above. Without them this fails with
# "Unable to scan device chain". `make program` needs neither.
program-quartus: $(SOF_FILE)
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG -o "p;$(SOF_FILE)"
	@echo "=== FPGA programmed: $(SOF_FILE) ==="

# `clean` removes the in-tree leftovers only. The generated tree is
# quartus.mk's `quartus-clean`, which removes build/<config>/ for the CFG in
# force — one configuration, not all of them.
clean:
	rm -rf output_files db incremental_db
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin *.cdf
