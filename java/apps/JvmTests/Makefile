# TWO JVMs decide what the .jop image looks like, and both must match CI to
# reproduce a CI result locally:
#
#   TARGET_JDK_HOME  javac for the JOP target code. Sets the image SIZE.
#                    Must still emit -source/-target 1.6: JOPizer's BCEL 5.2
#                    cannot read class files newer than Java 6, and JDK 9
#                    dropped target 6 — so JDK 8 is the newest usable, not a
#                    preference. Named for its role, not its version: the old
#                    JDK6_HOME ended up pointing at a JDK 8 in CI, which is
#                    exactly the sort of lie that costs an afternoon.
#   JAVA             runs JOPizer/PreLinker. Sets the image LAYOUT — same size,
#                    different bytes. CI uses 17.0.19; a stock system java is
#                    usually newer and produces a different (valid) image.
#
# Verified 2026-08-09: javac 8u492 + tools JDK 17.0.19 reproduces CI's
# DoAll.jop byte for byte. For a CI-identical build:
#   JAVA_HOME=/opt/jdk-17.0.19+10 PATH=/opt/jdk-17.0.19+10/bin:$PATH \
#     TARGET_JDK_HOME=/opt/jdk8u492-b09 make
TARGET_JDK_HOME ?= /opt/jdk8u492-b09
TARGET_JAVAC := $(TARGET_JDK_HOME)/bin/javac
JAVA ?= java

ROOT := ../..
TOOLS_DIR := $(ROOT)/tools
TARGET_DIR := $(ROOT)/runtime
LIB_DIR := $(ROOT)/lib

APP_PKG := jvm
APP_NAME := DoAll
APP_SRC := src

BUILD_DIR := build
CLASSES_DIR := $(BUILD_DIR)/classes
PP_DIR := $(BUILD_DIR)/pp
PP_CLASSES_DIR := $(PP_DIR)/classes
JOP_OUT := $(APP_NAME).jop

# Classpath for tools
LIB_CP := $(LIB_DIR)/bcel-5.2.jar:$(LIB_DIR)/jakarta-regexp-1.3.jar:$(LIB_DIR)/log4j-1.2.15.jar:$(LIB_DIR)/jgrapht-jdk1.5.jar
TOOLS_CP := $(TOOLS_DIR)/dist/jopizer.jar:$(LIB_CP)

.PHONY: all classes preprocess jop clean

all: jop

classes:
	mkdir -p $(CLASSES_DIR) $(BUILD_DIR)
	find $(APP_SRC) $(TARGET_DIR)/src/jop $(TARGET_DIR)/src/jvm $(TARGET_DIR)/src/jdk -name '*.java' > $(BUILD_DIR)/sources.txt
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
