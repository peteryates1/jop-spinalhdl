JAVAC ?= javac
JAR ?= jar

SRC_DIR := src
JOPA_SRC := jopa/src
DIST_DIR := dist
CLASSES_DIR := $(DIST_DIR)/classes
LIB_DIR := ../lib
TARGET_SRC := ../target/src/jop

# JARs needed for compilation
LIB_CP := $(LIB_DIR)/bcel-5.2.jar:$(LIB_DIR)/jakarta-regexp-1.3.jar:$(LIB_DIR)/log4j-1.2.15.jar:$(LIB_DIR)/jgrapht-jdk1.5.jar

# Include jopa/src for JopInstr and target/src/jop for Const
COMPILE_CP := $(LIB_CP):$(JOPA_SRC):$(TARGET_SRC)

.PHONY: all clean

all: $(DIST_DIR)/jopizer.jar $(DIST_DIR)/jopsim.jar

$(DIST_DIR)/jopizer.jar: $(shell find $(SRC_DIR) -name '*.java')
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -encoding Latin1 -d $(CLASSES_DIR) \
		-classpath $(COMPILE_CP) \
		-sourcepath $(SRC_DIR):$(JOPA_SRC):$(TARGET_SRC) \
		$(SRC_DIR)/com/jopdesign/build/JOPizer.java \
		$(SRC_DIR)/com/jopdesign/build/PreLinker.java
	$(JAR) cfe $(DIST_DIR)/jopizer.jar com.jopdesign.build.JOPizer -C $(CLASSES_DIR) .

# JopSim needs only JopInstr and Const (no BCEL)
SIM_CP := $(JOPA_SRC):$(TARGET_SRC)

$(DIST_DIR)/jopsim.jar: $(shell find $(SRC_DIR)/com/jopdesign/tools -name '*.java')
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -encoding Latin1 -d $(CLASSES_DIR) \
		-classpath $(SIM_CP) \
		-sourcepath $(SRC_DIR):$(JOPA_SRC):$(TARGET_SRC) \
		$(SRC_DIR)/com/jopdesign/tools/JopSim.java
	$(JAR) cfe $(DIST_DIR)/jopsim.jar com.jopdesign.tools.JopSim -C $(CLASSES_DIR) .

clean:
	rm -rf $(DIST_DIR)
