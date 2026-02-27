GCC_PARAMS=
ASM_SRC=jvm
JVM_TYPE=SIMULATION

all: ../java/tools/dist/jopa.jar
	mkdir -p generated
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
# remove block comments introduced by gcc preprocessor to fix alignment in rom.mif
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm

# Build serial microcode and save to generated/serial/ (including SerialJumpTableData.scala)
serial: ../java/tools/dist/jopa.jar
	mkdir -p generated/serial
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial/mem_rom.dat
	cp generated/mem_ram.dat generated/serial/mem_ram.dat
	cp generated/rom.mif generated/serial/rom.mif
	sed 's/object JumpTableData/object SerialJumpTableData/' generated/JumpTableData.scala \
		> generated/serial/SerialJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

# Build flash-boot microcode and save to generated/flash/ (including FlashJumpTableData.scala)
flash: ../java/tools/dist/jopa.jar
	mkdir -p generated/flash
	gcc -x c -E -C -P $(GCC_PARAMS) -DFLASH src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/flash/mem_rom.dat
	cp generated/mem_ram.dat generated/flash/mem_ram.dat
	cp generated/rom.mif generated/flash/rom.mif
	sed 's/object JumpTableData/object FlashJumpTableData/' generated/JumpTableData.scala \
		> generated/flash/FlashJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

../java/tools/dist/jopa.jar:
	cd ../java/tools && $(MAKE) dist/jopa.jar

clean:
	rm -rf generated
