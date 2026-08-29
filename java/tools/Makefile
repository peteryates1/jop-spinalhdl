JAVAC ?= javac
JAR ?= jar

SRC_DIR := src
DIST_DIR := dist
CLASSES_DIR := $(DIST_DIR)/classes
LIB_DIR := ../lib
TARGET_SRC := ../runtime/src/jop

# Const.java is GENERATED, and BUILDTREE=1 puts it in the config's own gen tree
# (build/<config>/java/gen) instead of ../runtime/src/jop. These tools compile
# against it, so that directory has to be on the sourcepath as well -- otherwise
# `make -C java all BUILDTREE=1` fails from cold with
#
#   ReplaceAtomicAnnotation.java:24: cannot find symbol ... variable Const
#
# and works only in a tree where some earlier NON-buildtree build happened to
# leave a Const.java behind in the runtime source. java/Makefile exports
# GEN_SRC; it is empty for a legacy build, so that path is unchanged.
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

.PHONY: all clean

all: $(DIST_DIR)/jopizer.jar $(DIST_DIR)/jopsim.jar $(DIST_DIR)/jopa.jar

$(DIST_DIR)/jopizer.jar: $(shell find $(SRC_DIR) -name '*.java')
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -encoding Latin1 -d $(CLASSES_DIR) \
		-classpath $(COMPILE_CP) \
		-sourcepath $(SRC_DIR):$(TARGET_SRC_PATH) \
		$(SRC_DIR)/com/jopdesign/build/JOPizer.java \
		$(SRC_DIR)/com/jopdesign/build/PreLinker.java
	$(JAR) cfe $(DIST_DIR)/jopizer.jar com.jopdesign.build.JOPizer -C $(CLASSES_DIR) .

# JopSim needs only JopInstr and Const (no BCEL)
SIM_CP := $(TARGET_SRC_PATH)

$(DIST_DIR)/jopsim.jar: $(shell find $(SRC_DIR)/com/jopdesign/tools -name '*.java')
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -encoding Latin1 -d $(CLASSES_DIR) \
		-classpath $(SIM_CP) \
		-sourcepath $(SRC_DIR):$(TARGET_SRC_PATH) \
		$(SRC_DIR)/com/jopdesign/tools/JopSim.java
	$(JAR) cfe $(DIST_DIR)/jopsim.jar com.jopdesign.tools.JopSim -C $(CLASSES_DIR) .

# Jopa needs no external libs — only its own package
$(DIST_DIR)/jopa.jar: $(shell find $(SRC_DIR)/com/jopdesign/tools -name '*.java')
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -encoding Latin1 -d $(CLASSES_DIR) \
		-sourcepath $(SRC_DIR) \
		$(SRC_DIR)/com/jopdesign/tools/Jopa.java
	$(JAR) cfe $(DIST_DIR)/jopa.jar com.jopdesign.tools.Jopa -C $(CLASSES_DIR) .

clean:
	rm -rf $(DIST_DIR)
