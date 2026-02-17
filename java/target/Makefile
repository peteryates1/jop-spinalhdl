JAVA_HOME ?= /opt/jdk1.6.0_45
JAVAC := $(JAVA_HOME)/bin/javac

SRC_JOP := src/jop
SRC_JVM := src/jvm
CLASSES_DIR := classes

.PHONY: all clean

all: $(CLASSES_DIR)/.built

$(CLASSES_DIR)/.built: $(shell find src -name '*.java')
	mkdir -p $(CLASSES_DIR)
	$(JAVAC) -source 1.6 -target 1.6 -d $(CLASSES_DIR) \
		-sourcepath $(SRC_JOP):$(SRC_JVM) \
		$(SRC_JOP)/com/jopdesign/sys/Startup.java
	touch $@

clean:
	rm -rf $(CLASSES_DIR)
