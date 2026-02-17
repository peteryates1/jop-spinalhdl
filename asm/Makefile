GCC_PARAMS=
ASM_SRC=jvm
JVM_TYPE=SIMULATION

all: ../java/tools/jopa/dist/lib/jopa.jar
	mkdir -p generated
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
# remove block comments introduced by gcc preprocessor to fix alignment in rom.mif
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/jopa/dist/lib/jopa.jar -s generated -d generated $(ASM_SRC).asm

# Build serial microcode and save to generated/serial/ (including SerialJumpTableData.scala)
serial: ../java/tools/jopa/dist/lib/jopa.jar
	mkdir -p generated/serial
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/jopa/dist/lib/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial/mem_rom.dat
	cp generated/mem_ram.dat generated/serial/mem_ram.dat
	cp generated/rom.mif generated/serial/rom.mif
	sed 's/object JumpTableData/object SerialJumpTableData/' generated/JumpTableData.scala \
		> generated/serial/SerialJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

dist/lib/jopa.jar: src/com/jopdesign/tools/*.java
	cd ../java/tools/jopa && make

clean:
	rm -rf generated
