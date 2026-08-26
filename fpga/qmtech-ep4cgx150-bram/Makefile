# JOP BRAM — build & program
#
# BRAM-only JOP: no memory controller, so it isolates core problems from SDRAM
# ones. `gc` is the same board with a heap large enough to exercise the
# collector.
#
# CONVERTED 2026-08-25. The Quartus flow and the console come from
# ../quartus.mk; only what is board-specific is here.

PROJECT_ROOT  = ../..
CFG          ?= ep4cgx150Bram
REV          ?= jop_bram
BOARD_ALIAS   = ep4cgx150
CONSOLE_ALIAS = ep4cgx150

include ../quartus.mk

.PHONY: all run program full gc clean
all: generate build
program: program-sof
run: program monitor
full: generate build program monitor

# A different CONFIGURATION of the same project, so it re-enters the same rules.
gc:
	$(MAKE) generate build CFG=ep4cgx150BramGc REV=$(REV)

clean: quartus-clean
