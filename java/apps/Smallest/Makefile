# JDK 6 for target compilation, system Java for running tools
TARGET_JDK_HOME ?= /opt/jdk8u492-b09
TARGET_JAVAC := $(TARGET_JDK_HOME)/bin/javac
JAVA ?= java

ROOT := ../..
TOOLS_DIR := $(ROOT)/tools
TARGET_DIR := $(ROOT)/runtime
LIB_DIR := $(ROOT)/lib

# Per-configuration build tree (Const.java, classes, .jop) under
# build/<config>/java/. Included AFTER TARGET_DIR is set -- config.mk builds
# paths from it.
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
TOOLS_CP := $(JOPIZER_JAR):$(LIB_CP)

.PHONY: all classes preprocess jop clean

all: jop

classes:
	mkdir -p $(CLASSES_DIR) $(BUILD_DIR)
	find $(APP_SRC) $(TARGET_SRCS) -name '*.java' $(CONST_EXCLUDE) > $(BUILD_DIR)/sources.txt
	$(TARGET_JAVAC) -source 1.6 -target 1.6 -d $(CLASSES_DIR) @$(BUILD_DIR)/sources.txt

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
