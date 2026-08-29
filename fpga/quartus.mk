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
#   CONSOLE_ALIAS  for usb_serial_map, consumed by console.mk
#   GEN_MAIN       generator main, default jop.system.JopTopVerilog
#   GEN_ARGS       its arguments, default "$(CFG) buildtree"
#   GEN_MAKES_PROJECT  yes if GEN_MAIN also writes setup_proj.tcl and the .sdc
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

.PHONY: generate build program-sof assert-device quartus-clean microcode

# The microcode is not board-specific in any way -- every Altera board ran the
# identical two lines -- and `serial` is the mode these flows boot in, which is
# also what UCODE above watches. The EP4CGX150 copy of this additionally had a
# `microcode-flash` target invoking `make flash` in asm/, and asm/Makefile has
# no `flash` target: it is `flash-altera`. Dead, and no one noticed, because
# nothing depended on it.
microcode:
	cd $(PROJECT_ROOT)/asm && $(MAKE) serial
	@echo "=== Serial microcode in $(PROJECT_ROOT)/build/microcode/serial ==="

# WHICH GENERATOR RUNS, and whether it emits the project itself.
#
# Most flows here are JopConfig presets: JopTopVerilog emits the RTL, and the
# .sdc and setup_proj.tcl come from two further generator mains. The EXERCISERS
# are not presets -- they are standalone BoardDesigns whose single `<Name>Build`
# main emits RTL, PLL, .sdc and setup_proj.tcl in one pass -- so they set
# GEN_MAIN and GEN_MAKES_PROJECT=yes and skip those two rules.
#
# This exists because the EP4CGX150 grew a `define build_exerciser` macro
# carrying its own copy of quartus_sh + the four quartus_* commands: a SIXTH
# copy of the flow, added by the same commit that deleted the other five. The
# only thing that genuinely varies is which main runs and whether it wrote the
# project, so those are the only two things parameterised.
GEN_MAIN          ?= jop.system.JopTopVerilog
GEN_ARGS          ?= $(CFG) buildtree
GEN_MAKES_PROJECT ?= no

$(GEN_STAMP): $(SCALA_SRC) $(UCODE)
	cd $(PROJECT_ROOT) && sbt "runMain $(GEN_MAIN) $(GEN_ARGS)"
	@mkdir -p $(dir $@) && touch $@

ifeq ($(GEN_MAKES_PROJECT),yes)

# The generator already wrote setup_proj.tcl and the .sdc; only Quartus's own
# pass over the Tcl remains.
$(QUARTUS_PRJ)/$(REV).qsf: $(GEN_STAMP)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sh -t setup_proj.tcl

else

$(QUARTUS_PRJ)/$(REV).sdc:
	cd $(PROJECT_ROOT) && sbt "runMain jop.generate.TimingConstraintsMain $(CFG) --write build/$(CFG_NAME)/quartus/$(REV).sdc"

$(QUARTUS_PRJ)/setup_proj.tcl:
	cd $(PROJECT_ROOT) && sbt "runMain jop.generate.QuartusProjectMain $(CFG) --revision $(REV) --write build/$(CFG_NAME)/quartus/setup_proj.tcl"

# Quartus writes the .qsf/.qpf itself, from our Tcl, so their format follows the
# tool version rather than being frozen by hand.
$(QUARTUS_PRJ)/$(REV).qsf: $(QUARTUS_PRJ)/setup_proj.tcl $(QUARTUS_PRJ)/$(REV).sdc
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sh -t setup_proj.tcl

endif

generate: $(GEN_STAMP)
	@echo "=== Generated into $(CFG_DIR)/rtl ==="

$(SOF_FILE): $(GEN_STAMP) $(QUARTUS_PRJ)/$(REV).qsf
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_map $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_fit $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_asm $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sta $(REV)
	@echo "=== Build complete: $(SOF_FILE) ==="

build: $(SOF_FILE)

# A read-only JTAG chain scan that refuses unless the FPGA on the cable is the
# one BOARD_ALIAS names. Every .sof program blocks on it for TWO reasons:
#
#   1. quartus_pgm EXITS 0 ON A BROKEN CHAIN. Powered-off board, JTAG header
#      unplugged, level shifter with no Vref -- all of them "succeed". This
#      scan is the only thing in the flow that notices. (Observed 2026-08-29:
#      both Altera boards were off and every tool reported success.)
#   2. Two USB-Blasters are attached to this host, so a mis-resolved cable
#      programs the OTHER board -- also silently.
#
# Cheap, configures nothing, and passes with a note for an alias that has no
# IDCODE recorded. Boards that program some other way (a .rbf through
# openFPGALoader, say) define their own `program` and should depend on this
# too.
assert-device:
	@test -z "$(BOARD_ALIAS)" \
	  || $(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --assert-device $(BOARD_ALIAS)

# The cable is resolved by SERIAL, never by a bare "USB-Blaster": more than one
# blaster is attached to this host and a bare name takes whichever enumerated
# first. Takes the .sof directly, so no .cdf is needed.
# Named program-sof, not program: some boards load a .rbf or go through
# openFPGALoader instead, and they define their own `program`. A board whose
# programming is just "quartus_pgm the .sof" writes `program: program-sof`.
program-sof: $(SOF_FILE) assert-device
	$(QUARTUS_BIN)/quartus_pgm \
	    -c "$$($(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --cable $(BOARD_ALIAS))" \
	    -m JTAG -o "p;$(SOF_FILE)"
	@echo "=== FPGA programmed: $(SOF_FILE) ==="

quartus-clean:
	rm -rf $(CFG_DIR)

# The console is not Quartus-specific, but every board that uses this flow needs
# it, and it must come after CFG_DIR is defined -- it reads the build's summary.
include $(dir $(lastword $(MAKEFILE_LIST)))console.mk
