TARGET_JDK_HOME ?= /opt/jdk8u492-b09
TARGET_JAVAC := $(TARGET_JDK_HOME)/bin/javac

PROJECT_ROOT := ../..
TARGET_DIR   := .
include ../config.mk

SRC_JOP := src/jop
SRC_JVM := src/jvm
SRC_JDK := src/jdk

# Compiled classes are a per-configuration artefact because Const.java is: they
# embed its constants, so they go with it under build/<config>/java/. The
# `$(if $(JAVA_OUT),...,classes)` fallback to an in-tree `classes/` is gone --
# JAVA_OUT is always set now, so the fallback was unreachable, and an
# unreachable fallback to the old layout is exactly what let the old layout
# survive everywhere else.
CLASSES_DIR := $(JAVA_OUT)/runtime/classes

# GEN_SRC is where the generated Const.java lives, rather than under src/.
# It goes FIRST: javac takes the first match on the
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
