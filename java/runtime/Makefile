TARGET_JDK_HOME ?= /opt/jdk8u492-b09
TARGET_JAVAC := $(TARGET_JDK_HOME)/bin/javac

SRC_JOP := src/jop
SRC_JVM := src/jvm
SRC_JDK := src/jdk
CLASSES_DIR := classes

.PHONY: all clean

all: $(CLASSES_DIR)/.built

$(CLASSES_DIR)/.built: $(shell find src -name '*.java')
	mkdir -p $(CLASSES_DIR)
	$(TARGET_JAVAC) -source 1.6 -target 1.6 -d $(CLASSES_DIR) \
		-sourcepath $(SRC_JOP):$(SRC_JVM):$(SRC_JDK) \
		$(SRC_JOP)/com/jopdesign/sys/Startup.java
	touch $@

clean:
	rm -rf $(CLASSES_DIR)
