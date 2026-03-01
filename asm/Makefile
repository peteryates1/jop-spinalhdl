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

# Build flash-boot microcode for Artix-7 (4 MB flash, offset 0x240000)
flash-au: ../java/tools/dist/jopa.jar
	mkdir -p generated/flash
	gcc -x c -E -C -P $(GCC_PARAMS) -DFLASH -DFLASH_ADDR_B2=36 -DFLASH_CLK_DIV=15 -DSKIP_FLASH_RESET src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/flash/mem_rom.dat
	cp generated/mem_ram.dat generated/flash/mem_ram.dat
	cp generated/rom.mif generated/flash/rom.mif
	sed 's/object JumpTableData/object FlashJumpTableData/' generated/JumpTableData.scala \
		> generated/flash/FlashJumpTableData.scala

# Build FPU microcode variants (simulation + serial, with FPU_ATTACHED)
# The FPU ROM is a superset: contains all base microcode + FPU handlers at end.
# Non-FPU cores use JumpTableData (fadd→sys_noim); FPU cores use FpuJumpTableData.
# Both variants share the same JVM_TYPE so non-FPU addresses are identical.
fpu: ../java/tools/dist/jopa.jar
	mkdir -p generated/fpu
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) -DFPU_ATTACHED src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/fpu/mem_rom.dat
	cp generated/mem_ram.dat generated/fpu/mem_ram.dat
	cp generated/rom.mif generated/fpu/rom.mif
	sed 's/object JumpTableData/object FpuJumpTableData/' generated/JumpTableData.scala \
		> generated/fpu/FpuJumpTableData.scala
	$(MAKE) JVM_TYPE=$(JVM_TYPE)

# Build serial+FPU microcode and save to generated/serial-fpu/
serial-fpu: ../java/tools/dist/jopa.jar
	mkdir -p generated/serial-fpu
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL -DFPU_ATTACHED src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial-fpu/mem_rom.dat
	cp generated/mem_ram.dat generated/serial-fpu/mem_ram.dat
	cp generated/rom.mif generated/serial-fpu/rom.mif
	sed 's/object JumpTableData/object SerialFpuJumpTableData/' generated/JumpTableData.scala \
		> generated/serial-fpu/SerialFpuJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

../java/tools/dist/jopa.jar:
	cd ../java/tools && $(MAKE) dist/jopa.jar

clean:
	rm -rf generated
