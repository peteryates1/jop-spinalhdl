# SpinalHDL project root (relative to java/) and the runtime source tree.
# Both must be set before config.mk is included -- it builds paths from them.
PROJECT_ROOT := ..
JAVA_DIR     := .
TARGET_DIR   := runtime

include config.mk

# Where the classes and .jop images go: build/<config>/java/, beside Const.java.
# BUILDTREE is still exported so a board Makefile passing BUILDTREE=1 keeps
# working; nothing reads it any more.
export JOP_PRESET BUILDTREE JOP_CFG_DIR JAVA_OUT GEN_SRC CONST_EXCLUDE
export TOOLS_OUT COMMON_TOOLS_OUT JOPIZER_JAR JOPSIM_JAR JOPA_JAR LINKER_PROPS LINKER_FLAG

# THE PRESET STAMP IS GONE, because the thing it worked around is gone.
#
# Const.java is per-configuration -- ep4cgx150Serial and wukongSmp differ in
# SUPPORT_FLOAT, for instance -- and it used to be generated into a SHARED
# source tree. Make cannot depend on the VALUE of a variable, only on files, so
# a stamp file recorded which preset the current Const.java came from and was
# made a prerequisite of it. Without it, changing JOP_PRESET did not rebuild
# (demonstrated 2026-08-24: `make generate-const JOP_PRESET="wukongSmp 4"`
# then `JOP_PRESET=ep4cgx150Serial` printed "Nothing to be done", and every
# .jop built afterwards for the EP4CGX150 carried the Wukong SUPPORT_FLOAT).
# It was labelled a stopgap: "the real fix is per-config generation under
# build/<config>/".
#
# That is now the only layout, so CONST_JAVA is a DIFFERENT PATH for every
# configuration and make invalidates on the filename alone. Keeping the stamp
# would be strictly worse than removing it: building A, then B, then A again
# leaves A's Const.java correct and up to date, and the stamp would force a
# needless regeneration of it.

.PHONY: all tools runtime apps test-apps clean sim-smallest sim-small generate-const

all: tools runtime apps

# Generate Const.java from JopConfig if missing or stale
generate-const: $(CONST_JAVA)

# EVERYTHING THAT DECIDES AN I/O ADDRESS, not just the generator. The four
# io/ and memory/ files below were missing (status item 120): the RTL
# regenerates from Scala on every build, so moving a device or changing an
# addrBits moved the hardware while Const.java reported "Nothing to be done",
# and the .jop then addressed the wrong peripheral -- TROUBLESHOOTING.md's
# first entry, and a board that looks dead.
#
# Same defect as fpga/quartus.mk's .sdc, one directory over: a generated
# artefact whose prerequisites omit an input that determines its content.
$(CONST_JAVA): \
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

# The image and its link map are build products, so they come from JAVA_OUT --
# these two recipes still named apps/<X>/HelloWorld.jop in the source tree
# after everything else had moved, and nothing noticed because no CI job runs
# either target.
sim-smallest: all
	java -cp $(JOPSIM_JAR) -Dtrace=2 com.jopdesign.tools.JopSim \
		-link $(JAVA_OUT)/apps/Smallest/HelloWorld.jop.link.txt \
		$(JAVA_OUT)/apps/Smallest/HelloWorld.jop

sim-small: all
	java -cp $(JOPSIM_JAR) -Dtrace=2 com.jopdesign.tools.JopSim \
		-link $(JAVA_OUT)/apps/Small/HelloWorld.jop.link.txt \
		$(JAVA_OUT)/apps/Small/HelloWorld.jop

clean:
	$(MAKE) -C tools clean
	$(MAKE) -C runtime clean
	$(MAKE) -C apps/Smallest clean
	$(MAKE) -C apps/Small clean
	$(MAKE) -C apps/InterruptTest clean
