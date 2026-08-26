# ---------------------------------------------------------------------------
# JOP — Arrow MAX1000 (10M08 + SDRAM). FIT CHECK ONLY.
#
#   make all     microcode + generate + build
#   make clean
#
# THERE IS NO MAX1000 ON THIS HOST. This flow exists to prove the design still
# FITS the smallest part targeted -- a 10M08 has 8k LEs against the EP4CGX150's
# 149k -- so a change that quietly doubles an area cost shows up here first.
# It is a compile-time check and nothing more.
#
# RETIRED 2026-08-26: program, download, monitor, run and full. `download` had
# been dead since long before this: it invoked
#
#     java -cp java/tools/dist/jop-tools.jar com.jopdesign.tools.SerialDownload
#
# and NEITHER exists -- java/tools/dist holds jopa.jar, jopizer.jar and
# jopsim.jar, and there is no SerialDownload class anywhere in java/tools/src.
# The project's downloader has been fpga/scripts/download.py for a long time.
# `program` hardcoded a bare `-c USB-Blaster`, which takes whichever blaster
# enumerated first -- the wrong-board hazard every other flow here now resolves
# by serial. Rather than repair three targets against hardware that cannot test
# them, this board says what it actually does.
#
# STILL IN-TREE. jop_max1000.qsf reads ../../spinalhdl/generated/, the pre-
# build-tree location, so `generate` below deliberately omits `buildtree`. The
# conversion needs a MAX1000 BoardDesign; see docs/architecture/build-port-
# triage.md.
# ---------------------------------------------------------------------------

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN  = $(QUARTUS_DIR)/bin
PROJECT_ROOT = ../..
ASM_DIR      = ../../asm
QP           = jop_max1000
OUTPUT_DIR   = output_files

.PHONY: all microcode generate build clean

all: microcode generate build

microcode:
	cd $(ASM_DIR) && $(MAKE) serial
	@echo "=== Serial microcode generated ==="

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog max1000Sdram"
	@echo "=== Verilog generated ==="

build:
	$(QUARTUS_BIN)/quartus_sh --flow compile $(QP)
	@echo "=== Build complete ==="
	@grep "Total logic elements" $(OUTPUT_DIR)/$(QP).fit.summary 2>/dev/null || true

clean:
	rm -rf $(OUTPUT_DIR) db incremental_db
