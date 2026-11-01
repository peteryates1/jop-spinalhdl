# ---------------------------------------------------------------------------
# THIS HOST'S TOOLS, from the setup (status item 173, step 4).
#
# Where Quartus, Vivado, Questa and the JDKs are installed is this host's
# fact, not the flows': setup/host.toml (or $JOP_SETUP/host.toml) maps each
# tool and version to its directory. This fragment makes them Make variables,
# JOP_HOST_<tool>_<version>, through a cache in build/setup/ that make remakes
# when host.toml changes (one python3 run), and gives
#
#   $(call host_tool,<tool>,<version>,<VARIABLE>)
#
# which a flow uses as the DEFAULT of its own variable:
#
#   QUARTUS_DIR ?= $(call host_tool,quartus,$(QUARTUS_VERSION),QUARTUS_DIR)
#
# The flow names the tool and the version it needs; the host says where that
# is. Setting the variable (environment, command line) still wins -- CI sets
# TARGET_JDK_HOME so. With neither it nor a host.toml entry, the first use
# stops make and says which to add.
# ---------------------------------------------------------------------------
ifndef JOP_HOST_MK
JOP_HOST_MK := 1

JOP_ROOT       := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
JOP_HOST_SETUP := $(or $(JOP_SETUP),$(JOP_ROOT)/setup)
JOP_HOST_TOML  := $(abspath $(JOP_HOST_SETUP)/host.toml)
# One cache per setup, so pointing JOP_SETUP elsewhere never reads another's.
JOP_HOST_CACHE := $(JOP_ROOT)/build/setup/host$(subst /,_,$(JOP_HOST_TOML)).mk

# The cache's rule must not become the including Makefile's default goal.
JOP_HOST_GOAL := $(.DEFAULT_GOAL)
$(JOP_HOST_CACHE): $(wildcard $(JOP_HOST_TOML)) $(JOP_ROOT)/fpga/scripts/jop_setup.py
	@mkdir -p $(dir $@)
	@python3 $(JOP_ROOT)/fpga/scripts/jop_setup.py --setup $(JOP_HOST_SETUP) host-mk > $@.tmp \
	  && mv $@.tmp $@
.DEFAULT_GOAL := $(JOP_HOST_GOAL)
-include $(JOP_HOST_CACHE)

host_tool = $(or $(JOP_HOST_$(1)_$(2)),$(error this host has no $(1) $(2): add it to \
  $(JOP_HOST_TOML) as [$(1)] "$(2)" = "/its/dir", or set $(3)))

endif
