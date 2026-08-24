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
BUILDTREE  ?= 0

# APP_NAME is the main class and is NOT unique -- apps/Smallest and apps/Small
# are both "HelloWorld". The directory is, so it keys the output location.
APP_DIR_NAME := $(notdir $(CURDIR))

RUNTIME_SRCS := $(TARGET_DIR)/src/jop $(TARGET_DIR)/src/jvm $(TARGET_DIR)/src/jdk

ifeq ($(BUILDTREE),1)

  # Ask BuildLayout for the directory rather than reimplementing configName
  # here: a second copy of the sanitisation rules would go stale the first time
  # an override spelling changed, and the symptom would be a silently split
  # build directory rather than an error.
  #
  # Exported by java/Makefile so sub-makes reuse it and pay the sbt start once.
  # sbt logs a forked process's stdout at INFO, so the `[info] ` prefix is
  # stripped here; `sbt -error` would suppress the answer along with the noise.
  ifeq ($(origin JOP_CFG_DIR), undefined)
    JOP_CFG_DIR := $(shell cd $(PROJECT_ROOT) && sbt "runMain jop.generate.BuildLayoutMain $(JOP_PRESET)" 2>/dev/null \
                     | sed -n 's/^\[info\] \(build\/.*\)$$/\1/p' | tail -1)
    ifeq ($(JOP_CFG_DIR),)
      $(error BUILDTREE=1 but jop.generate.BuildLayoutMain produced no directory for preset "$(JOP_PRESET)")
    endif
  endif

  JAVA_OUT   := $(PROJECT_ROOT)/$(JOP_CFG_DIR)/java
  GEN_SRC    := $(JAVA_OUT)/gen
  CONST_JAVA := $(GEN_SRC)/com/jopdesign/sys/Const.java
  CONST_ARGS := $(JOP_PRESET) --write buildtree

  # A legacy Const.java left in runtime/src from an earlier in-tree build would
  # be a DUPLICATE definition of the same class, not a harmless leftover. Drop
  # it from the source list so the generated one is unambiguously the only copy.
  #
  # The pattern must name the SOURCE tree specifically: '*/com/jopdesign/sys/
  # Const.java' also matches the generated copy under gen/, which excluded both
  # and left every class referencing Const uncompilable.
  CONST_EXCLUDE := ! -path '*/src/jop/com/jopdesign/sys/Const.java'

else

  JAVA_OUT   :=
  GEN_SRC    :=
  CONST_JAVA := $(TARGET_DIR)/src/jop/com/jopdesign/sys/Const.java
  CONST_ARGS := $(JOP_PRESET) --write
  CONST_EXCLUDE :=

endif

# Every source directory javac must see, generated code included.
TARGET_SRCS := $(RUNTIME_SRCS) $(GEN_SRC)
