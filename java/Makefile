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

.PHONY: all tools runtime apps test-apps clean sim-smallest sim-small jopsim-hello generate-const

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
# The board instances too (status items 173 and 174): a unit's MAC and
# hostname are in Const.java, so a changed MAC must regenerate it. They are
# setup/units.toml (or $JOP_SETUP/units.toml), read by jop/setup/*.scala. A lab
# with no setup has no units, and its images keep the shared MAC.
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
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/memory/JopMemoryConfig.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/config/UnitFacts.scala \
               $(wildcard $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/setup/*.scala) \
               $(wildcard $(or $(JOP_SETUP),$(PROJECT_ROOT)/setup)/units.toml)
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
# not built here is a sim that a reader cannot run. CardMarkTest is for
# JopCardMarkSim and JopCardClearStallSim; it was missing until 2026-10-13
# (status item 139), and CI's card-table job builds it for itself.
.PHONY: test-apps
test-apps: apps
	$(MAKE) -C apps/Small APP_NAME=GcStressTest
	$(MAKE) -C apps/Small APP_NAME=NCoreHelloWorld
	$(MAKE) -C apps/Small APP_NAME=CardMarkTest
	$(MAKE) -C apps/JvmTests
	$(MAKE) -C apps/SmpGcTest
	$(MAKE) -C apps/JbeBench

# The image and its link map are build products, so they come from JAVA_OUT --
# these two recipes still named apps/<X>/HelloWorld.jop in the source tree
# after everything else had moved, and nothing noticed because no CI job runs
# either target. Both print a method trace (-Dtrace=2) and run until
# interrupted; `jopsim-hello` below runs the same Smallest image to a verdict,
# and CI runs that.
sim-smallest: all
	java -cp $(JOPSIM_JAR) -Dtrace=2 com.jopdesign.tools.JopSim \
		-link $(JAVA_OUT)/apps/Smallest/HelloWorld.jop.link.txt \
		$(JAVA_OUT)/apps/Smallest/HelloWorld.jop

sim-small: all
	java -cp $(JOPSIM_JAR) -Dtrace=2 com.jopdesign.tools.JopSim \
		-link $(JAVA_OUT)/apps/Small/HelloWorld.jop.link.txt \
		$(JAVA_OUT)/apps/Small/HelloWorld.jop

# JOPSIM RUNS AN IMAGE, AND CI WATCHES IT DO SO (status item 154). The README
# walk-through runs this as step 3b on every push.
#
# JopSim stopped in GC.init on every image from 2026-03-01 to 2026-11-11, and
# nothing ran it, so nobody saw. It answered 0 for
# IO_MEM_SIZE, so Startup put the heap 1 MB past the image, beyond JopSim's own
# 1 MB. And its microsecond counter was the wall clock cut to 32 bits, negative
# for 36 minutes in every 72, so HelloWorld (`n = 0; if (n <= c)`) could still
# print nothing.
#
# HelloWorld never exits, so JopSim stops it after JOPSIM_BUDGET bytecodes.
# Its first line comes about 540,000 bytecodes in (2026-11-11); 3M is 5.5x
# that and runs in under a second.
# The verdict is HelloWorld's own line in JopSim's output, never JopSim's exit
# status alone: the counter fault let JopSim run to its budget and exit 0
# having printed nothing.
JOPSIM_BUDGET ?= 3000000
JOPSIM_LOG    := $(JAVA_OUT)/apps/Smallest/HelloWorld.jopsim.log

jopsim-hello: all
	@java -cp $(JOPSIM_JAR) com.jopdesign.tools.JopSim \
		$(JAVA_OUT)/apps/Smallest/HelloWorld.jop $(JOPSIM_BUDGET) \
		> $(JOPSIM_LOG) 2>&1; \
	rc=$$?; n=$$(grep -cx 'Hello World!' $(JOPSIM_LOG)); \
	if [ $$rc -eq 0 ] && [ $$n -gt 0 ]; then \
		sed -n '1,/^Hello World!$$/p' $(JOPSIM_LOG); \
		echo "PASS: JopSim ran Smallest/HelloWorld to its first line"; \
	else \
		head -n 30 $(JOPSIM_LOG); \
		echo "FAIL: JopSim exited $$rc, and Smallest/HelloWorld printed $$n lines in $(JOPSIM_BUDGET) bytecodes; see $(JOPSIM_LOG)"; \
		exit 1; \
	fi

clean:
	$(MAKE) -C tools clean
	$(MAKE) -C runtime clean
	$(MAKE) -C apps/Smallest clean
	$(MAKE) -C apps/Small clean
	$(MAKE) -C apps/InterruptTest clean
