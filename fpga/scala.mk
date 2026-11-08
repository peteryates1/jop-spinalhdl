# ---------------------------------------------------------------------------
# EVERY SCALA SOURCE THE GENERATORS ARE BUILT FROM (status item 173), and the
# generated microcode tables among them.
# Included by quartus.mk, vivado.mk and the ECP5 boards, after PROJECT_ROOT.
#
# A flow re-elaborates when one of these is newer than its stamp. Since
# 2026-11-03 the sbt build has two projects -- generic JOP and its support for
# hardware under spinalhdl/, the hardware layer (boards, parts, PLL builders)
# under hardware/ -- and every flow searched spinalhdl/ alone, in four copies
# of one line: a changed pin or PLL builder would have rebuilt nothing, and
# the build would have succeeded on the previous run's RTL and constraints.
#
# So the search covers every top-level directory with a src/main/scala, which
# is each sbt project's, and a new project needs no edit here.
# .github/scripts/check-generated-deps.sh holds every flow to the tree.
# ---------------------------------------------------------------------------
ifndef JOP_SCALA_MK
JOP_SCALA_MK := 1

SCALA_SRC_DIRS := $(wildcard $(PROJECT_ROOT)/*/src/main/scala)
SCALA_SRC      := $(shell find $(SCALA_SRC_DIRS) -name '*.scala')

# AND THE GENERATED ONES. build.sbt compiles the microcode tables as sources
# (its build/microcode/* roots), so every root-project sbt run needs them, and
# a rule that runs one lists $(UCODE_SCALA). They are deliberately NOT a
# $(wildcard): a wildcard prerequisite expands to EMPTY when the files are
# absent, so `$(GEN_STAMP): ... $(UCODE)` said "depend on microcode only once
# microcode already exists" -- backwards for a cold build. It made
# cold-buildability depend on whether each board's own `all` happened to list
# `microcode`, and on 2026-08-29 three boards that did not failed with
#
#   object JumpTableData is not a member of package jop
#
# The rule lived in vivado.mk and quartus.mk then; the flows outside them (the
# ECP5 boards, the cluster carrier) never had it, and could not build from a
# cold clone until 2026-11-08. check-generated-deps.sh now holds every flow to it.
# Grouped target (`&:`, GNU make 4.3+) so ONE `asm all` satisfies all three,
# rather than the recipe running once per missing file.
UCODE_SCALA := $(PROJECT_ROOT)/build/microcode/simulation/JumpTableData.scala \
               $(PROJECT_ROOT)/build/microcode/serial/SerialJumpTableData.scala \
               $(PROJECT_ROOT)/build/microcode/flash/FlashJumpTableData.scala

$(UCODE_SCALA) &:
	cd $(PROJECT_ROOT)/asm && $(MAKE) all

endif
