GCC_PARAMS=
ASM_SRC=jvm
JVM_TYPE=SIMULATION

# =========================================================================
# Superset ROM targets — ALL hardware handlers in one ROM per boot mode.
# Per-bytecode config is done via jump table patching at SpinalHDL elaboration.
#
# Both HW and SW handlers exist unconditionally in the ROM:
#   imul: → sthw handler (IntegerComputeUnit)
#   imul_sw: → pure microcode shift-and-add multiply
# resolveJumpTable() selects the right one based on JopCoreConfig.
#
# FLOAT_CU: FloatComputeUnit handlers (fadd-fdiv, i2f, f2i, fcmp, fneg).
# =========================================================================

all: simulation serial

# Superset flags: FLOAT_CU=float ops via FloatCU
# Note: HW_MUL removed — both imul handlers are unconditional in jvm.asm
SUPERSET_FLAGS = -DFLOAT_CU

simulation: ../java/tools/dist/jopa.jar
	mkdir -p generated
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) $(SUPERSET_FLAGS) src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm

serial: ../java/tools/dist/jopa.jar
	mkdir -p generated/serial
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL $(SUPERSET_FLAGS) src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated -n SerialJumpTableData $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/serial/mem_rom.dat
	cp generated/mem_ram.dat generated/serial/mem_ram.dat
	cp generated/rom.mif generated/serial/rom.mif
	mv generated/SerialJumpTableData.scala generated/serial/SerialJumpTableData.scala
	$(MAKE) simulation

# Flash-boot for Altera (Cyclone IV GX, EPCS64 serial flash)
# FLASH_ADDR_B2=128 (default), FLASH_CLK_DIV=3 (default)
flash-altera: ../java/tools/dist/jopa.jar
	mkdir -p generated/flash
	gcc -x c -E -C -P $(GCC_PARAMS) -DFLASH $(SUPERSET_FLAGS) src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated -n FlashJumpTableData $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/flash/mem_rom.dat
	cp generated/mem_ram.dat generated/flash/mem_ram.dat
	cp generated/rom.mif generated/flash/rom.mif
	mv generated/FlashJumpTableData.scala generated/flash/FlashJumpTableData.scala
	$(MAKE) simulation

# Flash-boot for Xilinx (Artix-7, 4 MB SPI flash, offset 0x240000)
flash-alchitry: ../java/tools/dist/jopa.jar
	mkdir -p generated/flash
	gcc -x c -E -C -P $(GCC_PARAMS) -DFLASH $(SUPERSET_FLAGS) -DFLASH_ADDR_B2=36 -DFLASH_CLK_DIV=15 -DSKIP_FLASH_RESET src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated -n FlashJumpTableData $(ASM_SRC).asm
	cp generated/mem_rom.dat generated/flash/mem_rom.dat
	cp generated/mem_ram.dat generated/flash/mem_ram.dat
	cp generated/rom.mif generated/flash/rom.mif
	mv generated/FlashJumpTableData.scala generated/flash/FlashJumpTableData.scala
	$(MAKE) simulation

../java/tools/dist/jopa.jar:
	cd ../java/tools && $(MAKE) dist/jopa.jar

clean:
	rm -rf generated
