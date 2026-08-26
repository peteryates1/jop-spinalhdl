# ---------------------------------------------------------------------------
# The Vivado flow, once. Sibling of quartus.mk.
#
# The board sets:
#   PROJECT_ROOT   path to the repo root
#   CFG            preset invocation, e.g. wukongDdr3
#   BUILD_TCL      the board's build script, relative to the board directory
#   BITSTREAM      the .bit's name, e.g. JopDdr3WukongTop.bit
#   BOARD_ALIAS    for jtag_probe_map
#   CONSOLE_ALIAS  for usb_serial_map
# and may set:
#   LOADER_CABLE   openFPGALoader -c value, default dirtyJtag
#
# WHY. The Vivado boards each carried their own copy of the Vivado path, the
# locale export, the openFPGALoader invocation and the JOP_CFG_DIR plumbing. The
# probe selection is the part that matters: MORE THAN ONE dirtyJtag probe is
# attached to this host, and a bare `-c dirtyJtag` takes whichever enumerated
# first -- i.e. possibly another board. One board had no --busdev-num at all.
# ---------------------------------------------------------------------------

VIVADO      ?= /opt/xilinx/2025.2/Vivado/bin/vivado
VIVADO_ENV  ?= export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
LOADER_CABLE ?= dirtyJtag

ifeq ($(words $(CFG)),1)
  CFG_NAME := $(CFG)
else
  CFG_NAME := $(shell cd $(PROJECT_ROOT) && sbt "runMain jop.generate.BuildLayoutMain $(CFG)" 2>/dev/null \
                | sed -n 's|^\[info\] build/\(.*\)$$|\1|p' | tail -1)
endif

CFG_DIR    = $(PROJECT_ROOT)/build/$(CFG_NAME)
GEN_STAMP  = $(CFG_DIR)/rtl/.generated
BIT_FILE   = $(CFG_DIR)/vivado/build/$(BITSTREAM)
SCALA_SRC := $(shell find $(PROJECT_ROOT)/spinalhdl/src/main/scala -name '*.scala')
UCODE     := $(wildcard $(PROJECT_ROOT)/build/microcode/serial/mem_*.dat)

# Resolved by SERIAL. A bogus --busdev-num must FAIL for this to mean anything;
# it does, with the patched openFPGALoader in /usr/local/bin.
BUSDEV ?= --busdev-num $(shell $(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --busdev $(BOARD_ALIAS))

.PHONY: generate build program-bit vivado-clean

$(GEN_STAMP): $(SCALA_SRC) $(UCODE)
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog $(CFG) buildtree"
	@mkdir -p $(dir $@) && touch $@

generate: $(GEN_STAMP)
	@echo "=== Generated into $(CFG_DIR)/rtl ==="

build: generate
	$(VIVADO_ENV) && JOP_CFG_DIR=$(PROJECT_ROOT)/build/$(CFG_NAME) \
	    $(VIVADO) -mode batch -source $(BUILD_TCL)

program-bit:
	sudo openFPGALoader -c $(LOADER_CABLE) $(BUSDEV) $(BIT_FILE)
	@echo "=== programmed: $(BIT_FILE) ==="

vivado-clean:
	rm -rf $(CFG_DIR)

include $(dir $(lastword $(MAKEFILE_LIST)))console.mk
