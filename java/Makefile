# SpinalHDL project root (relative to java/) and the runtime source tree.
# Both must be set before config.mk is included -- it builds paths from them.
PROJECT_ROOT := ..
JAVA_DIR     := .
TARGET_DIR   := runtime

include config.mk

# Where the classes and .jop images go. Under BUILDTREE=1 they follow Const.java
# into build/<config>/java/; otherwise they stay in the tree, as before.
export JOP_PRESET BUILDTREE JOP_CFG_DIR JAVA_OUT GEN_SRC CONST_EXCLUDE

# Records which preset the CURRENT Const.java was generated from. Make cannot
# depend on the VALUE of a variable, only on files, so the value is written to
# a file and that file is a prerequisite of Const.java.
#
# WHY. Const.java is per-configuration -- ep4cgx150Serial and wukongSmp differ
# in SUPPORT_FLOAT, for instance -- but it is generated into a SHARED source
# tree. Without this stamp the only prerequisites were the three generator Scala
# files, so changing JOP_PRESET did not rebuild it. Demonstrated 2026-08-24:
#
#   make generate-const JOP_PRESET="wukongSmp 4"   -> SUPPORT_FLOAT = false
#   make generate-const JOP_PRESET=ep4cgx150Serial -> "Nothing to be done"
#
# and every .jop built afterwards for the EP4CGX150 carried the Wukong's value.
# The real fix is per-config generation under build/<config>/; this makes the
# shared file at least self-invalidating in the meantime.
# Redundant under BUILDTREE=1 -- there the path itself carries the config -- but
# kept because the legacy path is still the default.
CONST_STAMP := $(dir $(CONST_JAVA)).const-preset

.PHONY: all tools runtime apps test-apps clean sim-smallest sim-small generate-const FORCE

all: tools runtime apps

# Generate Const.java from JopConfig if missing or stale
generate-const: $(CONST_JAVA)

# Rewritten only when the preset actually changes, so its mtime does not move on
# every make and Const.java is not needlessly regenerated.
FORCE:

$(CONST_STAMP): FORCE
	@mkdir -p $(dir $@)
	@printf '%s\n' '$(JOP_PRESET)' | cmp -s - $@ || printf '%s\n' '$(JOP_PRESET)' > $@

# EVERYTHING THAT DECIDES AN I/O ADDRESS, not just the generator. The four
# io/ and memory/ files below were missing (status item 120): the RTL
# regenerates from Scala on every build, so moving a device or changing an
# addrBits moved the hardware while Const.java reported "Nothing to be done",
# and the .jop then addressed the wrong peripheral -- TROUBLESHOOTING.md's
# first entry, and a board that looks dead.
#
# Same defect as fpga/quartus.mk's .sdc, one directory over: a generated
# artefact whose prerequisites omit an input that determines its content.
$(CONST_JAVA): $(CONST_STAMP) \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/generate/ConstGenerator.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/config/JopConfig.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/config/JopCoreConfig.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/io/IoAddressAllocator.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/io/DeviceTypes.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/config/DeviceInstance.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/memory/JopMemoryConfig.scala
	cd $(PROJECT_ROOT) && sbt -error "runMain jop.generate.ConstGeneratorMain $(CONST_ARGS)"

tools: $(CONST_JAVA)
	$(MAKE) -C tools

runtime: $(CONST_JAVA)
	$(MAKE) -C runtime

apps: tools runtime
	$(MAKE) -C apps/Smallest
	$(MAKE) -C apps/Small
	$(MAKE) -C apps/InterruptTest

# EVERY app any documented simulation loads.
#
# `apps` above builds three HelloWorlds, which covers the first few README
# steps and nothing else. The rest of the documented sims -- the SMP ones, the
# JVM suite, the GC stress test, the performance counters -- each load a
# different .jop that NO build step produced, so they failed on a missing file
# for anyone following the README. A cold-newcomer walkthrough on 2026-08-29
# hit that seven times over; it was one root cause wearing seven hats.
#
# Keep this in step with the simulations the README lists. A sim whose .jop is
# not built here is a sim that a reader cannot run.
.PHONY: test-apps
test-apps: apps
	$(MAKE) -C apps/Small APP_NAME=GcStressTest
	$(MAKE) -C apps/Small APP_NAME=NCoreHelloWorld
	$(MAKE) -C apps/JvmTests
	$(MAKE) -C apps/SmpGcTest
	$(MAKE) -C apps/JbeBench

sim-smallest: all
	java -cp tools/dist/jopsim.jar -Dtrace=2 com.jopdesign.tools.JopSim \
		-link apps/Smallest/HelloWorld.jop.link.txt apps/Smallest/HelloWorld.jop

sim-small: all
	java -cp tools/dist/jopsim.jar -Dtrace=2 com.jopdesign.tools.JopSim \
		-link apps/Small/HelloWorld.jop.link.txt apps/Small/HelloWorld.jop

clean:
	$(MAKE) -C tools clean
	$(MAKE) -C runtime clean
	$(MAKE) -C apps/Smallest clean
	$(MAKE) -C apps/Small clean
	$(MAKE) -C apps/InterruptTest clean
