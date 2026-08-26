# ---------------------------------------------------------------------------
# The Quartus flow, once.
#
# Included by every converted Altera board Makefile. The board sets what is
# genuinely board-specific -- which preset, which revision name, how to program,
# which console -- and inherits the rest.
#
# WHY THIS EXISTS. The same six rules were hand-copied into the EP4CGX150, the
# SDRAM-test and the CYC5000 Makefiles, and the copies drifted: one named
# $(QP).cdf where QP was fixed at jop_sdram, so `make program REV=jop_dbfpga`
# programmed the WRONG BITSTREAM. That is the failure mode a shared include
# removes.
#
# The board must set before including:
#   PROJECT_ROOT   path to the repo root
#   CFG            preset invocation, e.g. ep4cgx150Serial or "ep4cgx150Smp 4"
#   REV            Quartus revision name, e.g. jop_sdram
# and may set:
#   QUARTUS_DIR    default /opt/altera/25.1/quartus
#   BOARD_ALIAS    for jtag_probe_map, default none (program target is skipped)
# ---------------------------------------------------------------------------

QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN  = $(QUARTUS_DIR)/bin

# The config directory name comes from BuildLayout, never from a second copy of
# its sanitising rules here. A bare preset is a Scala identifier, so sanitising
# it is the identity and costs no call; only a multi-argument invocation pays.
ifeq ($(words $(CFG)),1)
  CFG_NAME := $(CFG)
else
  CFG_NAME := $(shell cd $(PROJECT_ROOT) && sbt "runMain jop.generate.BuildLayoutMain $(CFG)" 2>/dev/null \
                | sed -n 's|^\[info\] build/\(.*\)$$|\1|p' | tail -1)
  ifeq ($(CFG_NAME),)
    $(error BuildLayoutMain produced no directory for CFG="$(CFG)")
  endif
endif

CFG_DIR      = $(PROJECT_ROOT)/build/$(CFG_NAME)
QUARTUS_PRJ  = $(CFG_DIR)/quartus
SOF_FILE     = $(QUARTUS_PRJ)/output_files/$(REV).sof

# A STAMP, not the .v: the entity name is derived from the config (JopSdramTop
# at one core, JopSmpSdramTop at two or more), so naming the file here would
# restate config in Make and be wrong for every SMP build.
GEN_STAMP    = $(CFG_DIR)/rtl/.generated
SCALA_SRC   := $(shell find $(PROJECT_ROOT)/spinalhdl/src/main/scala -name '*.scala')
UCODE       := $(wildcard $(PROJECT_ROOT)/build/microcode/serial/mem_*.dat)

.PHONY: generate build program-sof quartus-clean

$(GEN_STAMP): $(SCALA_SRC) $(UCODE)
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog $(CFG) buildtree"
	@mkdir -p $(dir $@) && touch $@

$(QUARTUS_PRJ)/$(REV).sdc:
	cd $(PROJECT_ROOT) && sbt "runMain jop.generate.TimingConstraintsMain $(CFG) --write build/$(CFG_NAME)/quartus/$(REV).sdc"

$(QUARTUS_PRJ)/setup_proj.tcl:
	cd $(PROJECT_ROOT) && sbt "runMain jop.generate.QuartusProjectMain $(CFG) --revision $(REV) --write build/$(CFG_NAME)/quartus/setup_proj.tcl"

# Quartus writes the .qsf/.qpf itself, from our Tcl, so their format follows the
# tool version rather than being frozen by hand.
$(QUARTUS_PRJ)/$(REV).qsf: $(QUARTUS_PRJ)/setup_proj.tcl $(QUARTUS_PRJ)/$(REV).sdc
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sh -t setup_proj.tcl

generate: $(GEN_STAMP)
	@echo "=== Generated into $(CFG_DIR)/rtl ==="

$(SOF_FILE): $(GEN_STAMP) $(QUARTUS_PRJ)/$(REV).qsf
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_map $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_fit $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_asm $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sta $(REV)
	@echo "=== Build complete: $(SOF_FILE) ==="

build: $(SOF_FILE)

# The cable is resolved by SERIAL, never by a bare "USB-Blaster": more than one
# blaster is attached to this host and a bare name takes whichever enumerated
# first. Takes the .sof directly, so no .cdf is needed.
# Named program-sof, not program: some boards load a .rbf or go through
# openFPGALoader instead, and they define their own `program`. A board whose
# programming is just "quartus_pgm the .sof" writes `program: program-sof`.
program-sof: $(SOF_FILE)
	$(QUARTUS_BIN)/quartus_pgm \
	    -c "$$($(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --cable $(BOARD_ALIAS))" \
	    -m JTAG -o "p;$(SOF_FILE)"
	@echo "=== FPGA programmed: $(SOF_FILE) ==="

quartus-clean:
	rm -rf $(CFG_DIR)

# The console is not Quartus-specific, but every board that uses this flow needs
# it, and it must come after CFG_DIR is defined -- it reads the build's summary.
include $(dir $(lastword $(MAKEFILE_LIST)))console.mk
