JAVAC ?= javac
JAR ?= jar

SRC_DIR := src
LIB_DIR := ../lib

# WHERE THE JARS GO. Set by java/config.mk and exported by java/Makefile, so
# the config-dependence rule lives in one place -- see the long note there.
#   TOOLS_OUT         build/<config>/java/tools   jopizer, jopsim
#   COMMON_TOOLS_OUT  build/java/tools            jopa
#
# COMMON_TOOLS_OUT has a default because jopa is config-independent, so
# `make -C java/tools jopa` is meaningful with no preset selected -- CI builds
# it that way, before any configuration exists. The per-config jars have NO
# default on purpose: a default would silently produce a jar for the wrong
# configuration at a path something later reads, which is the whole defect
# this split exists to remove.
COMMON_TOOLS_OUT ?= $(abspath ../../build/java/tools)

# ONE CLASSES DIRECTORY PER JAR. All three used to share $(DIST_DIR)/classes
# and pack it whole with `-C $(CLASSES_DIR) .`, so every jar shipped whatever
# the previous one had compiled: jopa.jar carried Const.class despite Jopa
# never referencing Const, which made a config-independent tool look
# config-dependent. Separate directories mean a jar contains what it needs.
# THE JAR PATHS NEED DEFAULTS HERE TOO. They are normally set by
# java/config.mk and exported, but `make -C java/tools jopa` includes nothing,
# so JOPA_JAR was EMPTY and `jopa: $(JOPA_JAR)` became a target with no
# prerequisite: it built nothing and exited 0. A silent no-op that reports
# success is worse than the stale jar this split exists to fix.
JOPIZER_JAR ?= $(TOOLS_OUT)/jopizer.jar
JOPSIM_JAR  ?= $(TOOLS_OUT)/jopsim.jar
JOPA_JAR    ?= $(COMMON_TOOLS_OUT)/jopa.jar

JOPIZER_CLASSES := $(TOOLS_OUT)/classes-jopizer
JOPSIM_CLASSES  := $(TOOLS_OUT)/classes-jopsim
JOPA_CLASSES    := $(COMMON_TOOLS_OUT)/classes-jopa
TARGET_SRC := ../runtime/src/jop

# Const.java is GENERATED into the config's own gen tree
# (build/<config>/java/gen). These tools compile against it, so that directory
# has to be on the sourcepath -- otherwise `make -C java all` fails from cold
# with
#
#   ReplaceAtomicAnnotation.java:24: cannot find symbol ... variable Const
#
# and works only in a tree where some earlier build happened to leave a
# Const.java behind in the runtime source. java/Makefile exports GEN_SRC.
#
# GEN_SRC is written relative to java/, but this Makefile runs in java/tools/,
# so using it verbatim resolves one level short (java/build/... instead of
# build/...). javac does not complain about a sourcepath entry that does not
# exist -- it just fails later with "cannot find symbol", pointing at the
# symbol rather than at the path. Hence the extra `../`, and abspath so this
# still holds if GEN_SRC is ever made absolute.
GEN_SRC_HERE := $(if $(GEN_SRC),$(abspath $(if $(filter /%,$(GEN_SRC)),$(GEN_SRC),../$(GEN_SRC))))
TARGET_SRC_PATH := $(TARGET_SRC)$(if $(GEN_SRC_HERE),:$(GEN_SRC_HERE))

# JARs needed for compilation
LIB_CP := $(LIB_DIR)/bcel-5.2.jar:$(LIB_DIR)/jakarta-regexp-1.3.jar:$(LIB_DIR)/log4j-1.2.15.jar:$(LIB_DIR)/jgrapht-jdk1.5.jar

# Include target/src/jop for Const
COMPILE_CP := $(LIB_CP):$(TARGET_SRC_PATH)

.PHONY: all clean jopa require-tools-out

all: require-tools-out $(JOPIZER_JAR) $(JOPSIM_JAR) $(JOPA_JAR)

# Convenience for callers that want only the config-independent assembler --
# asm/ and the CI job that builds microcode before any preset is chosen.
jopa: $(JOPA_JAR)

require-tools-out:
	@if [ -z "$(TOOLS_OUT)" ]; then \
	  echo "java/tools: TOOLS_OUT is not set." >&2; \
	  echo "  jopizer.jar and jopsim.jar are PER CONFIGURATION -- they compile" >&2; \
	  echo "  against the generated Const.java -- so they cannot be built without" >&2; \
	  echo "  a preset. Build via 'make -C java tools JOP_PRESET=<preset>', which" >&2; \
	  echo "  sets it. 'make -C java/tools jopa' needs no preset." >&2; \
	  exit 1; fi

# Const.java IS A PREREQUISITE, not merely on the sourcepath — status item 137.
#
# JopMethodInfo reads Const.METHOD_MAX_SIZE, which ConstGenerator derives from
# the preset's method cache. Those are `static final int`, so javac INLINES them
# into JopMethodInfo.class: the limit is baked into the jar at build time and no
# runtime lookup can save a stale one.
#
# Without this dependency, switching preset (or passing methodmax=) regenerates
# Const.java, the jar does NOT rebuild, and the linker silently keeps enforcing
# the PREVIOUS preset's limit. Demonstrated: with Const at 2048 the jar still
# compared against 1023 words, i.e. the 4092-byte limit.
#
# Const.java is a PREREQUISITE of the jar, not merely something on the
# sourcepath — same defect as item 140 and as Const.java's own missing
# prerequisites: a generated artefact whose prerequisites omit an input that
# decides its content. The linker bakes METHOD_MAX_SIZE in, so a jar built
# against a stale Const.java enforces the wrong limit and says nothing.
#
# The second arm of this used to be $(TARGET_SRC)/com/jopdesign/sys/Const.java,
# the source-tree copy. Nothing writes there any more, so it could never match;
# dropping it is a no-op today and stops it becoming a live fallback to the old
# layout tomorrow.
CONST_JAVA_DEP := $(wildcard $(GEN_SRC_HERE)/jop/com/jopdesign/sys/Const.java)

$(JOPIZER_JAR): $(shell find $(SRC_DIR) -name '*.java') $(CONST_JAVA_DEP)
	mkdir -p $(JOPIZER_CLASSES) $(dir $@)
	$(JAVAC) -encoding Latin1 -d $(JOPIZER_CLASSES) \
		-classpath $(COMPILE_CP) \
		-sourcepath $(SRC_DIR):$(TARGET_SRC_PATH) \
		$(SRC_DIR)/com/jopdesign/build/JOPizer.java \
		$(SRC_DIR)/com/jopdesign/build/PreLinker.java
	$(JAR) cfe $@ com.jopdesign.build.JOPizer -C $(JOPIZER_CLASSES) .

# JopSim needs only JopInstr and Const (no BCEL)
SIM_CP := $(TARGET_SRC_PATH)

# CONST_JAVA_DEP HERE TOO. JopSim references Const -- compiling it alone puts
# Const.class in its output -- so it is per-configuration for the same reason
# jopizer is, and it was missing the prerequisite entirely: a preset switch
# regenerated Const.java and this jar did not rebuild.
$(JOPSIM_JAR): $(shell find $(SRC_DIR)/com/jopdesign/tools -name '*.java') $(CONST_JAVA_DEP)
	mkdir -p $(JOPSIM_CLASSES) $(dir $@)
	$(JAVAC) -encoding Latin1 -d $(JOPSIM_CLASSES) \
		-classpath $(SIM_CP) \
		-sourcepath $(SRC_DIR):$(TARGET_SRC_PATH) \
		$(SRC_DIR)/com/jopdesign/tools/JopSim.java
	$(JAR) cfe $@ com.jopdesign.tools.JopSim -C $(JOPSIM_CLASSES) .

# Jopa needs no external libs — only its own package, and no Const, which is
# why this one is COMMON rather than per-config.
$(JOPA_JAR): $(shell find $(SRC_DIR)/com/jopdesign/tools -name '*.java')
	mkdir -p $(JOPA_CLASSES) $(dir $@)
	$(JAVAC) -encoding Latin1 -d $(JOPA_CLASSES) \
		-sourcepath $(SRC_DIR) \
		$(SRC_DIR)/com/jopdesign/tools/Jopa.java
	$(JAR) cfe $@ com.jopdesign.tools.Jopa -C $(JOPA_CLASSES) .

clean:
	rm -rf $(TOOLS_OUT) $(COMMON_TOOLS_OUT)
