GCC_PARAMS=
ASM_SRC=jvm
JVM_TYPE=SIMULATION

# Where the generated microcode goes: build/microcode/<variant>/, one directory
# per BOOT MODE. This tree is NOT per-configuration -- it is keyed by boot mode,
# and its JumpTableData.scala siblings are compile INPUTS to the SpinalHDL build
# (build.sbt lists them as unmanagedSourceDirectories), so it cannot live under
# build/<config>/ the way generated RTL does. See docs/current-status.md item 60.
#
# Every variant now gets its own directory, INCLUDING simulation, which used to
# be written to the tree root while the others got subdirectories. That
# asymmetry is why JopConfig needed a special case, and why the two LPM
# simulation blackboxes in fpga/ip/altera_lpm/ point at a path that never
# existed -- they already assumed the uniform layout.
OUT ?= ../build/microcode

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

# FLASH IS PART OF `all` NOW. JumpTableInitData references FlashJumpTableData
# unconditionally, so the SpinalHDL build does not compile without it -- but
# `all` built only simulation and serial, and the flash directory survived
# only because someone had once run flash-altera by hand. A clean checkout could
# not compile. flash-altera is the default of the two flash variants; the
# Alchitry one differs in FLASH_ADDR_B2 and SKIP_FLASH_RESET and is still built
# explicitly.
all: simulation serial flash-altera

# All handlers are unconditional — no preprocessor flags needed.
# SUPERSET_FLAGS kept empty for future use.
SUPERSET_FLAGS =

simulation: ../build/java/tools/jopa.jar
	mkdir -p $(OUT)/simulation
	gcc -x c -E -C -P $(GCC_PARAMS) -D$(JVM_TYPE) $(SUPERSET_FLAGS) src/$(ASM_SRC).asm > $(OUT)/simulation/$(ASM_SRC).asm
	sed -i '1,35d' $(OUT)/simulation/$(ASM_SRC).asm
	java -jar ../build/java/tools/jopa.jar -s $(OUT)/simulation -d $(OUT)/simulation $(ASM_SRC).asm
	python3 make_ram_mif.py $(OUT)/simulation/mem_ram.dat $(OUT)/simulation/ram.mif
	sed -i 's/object JumpTableData {/object JumpTableData extends JumpTableSource {/' $(OUT)/simulation/JumpTableData.scala
	@grep -q 'altEntries' $(OUT)/simulation/JumpTableData.scala || sed -i '/^}/i\  def altEntries: Map[Int, Int] = Map.empty\n  override def dspAltEntries: Map[Int, Int] = Map.empty' $(OUT)/simulation/JumpTableData.scala

serial: ../build/java/tools/jopa.jar
	mkdir -p $(OUT)/serial
	gcc -x c -E -C -P $(GCC_PARAMS) -DSERIAL $(SUPERSET_FLAGS) src/$(ASM_SRC).asm > $(OUT)/serial/$(ASM_SRC).asm
	sed -i '1,35d' $(OUT)/serial/$(ASM_SRC).asm
	java -jar ../build/java/tools/jopa.jar -s $(OUT)/serial -d $(OUT)/serial -n SerialJumpTableData $(ASM_SRC).asm
	python3 make_ram_mif.py $(OUT)/serial/mem_ram.dat $(OUT)/serial/ram.mif
	$(MAKE) simulation

# Flash-boot for Altera (Cyclone IV GX, EPCS64 serial flash)
# FLASH_ADDR_B2=128 (default); clock divider set by hardware init
flash-altera: ../build/java/tools/jopa.jar
	mkdir -p $(OUT)/flash
	gcc -x c -E -C -P $(GCC_PARAMS) -DFLASH $(SUPERSET_FLAGS) src/$(ASM_SRC).asm > $(OUT)/flash/$(ASM_SRC).asm
	sed -i '1,35d' $(OUT)/flash/$(ASM_SRC).asm
	java -jar ../build/java/tools/jopa.jar -s $(OUT)/flash -d $(OUT)/flash -n FlashJumpTableData $(ASM_SRC).asm
	python3 make_ram_mif.py $(OUT)/flash/mem_ram.dat $(OUT)/flash/ram.mif
	$(MAKE) simulation

# Flash-boot for Xilinx (Artix-7, 4 MB SPI flash, offset 0x240000)
flash-alchitry: ../build/java/tools/jopa.jar
	mkdir -p $(OUT)/flash
	gcc -x c -E -C -P $(GCC_PARAMS) -DFLASH $(SUPERSET_FLAGS) -DFLASH_ADDR_B2=36 -DSKIP_FLASH_RESET src/$(ASM_SRC).asm > $(OUT)/flash/$(ASM_SRC).asm
	sed -i '1,35d' $(OUT)/flash/$(ASM_SRC).asm
	java -jar ../build/java/tools/jopa.jar -s $(OUT)/flash -d $(OUT)/flash -n FlashJumpTableData $(ASM_SRC).asm
	python3 make_ram_mif.py $(OUT)/flash/mem_ram.dat $(OUT)/flash/ram.mif
	$(MAKE) simulation

# jopa is the CONFIG-INDEPENDENT tool -- it never references Const -- so it
# lives in the common build tree and needs no preset to build.
../build/java/tools/jopa.jar:
	cd ../java/tools && $(MAKE) jopa

clean:
	rm -rf $(OUT)
