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
# CONVERTED 2026-08-29. The note here said the conversion "needs a MAX1000
# BoardDesign" -- stale: max1000Sdram is a JopConfig preset, and JopConfig IS a
# BoardDesign, so QuartusProjectMain generates this project like any other. The
# hand-written jop_max1000.qsf is gone.
#
# THAT QSF HAD ZERO PIN ASSIGNMENTS. Not "some pins missing" -- none at all, so
# every signal was placed wherever the fitter liked. The generated project has
# five real ones (clk_in, ser_txd, ser_rxd, led[0..1]).
#
# PINS ARE REAL NOW, recovered from the working jopmin project for this board
# (/srv/git/jopmin/quartus/max1000/jop.qsf) -- SDRAM, clock, UART and all eight
# LEDs. They had been called "placeholders not verified against schematic", and
# the fitter rejected every one; both readings were wrong. The pins were right
# and the DEVICE was wrong: the MAX1000 is a 10M08SAU169C8G (169-ball UBGA) and
# the model named the 144-pin EQFP, whose pin names are entirely different.
# So this is a FIT CHECK, which is what it was already good for
# -- a 10M08 has 8k LEs against the EP4CGX150's 149k, so an area regression
# shows up here first.
#
# ITEM 84 NOW HAS ITS NUMBER, and `make all` FAILS ON PURPOSE:
#
#   Total logic elements : 10,791 / 8,064 ( 134 % )
#   Total pins           :     49 /   130 (  38 % )
#   Total memory bits    : 137,344 / 387,072 ( 35 % )
#
# Single-core max1000Sdram overflows the 10M08 by a third. Memory is fine at
# 35 %, so the work is LOGIC: a cropped-back core, not a smaller heap. The
# fitter failing here is the flow WORKING -- it is the first real answer this
# board has ever produced, because the old hand-written project could not get
# far enough to be asked.
# ---------------------------------------------------------------------------

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN  = $(QUARTUS_DIR)/bin
PROJECT_ROOT = ../..
ASM_DIR      = ../../asm
QP           = jop_max1000

PROJECT_ROOT  = ../..
CFG          ?= max1000Sdram
REV          ?= jop_max1000
BOARD_ALIAS   = max1000
CONSOLE_ALIAS = max1000

include ../quartus.mk

.PHONY: all full run program clean

all:  microcode generate build
full: all run
run:  program download monitor

program: program-sof

# Nothing is generated in-tree any more, so `clean` is quartus.mk's
# `quartus-clean`: remove build/<config>/.
clean: quartus-clean
