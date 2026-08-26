# JOP BRAM Serial — build & program
#
# BRAM-backed JOP with serial download. Exists to isolate serial-download
# problems from SDRAM-controller problems: same boot path, no memory controller.
#
# CONVERTED 2026-08-25. Everything below is what is genuinely board-specific;
# the Quartus flow and the console come from ../quartus.mk.

PROJECT_ROOT  = ../..
CFG          ?= ep4cgx150BramSerial
REV          ?= jop_bram_serial
BOARD_ALIAS   = ep4cgx150
CONSOLE_ALIAS = ep4cgx150

include ../quartus.mk

.PHONY: all run program clean
all: generate build
program: program-sof
run: program download monitor
clean: quartus-clean
