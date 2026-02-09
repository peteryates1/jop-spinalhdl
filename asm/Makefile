GCC_PARAMS=
ASM_SRC=jvm
JVM_TYPE=SIMULATION

all: ../java/jopa/dist/lib/jopa.jar
	mkdir -p generated
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
# remove block comments introduced by gcc preprocessor to fix alignment in rom.mif
	sed -i '1,35d' generated/$(ASM_SRC).asm 
	java -jar ../java/jopa/dist/lib/jopa.jar -s generated -d generated $(ASM_SRC).asm

dist/lib/jopa.jar: src/com/jopdesign/tools/*.java
	cd ../java/jopa && make

clean:
	rm -rf generated
