# ---------------------------------------------------------------------------
# The Vivado flow, once. Sibling of quartus.mk.
#
# The board sets:
#   PROJECT_ROOT   path to the repo root
#   CFG            preset invocation, e.g. wukongDdr3 or "wukongSmp 4"
#   BOARD_ALIAS    for jtag_probe_map
#   CONSOLE_ALIAS  for usb_serial_map
# and may set:
#   VIVADO         default /opt/xilinx/2025.2/Vivado/bin/vivado
#   LOADER_CABLE   openFPGALoader -c value, default dirtyJtag
#   GEN_MAIN       generator main, default jop.system.JopTopVerilog
#   GEN_ARGS       its arguments, default "$(CFG) buildtree"
#
# REWRITTEN 2026-08-26. The first version wrapped a per-board BUILD_TCL and a
# BITSTREAM name, which is the wrong shape now that the build itself lives in
# fpga/scripts/vivado_build_{project,nonproject}.tcl. What is genuinely shared
# is what remains here: resolving the config directory, generating the RTL once
# per change, programming by serial, and the console.
#
# It was also included by NO board for two days -- written for the two Xilinx
# boards and then never wired up, so nothing exercised it. A shared include
# nobody includes is indistinguishable from a broken one.
#
# WHY THE CONFIG DIRECTORY COMES FROM SCALA. `wukongSmp 4` lands in
# build/wukongSmp-4, and the Wukong Makefile used to spell that out as
#     DDR3_SMP_DIR = $(REPO_ROOT)/build/wukongSmp-$(DDR3_SMP_CORES)
# -- a second copy of BuildLayout.configName's sanitising rules, in Make. Two
# copies of a naming rule stay in step until the day they do not, and the
# failure is a path that silently points at a stale directory or none at all.
# BuildLayoutMain is asked instead.
# ---------------------------------------------------------------------------

VIVADO       ?= /opt/xilinx/2025.2/Vivado/bin/vivado
VIVADO_ENV   ?= export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
LOADER_CABLE ?= dirtyJtag

# A bare preset is a Scala identifier, so sanitising it is the identity and
# costs no call; only a multi-argument invocation pays for the sbt round trip.
ifeq ($(words $(CFG)),1)
  CFG_NAME := $(CFG)
else
  CFG_NAME := $(shell cd $(PROJECT_ROOT) && sbt "runMain jop.generate.BuildLayoutMain $(CFG)" 2>/dev/null \
                | sed -n 's|^\[info\] build/\(.*\)$$|\1|p' | tail -1)
  ifeq ($(CFG_NAME),)
    $(error BuildLayoutMain produced no directory for CFG="$(CFG)")
  endif
endif

CFG_DIR     = $(PROJECT_ROOT)/build/$(CFG_NAME)
VIVADO_PRJ  = $(CFG_DIR)/vivado
VIVADO_BUILD= $(VIVADO_PRJ)/build

# A STAMP, not the .v: the entity name is derived from the config, so naming
# the file here would restate config in Make and be wrong for every SMP build.
GEN_STAMP   = $(CFG_DIR)/rtl/.generated
SCALA_SRC  := $(shell find $(PROJECT_ROOT)/spinalhdl/src/main/scala -name '*.scala')
UCODE      := $(wildcard $(PROJECT_ROOT)/build/microcode/serial/mem_*.dat)

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
ifeq ($(GEN_MAIN),jop.system.JopTopVerilog)
JOP_APP_FILE = $(CFG_DIR)/java/apps/Smallest/HelloWorld.jop
else
JOP_APP_FILE =
endif

$(JOP_APP_FILE):
	cd $(PROJECT_ROOT)/java && $(MAKE) all JOP_PRESET="$(CFG)" BUILDTREE=1


GEN_MAIN    ?= jop.system.JopTopVerilog
GEN_ARGS    ?= $(CFG) buildtree

# The shared Vivado scripts. The board passes the design's fields to these
# rather than keeping its own copy of the flow -- see their headers.
CREATE_TCL  = $(PROJECT_ROOT)/fpga/scripts/vivado_create_project.tcl
PRJ_TCL     = $(PROJECT_ROOT)/fpga/scripts/vivado_build_project.tcl
NP_TCL      = $(PROJECT_ROOT)/fpga/scripts/vivado_build_nonproject.tcl

.PHONY: generate program-bit vivado-clean microcode

# The Vivado side had NO microcode rule, so a cold build of a Vivado board
# failed before it reached Vivado at all. See the long note in quartus.mk for
# why this is `all` and not `serial`: every sbt compile needs all three
# variants generated, regardless of which one the board boots from.
microcode:
	cd $(PROJECT_ROOT)/asm && $(MAKE) all
	@echo "=== Microcode in $(PROJECT_ROOT)/build/microcode ==="

$(GEN_STAMP): $(SCALA_SRC) $(UCODE) $(UCODE_SCALA) $(JOP_APP_FILE)
	cd $(PROJECT_ROOT) && sbt "runMain $(GEN_MAIN) $(GEN_ARGS)"
	@mkdir -p $(dir $@) && touch $@

generate: $(GEN_STAMP)
	@echo "=== Generated into $(CFG_DIR)/rtl ==="

# Resolved by SERIAL. More than one dirtyJtag probe is attached to this host, so
# a bare `-c dirtyJtag` takes whichever enumerated first -- i.e. possibly
# another board. Needs the PATCHED openFPGALoader in /usr/local/bin; a bogus
# `--busdev-num 099:099` must FAIL for the selection to mean anything.
BUSDEV ?= --busdev-num $(shell $(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --busdev $(BOARD_ALIAS))

# The board sets BIT_FILE for the flow being programmed; a board with several
# flows re-enters this with a different one rather than repeating the command.
program-bit:
	sudo openFPGALoader -c $(LOADER_CABLE) $(BUSDEV) $(BIT_FILE)
	@echo "=== programmed: $(BIT_FILE) ==="

vivado-clean:
	rm -rf $(CFG_DIR)

# Must come after CFG_DIR is defined -- it reads the build's own summary for
# the baud rather than trusting a Makefile constant. See its header, status 70.
include $(dir $(lastword $(MAKEFILE_LIST)))console.mk

# Lets a board ask for another flow's config directory without restating
# BuildLayout's naming rules in Make.
.PHONY: print-cfg-dir
print-cfg-dir:
	@echo $(CFG_DIR)
