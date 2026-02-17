
all: dist/lib/jopa.jar

dist/lib/jopa.jar: src/com/jopdesign/tools/*.java
	mkdir -p dist/lib
	mkdir -p dist/classes
	javac -g -d dist/classes -encoding Latin1 src/com/jopdesign/tools/*.java
	jar cfe dist/lib/jopa.jar com.jopdesign.tools.Jopa -C dist/classes .

clean:
	rm -rf dist
