# ---------------------------------------------------------------------------
# THE UNIT A BUILD RUNS ON: its probe and its console (status item 173, step 3).
# Included by quartus.mk, vivado.mk and console.mk, after CFG_DIR is defined.
#
# Which physical unit a preset is built for, and how that unit is reached, is
# the lab's setup (setup/units.toml), not the board Makefile's. Until
# 2026-11-01 thirteen board Makefiles named BOARD_ALIAS and CONSOLE_ALIAS
# themselves -- a second copy of the units, which a second unit of the same
# board could not change. Now the descriptor answers, with the same selection
# hw_verify uses: the unit for the preset in CFG, or the one INSTANCE names,
# and the console wired to the preset's UART. The answer is cached per config
# in $(UNIT_DESC) and remade when the units or the configs change, so the
# first lookup costs one sbt start and the rest cost a sed.
#
# Only targets that reach hardware depend on $(UNIT_DESC); a build does not.
# SYSTEM=<name> picks one system's console in a multi-system build (the Wukong
# dual builds: SYSTEM=ddr3 or SYSTEM=sdr); without it, the first system's.
#
# A design no unit covers (the MAX1000, the generic EP4CE6) gets no probe and
# no console: assert-device skips, program and download refuse.
# ---------------------------------------------------------------------------
ifndef JOP_UNIT_MK
JOP_UNIT_MK := 1

JOP_SETUP_DIR = $(or $(JOP_SETUP),$(PROJECT_ROOT)/setup)
UNIT_DESC     = $(CFG_DIR)/unit$(if $(INSTANCE),.$(INSTANCE)).desc
UNIT_DESC_SRC := $(wildcard $(JOP_SETUP_DIR)/units.toml) \
                 $(shell find $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/config \
                              $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/setup -name '*.scala') \
                 $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/generate/HwVerifyDescriptor.scala

# The design described: the preset in CFG or, for a board design built by its
# own main (the exercisers: GEN_MAIN=jop.system.XxxBuild), its design object,
# jop.system.XxxDesign -- such a CFG names a directory, not a preset.
UNIT_DESIGN = $(if $(filter %Build,$(GEN_MAIN)),$(GEN_MAIN:Build=Design),$(CFG))

# --write takes a path from the repository root, where sbt runs.
$(CFG_DIR)/unit%desc: $(UNIT_DESC_SRC)
	@mkdir -p $(CFG_DIR)
	cd $(PROJECT_ROOT) && sbt -error "runMain jop.generate.HwVerifyDescriptorMain $(UNIT_DESIGN) \
	  $(if $(INSTANCE),--instance $(INSTANCE)) --write build/$(CFG_NAME)/$(notdir $@)"
	@test -s $@ || { echo "make: no unit descriptor for CFG=$(CFG)" >&2; exit 1; }

# One line of the descriptor; empty when the unit (or the descriptor) has none.
unit_fact     = $(shell sed -n 's/^$(1)=//p' $(UNIT_DESC) 2>/dev/null)
BOARD_ALIAS   = $(call unit_fact,PROBE_ALIAS)
CONSOLE_ALIAS = $(call unit_fact,CONSOLE_ALIAS$(if $(SYSTEM),_$(SYSTEM)))

.PHONY: unit-info
unit-info: $(UNIT_DESC)
	@echo "unit    : $(or $(call unit_fact,INSTANCE),$(if $(BOARD_ALIAS),(the only unit of this assembly),none))"
	@echo "probe   : $(or $(BOARD_ALIAS),none)"
	@echo "console : $(or $(CONSOLE_ALIAS),none)$(if $(SYSTEM), (system $(SYSTEM)))"

endif
