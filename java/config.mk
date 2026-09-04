# ---------------------------------------------------------------------------
# Shared build configuration for the Java/JOP tree.
#
# Included by java/Makefile, java/runtime/Makefile and every java/apps/*/Makefile.
# Each includer sets JAVA_DIR (path to java/) and PROJECT_ROOT (repo root)
# before including.
#
# WHAT THIS EXISTS FOR. Const.java is generated from JopConfig and is PER
# CONFIGURATION -- ep4cgx150Serial and wukongSmp differ in SUPPORT_FLOAT today
# and will differ in more. It was generated into java/runtime/src, a source tree
# shared by every configuration, so whichever build ran last decided what every
# subsequent .jop contained. See docs/current-status.md item 60.
#
# BUILDTREE=1 puts Const.java, the compiled classes and the .jop images under
# build/<config>/java/ instead. Opt-in, and the default is byte-for-byte the old
# behaviour, so nothing that is not converted changes.
# ---------------------------------------------------------------------------

JOP_PRESET ?= ep4cgx150Serial

# BUILDTREE IS GONE, AND ITS ABSENCE IS THE POINT. It selected between this
# layout and writing build products into the source tree, and it defaulted to
# the SOURCE TREE -- so the correct layout needed a flag and everything that
# had not been explicitly converted silently kept the old behaviour. Board
# flows passed BUILDTREE=1; a bare `make -C java` did not, which is how the
# shared-source-tree Const.java survived long after the boards had moved.
#
# The variable is still ACCEPTED on the command line so the four board
# Makefiles that pass BUILDTREE=1 keep working, and it is ignored: there is one
# layout now, so there is nothing left to select.

# APP_NAME is the main class and is NOT unique -- apps/Smallest and apps/Small
# are both "HelloWorld". The directory is, so it keys the output location.
APP_DIR_NAME := $(notdir $(CURDIR))

RUNTIME_SRCS := $(TARGET_DIR)/src/jop $(TARGET_DIR)/src/jvm $(TARGET_DIR)/src/jdk

# Ask BuildLayout for the directory rather than reimplementing configName here:
# a second copy of the sanitisation rules would go stale the first time an
# override spelling changed, and the symptom would be a silently split build
# directory rather than an error.
#
# Exported by java/Makefile so sub-makes reuse it and pay the sbt start once.
# sbt logs a forked process's stdout at INFO, so the `[info] ` prefix is
# stripped here; `sbt -error` would suppress the answer along with the noise.
ifeq ($(origin JOP_CFG_DIR), undefined)
  JOP_CFG_DIR := $(shell cd $(PROJECT_ROOT) && sbt "runMain jop.generate.BuildLayoutMain $(JOP_PRESET)" 2>/dev/null \
                   | sed -n 's/^\[info\] \(build\/.*\)$$/\1/p' | tail -1)
  ifeq ($(JOP_CFG_DIR),)
    $(error jop.generate.BuildLayoutMain produced no directory for preset "$(JOP_PRESET)")
  endif
endif

JAVA_OUT   := $(PROJECT_ROOT)/$(JOP_CFG_DIR)/java
GEN_SRC    := $(JAVA_OUT)/gen
CONST_JAVA := $(GEN_SRC)/com/jopdesign/sys/Const.java
CONST_ARGS := $(JOP_PRESET) --write buildtree

# A legacy Const.java left in runtime/src from an earlier in-tree build would be
# a DUPLICATE definition of the same class, not a harmless leftover. Drop it
# from the source list so the generated one is unambiguously the only copy.
#
# The pattern must name the SOURCE tree specifically: '*/com/jopdesign/sys/
# Const.java' also matches the generated copy under gen/, which excluded both
# and left every class referencing Const uncompilable.
CONST_EXCLUDE := ! -path '*/src/jop/com/jopdesign/sys/Const.java'

# Every source directory javac must see, generated code included.
TARGET_SRCS := $(RUNTIME_SRCS) $(GEN_SRC)

# ---------------------------------------------------------------------------
# THE APP BUILD RUNS JOPizer FROM A JAR IT NEVER BUILDS — status item 140.
#
# `make -C java/apps/<X>` links with whatever `tools/dist/jopizer.jar` happens
# to be on disk. A change under `java/tools/src` therefore compiles into
# nothing: every timestamp looks fresh, the build exits 0, and the emitted .jop
# is produced by the OLD linker. It cost two wrong conclusions during item 136,
# and was noticed only because a result was impossible — a test passing with the
# defect still present. The image disagreed with its own source:
#
#   command grep -c 'OFF_TYPE: IS_OBJ' DoAll.jop     -> 374   (stale jar)
#   command grep -n 'STR_OBJ_LEN ='   StringInfo.java -> 2+2+1 (the source)
#
# The known GOTCHA — `make -C java runtime && make -C java/apps/<X> clean &&
# make ...` — does NOT help: the staleness is one level up, in the tool that
# PRODUCES the .jop. `make -C java apps` was fine (it has `apps: tools runtime`);
# building an app directly, which is what the GOTCHA tells you to do, was not.
#
# ASK EVERY TIME. The sub-make is incremental — jopizer.jar is a real file
# target over `find $(SRC_DIR) -name '*.java'` — so this is a stat and "nothing
# to be done" when nothing changed, and JOPizer builds in seconds when it has.
# Cheap enough that correctness wins over cleverness.
#
# Declared here rather than in eight app Makefiles: all of them include this
# file, and a rule with no recipe only ADDS a prerequisite to the `jop` target
# each defines later.
.PHONY: tools-fresh
tools-fresh:
	@$(MAKE) -s -C $(TOOLS_DIR)

jop: tools-fresh

# AND DO NOT BECOME THE DEFAULT GOAL. A rule defined inside an include becomes
# the default if it is the first target make sees, and config.mk is included by
# java/runtime/ as well as by the eight apps -- so the first version of this
# made `make -C java runtime` try to build the tools, with TOOLS_DIR unset
# there. Clearing .DEFAULT_GOAL hands the choice back to the including file's
# own first target, which is what it was before this block existed.
.DEFAULT_GOAL :=
