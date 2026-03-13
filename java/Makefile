# JopConfig preset for Const.java generation (default: ep4cgx150Serial)
JOP_PRESET ?= ep4cgx150Serial

# Const.java location (generated, not tracked in git)
CONST_JAVA := runtime/src/jop/com/jopdesign/sys/Const.java

# SpinalHDL project root (relative to java/)
PROJECT_ROOT := ..

.PHONY: all tools runtime apps clean sim-smallest sim-small generate-const

all: tools runtime apps

# Generate Const.java from JopConfig if missing or stale
generate-const: $(CONST_JAVA)

$(CONST_JAVA): $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/generate/ConstGenerator.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/config/JopConfig.scala \
               $(PROJECT_ROOT)/spinalhdl/src/main/scala/jop/config/JopCoreConfig.scala
	cd $(PROJECT_ROOT) && sbt -error "runMain jop.generate.ConstGeneratorMain $(JOP_PRESET) --write"

tools: $(CONST_JAVA)
	$(MAKE) -C tools

runtime: $(CONST_JAVA)
	$(MAKE) -C runtime

apps: tools runtime
	$(MAKE) -C apps/Smallest
	$(MAKE) -C apps/Small
	$(MAKE) -C apps/InterruptTest

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
