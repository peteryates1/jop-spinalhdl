# JDK 6 for target compilation, system Java for running tools
TARGET_JDK_HOME ?= /opt/jdk8u492-b09
TARGET_JAVAC := $(TARGET_JDK_HOME)/bin/javac
JAVA ?= java

ROOT := ../..
TOOLS_DIR := $(ROOT)/tools
TARGET_DIR := $(ROOT)/runtime
LIB_DIR := $(ROOT)/lib

# Per-configuration build tree (Const.java, classes, .jop). Included AFTER
# TARGET_DIR is set -- config.mk builds paths from it. Default is the old
# in-tree layout; BUILDTREE=1 moves the outputs under build/<config>/java/.
PROJECT_ROOT := $(ROOT)/..
include $(ROOT)/config.mk

APP_PKG := test
APP_NAME := HelloWorld
APP_SRC := src

BUILD_DIR := $(if $(JAVA_OUT),$(JAVA_OUT)/apps/$(APP_DIR_NAME),build)
CLASSES_DIR := $(BUILD_DIR)/classes
PP_DIR := $(BUILD_DIR)/pp
PP_CLASSES_DIR := $(PP_DIR)/classes
JOP_OUT := $(if $(JAVA_OUT),$(JAVA_OUT)/apps/$(APP_DIR_NAME)/$(APP_NAME).jop,$(APP_NAME).jop)

# Classpath for tools
LIB_CP := $(LIB_DIR)/bcel-5.2.jar:$(LIB_DIR)/jakarta-regexp-1.3.jar:$(LIB_DIR)/log4j-1.2.15.jar:$(LIB_DIR)/jgrapht-jdk1.5.jar
TOOLS_CP := $(TOOLS_DIR)/dist/jopizer.jar:$(LIB_CP)

# EXTRA_SRC can contain space-separated paths (e.g., EXTRA_SRC="../../net/src ../../fat32/src")
SRCPATH := $(subst $() $(),:,$(strip $(APP_SRC) $(GEN_SRC) $(RUNTIME_SRCS) $(EXTRA_SRC)))

# Runtime and generated sources are compiled WHOLESALE; the app is named. See
# the comment on `classes` for why it is this way round.
BULK_SRCS := $(TARGET_SRCS) $(EXTRA_SRC)

.PHONY: all classes preprocess jop clean

all: jop

# THE RUNTIME IS COMPILED WHOLESALE; THE APP IS REACHED FROM ITS ENTRY POINT.
#
# This used to pass -sourcepath and name only $(APP_NAME).java. That makes javac
# emit just the classes reachable from that entry point, so classes the JVM
# needs but the application never mentions -- java.lang.Throwable among them --
# were never written, and PreLinker failed:
#
#   ClassInfoNotFoundException: Class 'java.lang.Throwable' could not be loaded
#
# It survived because a populated build/classes from an earlier, wider compile
# satisfies PreLinker. The failure only appears from clean -- and by then NONE of
# the .jop files in this directory could be regenerated. See item 61.
#
# The other seven apps just `find` everything, but that is wrong HERE: this
# directory holds apps with different dependencies (NetTest and HttpServer need
# EXTRA_SRC=../../net/src), so compiling all of src/test fails unless every
# optional source root happens to be supplied. Hence the split -- bulk-compile
# the runtime, name the app, and let -sourcepath pull in what it uses.
classes:
	mkdir -p $(CLASSES_DIR) $(BUILD_DIR)
	find $(BULK_SRCS) -name '*.java' $(CONST_EXCLUDE) > $(BUILD_DIR)/sources.txt
	$(TARGET_JAVAC) -source 1.6 -target 1.6 \
		-sourcepath $(SRCPATH) \
		-d $(CLASSES_DIR) \
		@$(BUILD_DIR)/sources.txt $(APP_SRC)/$(APP_PKG)/$(APP_NAME).java

preprocess: classes
	mkdir -p $(PP_DIR)
	$(JAVA) -classpath $(TOOLS_CP) \
		com.jopdesign.build.PreLinker \
		-c $(CLASSES_DIR) -o $(PP_DIR) $(APP_PKG)/$(APP_NAME)

jop: preprocess
	$(JAVA) -Dmgci=false -classpath $(TOOLS_CP) \
		com.jopdesign.build.JOPizer \
		-cp $(PP_CLASSES_DIR) -o $(JOP_OUT) $(APP_PKG).$(APP_NAME)

clean:
	rm -rf $(BUILD_DIR) $(JOP_OUT)
