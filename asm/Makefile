GCC_PARAMS=
ASM_SRC=jvm
JVM_TYPE=SIMULATION

# =========================================================================
# Superset ROM — ALL hardware AND software handlers in one ROM.
# Per-bytecode config is done via jump table patching at SpinalHDL elaboration.
#
# Both HW and SW handlers exist unconditionally in the ROM:
#   <op>: / <op>_hw: → CU handler (sthw/ldop)
#   <op>_sw:         → pure microcode or I/O-based handler
# resolveJumpTable() selects the right one based on JopCoreConfig.
#
# Covered operations:
#   imul, lmul (integer/long multiply)
#   lneg, ladd, lsub, lcmp, lushr, lshr, lshl (long arithmetic/shifts)
#   fadd, fsub, fmul, fdiv (float via CU)
#   fcmpl, fcmpg, i2f, f2i (float CU only, no _sw variant)
# =========================================================================

all: simulation serial

# All handlers are unconditional — no preprocessor flags needed.
# SUPERSET_FLAGS kept empty for future use.
SUPERSET_FLAGS =

simulation: ../java/tools/dist/jopa.jar
	mkdir -p generated
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) $(SUPERSET_FLAGS) src/$(ASM_SRC).asm > generated/$(ASM_SRC).asm
	sed -i '1,35d' generated/$(ASM_SRC).asm
	java -jar ../java/tools/dist/jopa.jar -s generated -d generated $(ASM_SRC).asm
	sed -i 's/object JumpTableData {/object JumpTableData extends JumpTableSource {/' generated/JumpTableData.scala
	@grep -q 'altEntries' generated/JumpTableData.scala || sed -i '/^}/i\  def altEntries: Map[Int, Int] = Map.empty\n  override def dspAltEntries: Map[Int, Int] = Map.empty' generated/JumpTableData.scala

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
