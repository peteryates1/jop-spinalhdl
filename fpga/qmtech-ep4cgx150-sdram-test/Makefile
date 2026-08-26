# ---------------------------------------------------------------------------
# SDRAM Exerciser — QMTECH EP4CGX150
#
#   make all       generate + build
#   make run       program + monitor
#
# CONVERTED 2026-08-26. The Quartus flow and the console come from
# ../quartus.mk; this file now holds only what identifies the design.
#
# It was already generating everything (2026-08-25: Verilog, PLL, SDC and the
# Quartus project all come from SdramExerciserDesign, and the hand-written
# sdram_test.qsf/.qpf/.sdc were proven equivalent and deleted) -- but it still
# carried its own copy of the four quartus_* commands, its own quartus_pgm line
# with a hand-resolved blaster, its own BAUD_RATE constant, and a monitor
# pointing at ../qmtech-ep4cgx150-bram/monitor.py.
#
# What let it fold in was `GEN_MAIN` / `GEN_MAKES_PROJECT` in quartus.mk: an
# exerciser is not a JopConfig preset, it is a standalone BoardDesign whose
# single Build main emits RTL, PLL, SDC and setup_proj.tcl in one pass. Those
# two knobs are the whole difference. The cfgName and revision inside
# SdramExerciserBuild are exactly the CFG and REV set here.
#
# BAUD comes from the build's own summary now, not from the BAUD_RATE = 1000000
# that used to sit here -- see console.mk's header and status item 70.
# ---------------------------------------------------------------------------

PROJECT_ROOT      = ../..
CFG              ?= sdramExerciser
REV              ?= sdram_test
BOARD_ALIAS       = ep4cgx150
CONSOLE_ALIAS     = ep4cgx150
GEN_MAIN          = jop.system.SdramExerciserBuild
GEN_ARGS          =
GEN_MAKES_PROJECT = yes
# This design's UART reports results and listens to nothing
# (`txOnly -> true` in SdramExerciserDesign), so console.mk gives it
# `monitor` and refuses download/redownload/reset.
CONSOLE_TXONLY    = yes

include ../quartus.mk

.PHONY: all run program clean

all: generate build
run: program monitor

program: program-sof

# Nothing is generated in-tree for this design, so `clean` is exactly
# quartus.mk's `quartus-clean`: remove build/<config>/.
clean: quartus-clean

