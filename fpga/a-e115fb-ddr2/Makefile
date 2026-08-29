# JOP DDR2 — A-E115FB (EP4CE115 + 1 GB DDR2 SODIMM)
#
# CONVERTED 2026-08-26. The Quartus flow, the console and the microcode come
# from ../quartus.mk; what is board-specific stays here.
#
# QUARTUS 18.1, NOT 25.1, and that is not a preference. Intel dropped Cyclone
# IV DDR2 ALTMEMPHY after 18.1, which is the whole reason 18.1 is installed.
# 25.1 Lite still supports Cyclone IV E itself, so only the DDR2 path is
# pinned. quartus.mk takes QUARTUS_DIR with `?=`, so this is all it takes.
#
# The Quartus project is GENERATED now, like every other converted board.
# Proven equivalent before the hand-written one was retired: the generated
# project and jop_ddr2.qsf carry the SAME 506 pin and instance assignments,
# zero differences in either direction, and the generated .sdc reproduces
# jop_ddr2.sdc exactly. DoAll 66/66 on hardware from the generated project.
#
# Two things that took finding, both recorded in status item 78:
#
#   - The DDR2 pin/PHY assignments are SOURCED from the vendor file, not
#     transcribed into board data: `Board.constraintFiles`. Only location and
#     IO_STANDARD are pin facts; the rest are ALTMEMPHY and SODIMM properties.
#
#   - The PHY brings its OWN clock constraints (ddr2_64bit_phy_ddr_timing.sdc,
#     pulled in by its .qip). Generating a second create_clock and
#     derive_pll_clocks on top re-derived the PHY's internal clocks under ours
#     and cost 5.4 ns: +0.584 became -4.807. The generated .sdc carries only
#     the JOP-side I/O false paths.

PROJECT_ROOT  = ../..
CFG          ?= ae115fbDdr2
REV          ?= jop_ddr2
BOARD_ALIAS   = ae115fb
CONSOLE_ALIAS = ae115fb
QUARTUS_DIR  ?= /opt/altera/18.1/quartus

include ../quartus.mk

.PHONY: all full program assert-device ip smp exerciser exerciser-program clean

all:  microcode ip generate build
full: microcode ip generate build program download monitor

# --------------------------------------------------------------------------
# Vendor DDR2 IP
#
# REGENERATED from the variation file in ip-src/, which is ~30 KB and tracked;
# the ~3.7 MB of generated Verilog is derived and is not. ip-src/ddr2_64bit.v is
# the vendor's variation with local_if_drate switched Full -> Half: a 256-bit
# local interface at 83 MHz rather than 128-bit at 166 MHz. The wizard reads its
# parameters from the "Retrieval info" comments in that file.
# --------------------------------------------------------------------------

IP_SRC := ip-src/ddr2_64bit.v

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

# --------------------------------------------------------------------------
# Programming
#
# The Terasic blaster is this board's own cable now that the EP4CGX150 has a
# level-shifted Pico of its own, but the IDCODE check still guards the program:
# two blasters are attached at once, and quartus_pgm reports success on a chain
# it never read. It lives in quartus.mk, and `program-sof` depends on it.
# --------------------------------------------------------------------------

program: program-sof

# --------------------------------------------------------------------------
# Other configurations
# --------------------------------------------------------------------------

#   make smp            2 cores
#   make smp CORES=8
CORES ?= 2
smp:
	$(MAKE) generate build CFG="ae115fbDdr2Smp $(CORES)" REV=jop_ddr2_smp

smp-program:
	$(MAKE) program CFG="ae115fbDdr2Smp $(CORES)" REV=jop_ddr2_smp

# --------------------------------------------------------------------------
# NOT CONVERTED — the DDR2 exerciser
#
# The first hardware milestone of the bring-up (docs/boards/ae115fb-ddr2-
# bringup.md). Still builds in-tree from the hand-written ddr2_exerciser.qsf:
# it is a standalone Ddr2ExerciserTopVerilog design rather than a JopConfig
# preset, so converting it means giving it a BoardDesign as the SD and SDRAM
# exercisers have. Left alone because the JOP path it was written to unblock now
# works end to end.
# --------------------------------------------------------------------------

exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.Ddr2ExerciserTopVerilog"
	$(QUARTUS_BIN)/quartus_map ddr2_exerciser
	$(QUARTUS_BIN)/quartus_fit ddr2_exerciser
	$(QUARTUS_BIN)/quartus_asm ddr2_exerciser
	$(QUARTUS_BIN)/quartus_sta ddr2_exerciser

exerciser-program: assert-device
	$(QUARTUS_BIN)/quartus_pgm -c "$$($(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --cable $(BOARD_ALIAS))" \
	    -m JTAG -o "p;output_files/ddr2_exerciser.sof"

# In-tree leftovers only; the generated tree is quartus.mk's `quartus-clean`.
clean:
	rm -rf db incremental_db output_files
