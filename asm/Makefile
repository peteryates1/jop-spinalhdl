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

# Build DSP multiply microcode (simulation variant)
dsp: ../java/tools/dist/jopa.jar
	mkdir -p generated/dsp
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) -DDSP_MUL src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/dsp/mem_rom.dat
	cp generated/mem_ram.dat generated/dsp/mem_ram.dat
	cp generated/rom.mif generated/dsp/rom.mif
	sed 's/object JumpTableData/object DspJumpTableData/' generated/JumpTableData.scala \
		> generated/dsp/DspJumpTableData.scala
	$(MAKE) JVM_TYPE=$(JVM_TYPE)

# Build serial+DSP microcode
serial-dsp: ../java/tools/dist/jopa.jar
	mkdir -p generated/serial-dsp
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL -DDSP_MUL src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial-dsp/mem_rom.dat
	cp generated/mem_ram.dat generated/serial-dsp/mem_ram.dat
	cp generated/rom.mif generated/serial-dsp/rom.mif
	sed 's/object JumpTableData/object SerialDspJumpTableData/' generated/JumpTableData.scala \
		> generated/serial-dsp/SerialDspJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

# Build HW divide microcode (simulation variant)
div: ../java/tools/dist/jopa.jar
	mkdir -p generated/div
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) -DHW_DIV src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/div/mem_rom.dat
	cp generated/mem_ram.dat generated/div/mem_ram.dat
	cp generated/rom.mif generated/div/rom.mif
	sed 's/object JumpTableData/object DivJumpTableData/' generated/JumpTableData.scala \
		> generated/div/DivJumpTableData.scala
	$(MAKE) JVM_TYPE=$(JVM_TYPE)

# Build serial+DIV microcode
serial-div: ../java/tools/dist/jopa.jar
	mkdir -p generated/serial-div
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL -DHW_DIV src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial-div/mem_rom.dat
	cp generated/mem_ram.dat generated/serial-div/mem_ram.dat
	cp generated/rom.mif generated/serial-div/rom.mif
	sed 's/object JumpTableData/object SerialDivJumpTableData/' generated/JumpTableData.scala \
		> generated/serial-div/SerialDivJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

# Build combined DSP+DIV microcode (simulation variant)
hwmath: ../java/tools/dist/jopa.jar
	mkdir -p generated/hwmath
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) -DDSP_MUL -DHW_DIV src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/hwmath/mem_rom.dat
	cp generated/mem_ram.dat generated/hwmath/mem_ram.dat
	cp generated/rom.mif generated/hwmath/rom.mif
	sed 's/object JumpTableData/object HwMathJumpTableData/' generated/JumpTableData.scala \
		> generated/hwmath/HwMathJumpTableData.scala
	$(MAKE) JVM_TYPE=$(JVM_TYPE)

# Build serial+DSP+DIV microcode
serial-hwmath: ../java/tools/dist/jopa.jar
	mkdir -p generated/serial-hwmath
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL -DDSP_MUL -DHW_DIV src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial-hwmath/mem_rom.dat
	cp generated/mem_ram.dat generated/serial-hwmath/mem_ram.dat
	cp generated/rom.mif generated/serial-hwmath/rom.mif
	sed 's/object JumpTableData/object SerialHwMathJumpTableData/' generated/JumpTableData.scala \
		> generated/serial-hwmath/SerialHwMathJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

# Build FloatCU microcode (simulation variant)
# Note: DSP_MUL omitted — lmul handler assumes fast DSP multiply (1-2 cycles),
# incompatible with IntegerComputeUnit's iterative multiply (~18 cycles).
# lmul falls through to Java software via sys_noim.
floatcu: ../java/tools/dist/jopa.jar
	mkdir -p generated/floatcu
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) -DFLOAT_CU src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/floatcu/mem_rom.dat
	cp generated/mem_ram.dat generated/floatcu/mem_ram.dat
	cp generated/rom.mif generated/floatcu/rom.mif
	sed 's/object JumpTableData/object FloatCuJumpTableData/' generated/JumpTableData.scala \
		> generated/floatcu/FloatCuJumpTableData.scala
	$(MAKE) JVM_TYPE=$(JVM_TYPE)

# Build serial+FloatCU microcode
serial-floatcu: ../java/tools/dist/jopa.jar
	mkdir -p generated/serial-floatcu
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL -DFLOAT_CU src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial-floatcu/mem_rom.dat
	cp generated/mem_ram.dat generated/serial-floatcu/mem_ram.dat
	cp generated/rom.mif generated/serial-floatcu/rom.mif
	sed 's/object JumpTableData/object SerialFloatCuJumpTableData/' generated/JumpTableData.scala \
		> generated/serial-floatcu/SerialFloatCuJumpTableData.scala
	$(MAKE) JVM_TYPE=SIMULATION

../java/tools/dist/jopa.jar:
	cd ../java/tools && $(MAKE) dist/jopa.jar

clean:
	rm -rf generated
