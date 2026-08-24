TARGET_JDK_HOME ?= /opt/jdk8u492-b09
TARGET_JAVAC := $(TARGET_JDK_HOME)/bin/javac

PROJECT_ROOT := ../..
TARGET_DIR   := .
include ../config.mk

SRC_JOP := src/jop
SRC_JVM := src/jvm
SRC_JDK := src/jdk

# Compiled classes are a per-configuration artefact once Const.java is: they
# embed its constants. Under BUILDTREE=1 they go with it.
CLASSES_DIR := $(if $(JAVA_OUT),$(JAVA_OUT)/runtime/classes,classes)

# GEN_SRC is empty unless BUILDTREE=1, in which case Const.java lives there
# rather than under src/. It goes FIRST: javac takes the first match on the
# sourcepath, and a legacy Const.java left in src/jop from an earlier in-tree
# build would otherwise win silently -- the generated file would be written,
# ignored, and the wrong constants compiled in with nothing to show for it.
SRCPATH := $(subst $() $(),:,$(strip $(GEN_SRC) $(SRC_JOP) $(SRC_JVM) $(SRC_JDK)))

.PHONY: all clean

all: $(CLASSES_DIR)/.built

$(CLASSES_DIR)/.built: $(shell find src $(GEN_SRC) -name '*.java' 2>/dev/null)
	mkdir -p $(CLASSES_DIR)
	$(TARGET_JAVAC) -source 1.6 -target 1.6 -d $(CLASSES_DIR) \
		-sourcepath $(SRCPATH) \
		$(SRC_JOP)/com/jopdesign/sys/Startup.java
	touch $@

clean:
	rm -rf $(CLASSES_DIR)
