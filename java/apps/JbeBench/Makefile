# JavaBenchEmbedded (JBE) — the classic JOP application benchmark suite.
#
# Ported from /srv/git/jop.original/java/target/src/bench/jbe. Only LowLevel.java
# was changed (System.currentTimeMillis -> IO_US_CNT); the workloads are
# upstream so results stay comparable with the published JOP numbers.
#
# Entry point is jbe.DoApp — Kfl, UdpIp and Lift, the three APPLICATION
# benchmarks. Upstream's DoAll additionally pulls in a `Jitter` class that lives
# outside this package, so it is not carried over.
#
# JDK 6 for target compilation, system Java for running tools
TARGET_JDK_HOME ?= /opt/jdk8u492-b09
TARGET_JAVAC := $(TARGET_JDK_HOME)/bin/javac
JAVA ?= java

ROOT := ../..
TOOLS_DIR := $(ROOT)/tools
TARGET_DIR := $(ROOT)/runtime
LIB_DIR := $(ROOT)/lib

APP_PKG := jbe
APP_NAME ?= DoApp
APP_SRC := src

BUILD_DIR := build
CLASSES_DIR := $(BUILD_DIR)/classes
PP_DIR := $(BUILD_DIR)/pp
PP_CLASSES_DIR := $(PP_DIR)/classes
JOP_OUT ?= JbeBench.jop

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
