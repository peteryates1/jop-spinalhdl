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

# The microcode files the Scala compile actually needs. Deliberately NOT a
# $(wildcard): a wildcard prerequisite expands to EMPTY when the files are
# absent, so `$(GEN_STAMP): ... $(UCODE)` said "depend on microcode only once
# microcode already exists" -- backwards for a cold build. It made
# cold-buildability depend on whether each board's own `all` happened to list
# `microcode`, and on 2026-08-29 three boards that did not failed with
#
#   object JumpTableData is not a member of package jop
#
# These three files are exactly what build.sbt's source roots require. Grouped
# target (`&:`, GNU make 4.3+) so ONE `asm all` satisfies all three, rather than
# the recipe running once per missing file.
UCODE_SCALA := $(PROJECT_ROOT)/build/microcode/simulation/JumpTableData.scala \
               $(PROJECT_ROOT)/build/microcode/serial/SerialJumpTableData.scala \
               $(PROJECT_ROOT)/build/microcode/flash/FlashJumpTableData.scala

$(UCODE_SCALA) &:
	cd $(PROJECT_ROOT)/asm && $(MAKE) all

# THE EMBEDDED PROGRAM. A BRAM design that does not serial-boot bakes a .jop
# into the bitstream, and nothing in any board flow built it -- so those designs
# could not be built from a clean clone at all. They only worked because someone
# had once run `make -C java all` WITHOUT BUILDTREE, leaving the artefact in the
# source tree. Building it here, into build/<config>/java, is what lets the
# source tree hold no build products.
#
# Cheap when up to date, and it is the same file console.mk downloads, so
# `make all && make download` now works from cold.
# Smallest is a STAMP, not necessarily the app this config embeds: `java all`
# builds Smallest, Small and InterruptTest in one pass, so depending on one of
# them gets all of them. Which app a preset actually bakes in is decided in
# JopTopVerilog (`appRel`) and is deliberately not restated here -- that mapping
# is configuration, and configuration lives in the Scala.
# ONLY FOR PRESETS. A standalone top -- an exerciser, a flash programmer --
# has no JopConfig preset behind it, so `make -C java ... JOP_PRESET=<cfg>`
# cannot resolve one and ConstGeneratorMain fails; and it embeds no program
# anyway. Keyed off the generator because that is exactly what distinguishes
# the two: JopTopVerilog builds a preset, anything else builds a standalone
# top.
# THE DEFAULT MUST PRECEDE THE ifeq BELOW. Make evaluates conditionals at parse
# time in file order, so testing GEN_MAIN before its `?=` runs compares against
# an EMPTY value: every preset board fell to the else branch, JOP_APP_FILE came
# out empty, and the rule that builds the demo .jop never became a prerequisite
# of anything. Silently -- the builds all succeeded, and only `make download`
# failed, much later, on a file nothing had created.
GEN_MAIN          ?= jop.system.JopTopVerilog
ifeq ($(GEN_MAIN),jop.system.JopTopVerilog)
JOP_APP_FILE = $(CFG_DIR)/java/apps/Smallest/HelloWorld.jop
else
JOP_APP_FILE =
endif

$(JOP_APP_FILE):
	cd $(PROJECT_ROOT)/java && $(MAKE) all JOP_PRESET="$(CFG)" BUILDTREE=1


.PHONY: generate build program-sof assert-device quartus-clean microcode

# The microcode is not board-specific in any way -- every Altera board ran the
# identical two lines -- and `serial` is the mode these flows boot in, which is
# also what UCODE above watches. The EP4CGX150 copy of this additionally had a
# `microcode-flash` target invoking `make flash` in asm/, and asm/Makefile has
# no `flash` target: it is `flash-altera`. Dead, and no one noticed, because
# nothing depended on it.
# `all`, NOT `serial`, even though serial is the mode these flows boot in.
#
# build.sbt declares all THREE microcode directories as Scala source roots
# (simulation, serial, flash) and JumpTable.scala references all three objects
# unconditionally -- `def flash: JumpTableInitData = from(FlashJumpTableData)`.
# So every sbt compile needs all three generated, whatever the board boots
# from. Building only `serial` leaves FlashJumpTableData undefined and the
# FIRST sbt invocation of a cold build fails:
#
#   not found: value FlashJumpTableData
#
# Invisible in a working tree, because build/microcode/flash survives from some
# earlier build and nothing invalidates it. Found 2026-08-29 by cold-building a
# fresh clone. CI already used `all` for exactly this reason -- its comment even
# records the flash variant going 16 days stale locally while CI stayed green --
# so the board flows were the half that never got the fix.
microcode:
	cd $(PROJECT_ROOT)/asm && $(MAKE) all
	@echo "=== Microcode in $(PROJECT_ROOT)/build/microcode ==="

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
GEN_ARGS          ?= $(CFG) buildtree
GEN_MAKES_PROJECT ?= no

$(GEN_STAMP): $(SCALA_SRC) $(UCODE) $(UCODE_SCALA) $(JOP_APP_FILE)
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
