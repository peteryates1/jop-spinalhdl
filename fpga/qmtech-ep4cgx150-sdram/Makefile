#
# JOP SDRAM - QMTECH EP4CGX150 Build & Program
#
# Targets:
#   make microcode     - Build serial-boot microcode
#   make generate      - Generate Verilog from SpinalHDL
#   make generate-smp    - Generate SMP Verilog (2 cores)
#   make generate-dbfpga - Generate DB_FPGA Verilog (Eth+VGA+SD)
#   make build           - Quartus compile (map, fit, asm, sta)
#   make program         - Program FPGA via JTAG
#   make download        - Send HelloWorld.jop via serial
#   make monitor         - Monitor UART output (1 Mbaud)
#   make generate-smp8   - Generate 8-core SMP test Verilog (per-core UART)
#   make build-smp8      - Quartus compile 8-core SMP test
#   make program-smp8    - Program 8-core SMP test via JTAG
#   make full-smp8       - microcode + generate-smp8 + build-smp8 + run-smp8
#   make build-smp       - Quartus compile SMP (2 cores)
#   make program-smp     - Program SMP FPGA via JTAG
#   make build-dbfpga    - Quartus compile DB_FPGA
#   make program-dbfpga  - Program DB_FPGA via JTAG
#   make generate-dbfpga-vgadma - Generate DB_FPGA VGA DMA Verilog
#   make build-dbfpga-vgadma    - Quartus compile DB_FPGA VGA DMA
#   make program-dbfpga-vgadma  - Program DB_FPGA VGA DMA via JTAG
#   make full-dbfpga-vgadma     - Full flow for DB_FPGA VGA DMA
#   make generate-sd-native-exerciser - Generate SD Native exerciser Verilog
#   make generate-sd-spi-exerciser    - Generate SD SPI exerciser Verilog
#   make build-sd-native-exerciser    - Quartus compile SD Native exerciser
#   make build-sd-spi-exerciser       - Quartus compile SD SPI exerciser
#   make program-sd-native-exerciser  - Program SD Native exerciser via JTAG
#   make program-sd-spi-exerciser     - Program SD SPI exerciser via JTAG
#   make full-sd-native-exerciser     - generate + build + program + monitor
#   make full-sd-spi-exerciser        - generate + build + program + monitor
#   make generate-config-flash-exerciser - Generate config flash exerciser Verilog
#   make build-config-flash-exerciser    - Quartus compile config flash exerciser
#   make program-config-flash-exerciser  - Program config flash exerciser via JTAG
#   make full-config-flash-exerciser     - generate + build + program + monitor
#   make generate-flash-programmer       - Generate flash programmer Verilog
#   make build-flash-programmer          - Quartus compile flash programmer
#   make program-flash-programmer        - Program flash programmer via JTAG
#   make full-flash-programmer           - generate + build + program + monitor
#   make flash-program                   - Run Python flash programmer script
#   make flash-image     - Create combined flash image (.rbf + .jop)
#   make flash-jic       - Create .jic for JTAG flash programming
#   make program-flash   - Program flash via JTAG (SFL + .jic)
#   make all             - microcode + generate + build
#   make run             - program + download + monitor
#   make reset           - reset the core over UART (no FPGA reprogram)
#   make redownload      - reset + download + monitor (no FPGA reprogram)
#   make full            - microcode + generate + build + run
#   make full-smp        - microcode + generate-smp + build-smp + run-smp
#   make full-dbfpga     - microcode + generate-dbfpga + build-dbfpga + run-dbfpga
#

# Quartus installation
QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin

# JTAG programmer.
#
# Resolved to a PORT-QUALIFIED cable name -- "USB-Blaster [1-6]", where 1-6 is
# the USB port path. A bare "USB-Blaster" makes Quartus pick the first cable
# whose product string matches, which is fine with one blaster attached and
# ambiguous the moment a second appears. The pico-usb-blaster reports the
# product string "USB Blaster" (no hyphen) and would otherwise be
# indistinguishable in intent; selecting by USB address avoids guessing.
#
# jtag_probe_map maps the board alias -> probe serial -> USB port, so this
# survives replugging (port paths move, serials do not).
BLASTER_TYPE ?= $(shell $(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --cable ep4cgx150)

# Serial port for UART
SERIAL_PORT ?= $(shell ../scripts/usb_serial_map --by-id ep4cgx150 2>/dev/null \
                 || echo /dev/serial/by-id/usb-Silicon_Labs_CP2102N_USB_to_UART_Bridge_Controller_de4cebf79bd1eb11910b7b54e93fd3f1-if00-port0)
BAUD_RATE = 2000000

# JOP application file
JOP_FILE ?= $(PROJECT_ROOT)/java/apps/Smallest/HelloWorld.jop

# Project paths (sbt runs from repo root)
PROJECT_ROOT = ../..
ASM_DIR = ../../asm
# A STAMP, not the .v -- the entity name is derived from the config
# (JopSdramTop at one core, JopSmpSdramTop at two or more), so naming the file
# here would restate config in the Makefile and be wrong for every SMP build.
GEN_STAMP = $(CFG_DIR)/rtl/.generated
GENERATED_SMP_V = $(PROJECT_ROOT)/spinalhdl/generated/JopSmpSdramTop.v
GENERATED_DBFPGA_V = $(PROJECT_ROOT)/spinalhdl/generated/JopSdramTop.v
GENERATED_SD_NATIVE_V = $(PROJECT_ROOT)/spinalhdl/generated/SdNativeExerciserTop.v
GENERATED_SD_SPI_V = $(PROJECT_ROOT)/spinalhdl/generated/SdSpiExerciserTop.v
GENERATED_CFG_FLASH_V = $(PROJECT_ROOT)/spinalhdl/generated/ConfigFlashExerciserTop.v
GENERATED_FLASH_PROG_V = $(PROJECT_ROOT)/spinalhdl/generated/FlashProgrammerTop.v
GENERATED_FLASH_BOOT_V = $(PROJECT_ROOT)/spinalhdl/generated/JopSdramTop.v

# Flash image settings
JOP_OFFSET ?= 0x800000

# Quartus projects
QP = jop_sdram
QP_SMP = jop_smp_sdram
QP_SMP8 = jop_smp8_test
QP_DBFPGA = jop_dbfpga
QP_FLASH_BOOT = jop_flash_boot
QP_SD_NATIVE = sd_native_exerciser
QP_SD_SPI = sd_spi_exerciser
QP_CFG_FLASH = config_flash_exerciser
QP_FLASH_PROG = flash_programmer
OUTPUT_DIR = output_files
JOP_APP_BIN = $(OUTPUT_DIR)/jop_app.bin
FLASH_COF = $(OUTPUT_DIR)/flash_boot.cof
FLASH_JIC = $(OUTPUT_DIR)/flash_image.jic
CFG        ?= ep4cgx150Serial
REV        ?= jop_sdram

# The config directory is keyed by the INVOCATION -- preset plus arguments --
# and BuildLayout owns how that becomes a directory name. A bare preset is a
# Scala identifier, so sanitising it is the identity and no call is needed; an
# invocation WITH arguments ("ep4cgx150Smp 4") is not, so ask BuildLayout rather
# than reimplementing its rules here. A second copy of the naming would split a
# build directory silently the first time an override spelling changed.
ifeq ($(words $(CFG)),1)
  CFG_NAME := $(CFG)
else
  CFG_NAME := $(shell cd $(PROJECT_ROOT) && sbt "runMain jop.generate.BuildLayoutMain $(CFG)" 2>/dev/null \
                | sed -n 's|^\[info\] build/\(.*\)$$|\1|p' | tail -1)
  ifeq ($(CFG_NAME),)
    $(error BuildLayoutMain produced no directory name for CFG="$(CFG)")
  endif
endif

CFG_DIR     = $(PROJECT_ROOT)/build/$(CFG_NAME)
QUARTUS_PRJ = $(CFG_DIR)/quartus

# Under build/<config>/quartus/output_files now, not the in-tree OUTPUT_DIR.
SOF_FILE = $(QUARTUS_PRJ)/output_files/$(REV).sof
SOF_SMP_FILE = $(OUTPUT_DIR)/$(QP_SMP).sof
SOF_SMP8_FILE = $(OUTPUT_DIR)/$(QP_SMP8).sof
SOF_DBFPGA_FILE = $(OUTPUT_DIR)/$(QP_DBFPGA).sof
SOF_FLASH_BOOT_FILE = $(OUTPUT_DIR)/$(QP_FLASH_BOOT).sof
SOF_SD_NATIVE_FILE = $(OUTPUT_DIR)/$(QP_SD_NATIVE).sof
SOF_SD_SPI_FILE = $(OUTPUT_DIR)/$(QP_SD_SPI).sof
SOF_CFG_FLASH_FILE = $(OUTPUT_DIR)/$(QP_CFG_FLASH).sof
SOF_FLASH_PROG_FILE = $(OUTPUT_DIR)/$(QP_FLASH_PROG).sof

.PHONY: all microcode microcode-flash generate generate-smp generate-smp8 generate-dbfpga generate-dbfpga-vgadma generate-flash-boot generate-sd-native-exerciser generate-sd-spi-exerciser generate-config-flash-exerciser generate-flash-programmer build build-smp build-smp8 build-dbfpga build-dbfpga-vgadma build-flash-boot build-sd-native-exerciser build-sd-spi-exerciser build-config-flash-exerciser build-flash-programmer program program-smp program-smp8 program-dbfpga program-dbfpga-vgadma program-flash-boot program-sd-native-exerciser program-sd-spi-exerciser program-config-flash-exerciser program-flash-programmer download reset redownload monitor run run-smp run-smp8 run-dbfpga run-dbfpga-vgadma flash-image flash-jic program-flash flash-program full full-smp full-smp8 full-dbfpga full-dbfpga-vgadma full-flash-boot full-sd-native-exerciser full-sd-spi-exerciser full-config-flash-exerciser full-flash-programmer clean

# Default: microcode + generate + build
all: microcode generate build

# Full flow: microcode, generate, build, program, download, monitor
full: microcode generate build run

# Exercises every working _sw microcode handler on this board — see
# ep4cgx150McFallback in JopConfig.scala and item 19 in docs/current-status.md.
full-mc-fallback: microcode generate-mc-fallback build run

# SMP full flow
full-smp: microcode smp smp-program smp-verify

# 8-core SMP test full flow
full-smp8: microcode generate-smp8 build-smp8 run-smp8

# DB_FPGA full flow
full-dbfpga: microcode generate-dbfpga build-dbfpga run-dbfpga

# DB_FPGA VGA DMA full flow
full-dbfpga-vgadma: microcode generate-dbfpga-vgadma build-dbfpga-vgadma run-dbfpga-vgadma

# Flash boot full flow
full-flash-boot: microcode-flash generate-flash-boot build-flash-boot

# SD exerciser full flows (no microcode or download needed)
full-sd-native-exerciser: generate-sd-native-exerciser build-sd-native-exerciser program-sd-native-exerciser monitor
full-sd-spi-exerciser: generate-sd-spi-exerciser build-sd-spi-exerciser program-sd-spi-exerciser monitor
full-config-flash-exerciser: generate-config-flash-exerciser build-config-flash-exerciser program-config-flash-exerciser monitor
full-flash-programmer: generate-flash-programmer build-flash-programmer program-flash-programmer monitor

# Program + download + monitor
run: program download monitor
run-smp8: program-smp8 download monitor
run-dbfpga: program-dbfpga download monitor
run-dbfpga-vgadma: program-dbfpga-vgadma download monitor

# --------------------------------------------------------------------------
# Serial-boot microcode
# --------------------------------------------------------------------------

microcode:
	cd $(ASM_DIR) && $(MAKE) serial
	@echo "=== Serial microcode generated ==="

microcode-flash:
	cd $(ASM_DIR) && $(MAKE) flash
	@echo "=== Flash microcode generated ==="

# --------------------------------------------------------------------------
# SpinalHDL Verilog generation
# --------------------------------------------------------------------------

# `buildtree` puts the Verilog under build/<config>/rtl with everything else.
# Without it the generator writes to the legacy spinalhdl/generated, which is
# what the boards not yet converted still read.
SCALA_SRC := $(shell find $(PROJECT_ROOT)/spinalhdl/src/main/scala -name '*.scala')
UCODE     := $(wildcard $(PROJECT_ROOT)/build/microcode/serial/mem_*.dat)

$(GEN_STAMP): $(SCALA_SRC) $(UCODE)
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog $(CFG) buildtree"
	@mkdir -p $(dir $@) && touch $@

generate: $(GEN_STAMP)
	@echo "=== Verilog generated into $(CFG_DIR)/rtl ==="

# Microcode-fallback coverage build. Same hardware project as `generate`; only
# the bytecode implementation choices differ, so it reuses build/program/download.
generate-mc-fallback:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog ep4cgx150McFallback"

# SMP IS NOT A DIFFERENT FLOW. It is the same six rules with a different
# configuration, so it re-enters them rather than duplicating them:
#
#   make smp                 2 cores (the default)
#   make smp CORES=4
#   make smp CORES=12 MHZ=36
#
# The duplicated build-smp/program-smp/run-smp rules this replaces each carried
# their own copy of the project name, the SDC path and a dram_pll.vhd that was
# NOT the one the JOP builds used.
CORES ?= 2
MHZ   ?=
SMP_CFG = ep4cgx150Smp $(CORES) $(MHZ)

smp:
	$(MAKE) generate build CFG="$(strip $(SMP_CFG))" REV=jop_smp_sdram

smp-program:
	$(MAKE) program CFG="$(strip $(SMP_CFG))" REV=jop_smp_sdram

smp-verify:
	$(PROJECT_ROOT)/fpga/scripts/hw_verify.py $(strip $(SMP_CFG)) \
	    --app SmpGcTest/SmpGcTest --expect-text "SMPGC OK"

generate-smp8:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopSmp8TestVerilog"
	@echo "=== 8-core SMP test Verilog generated: $(GENERATED_SMP_V) ==="

generate-dbfpga:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"
	@echo "=== DB_FPGA Verilog generated: $(GENERATED_DBFPGA_V) ==="

generate-dbfpga-vgadma:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopTopVerilog ep4cgx150Serial"
	@echo "=== DB_FPGA VGA DMA Verilog generated: $(GENERATED_DBFPGA_V) ==="

generate-flash-boot:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopCfgFlashTopVerilog"
	@echo "=== Flash boot Verilog generated: $(GENERATED_FLASH_BOOT_V) ==="

# CONVERTED (2026-08-25), same shape as the SPI exerciser. Its bidirectional
# pins go through InOutWrapper, which makes them `inout [0:0]`, so they are
# constrained as sd_cmd[0] and friends -- a property of the design's ports, not
# of the card, so it is a DeviceInstance param rather than baked into the model.
SD_NATIVE_CFG_DIR = $(PROJECT_ROOT)/build/sdNativeExerciser
SD_NATIVE_PRJ     = $(SD_NATIVE_CFG_DIR)/quartus

generate-sd-native-exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.SdNativeExerciserBuild"
	cd $(SD_NATIVE_PRJ) && $(QUARTUS_BIN)/quartus_sh -t setup_proj.tcl
	@echo "=== SD Native Exerciser generated into $(SD_NATIVE_CFG_DIR) ==="

# CONVERTED (2026-08-25). Everything -- Verilog, the PLL, the SDC and the Quartus
# project -- is generated from SdSpiExerciserDesign, a BoardDesign, into
# build/sdSpiExerciser/. The hand-written sd_spi_exerciser.qsf it replaces was
# proven PIN-IDENTICAL first, then deleted.
#
# The design is portable across FPGA families now (its PLL comes from
# Board.pllType); this target builds the EP4CGX150 arm, which is where the SD
# card is wired on the DB v4.
SD_SPI_CFG_DIR = $(PROJECT_ROOT)/build/sdSpiExerciser
SD_SPI_PRJ     = $(SD_SPI_CFG_DIR)/quartus

generate-sd-spi-exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.SdSpiExerciserBuild"
	cd $(SD_SPI_PRJ) && $(QUARTUS_BIN)/quartus_sh -t setup_proj.tcl
	@echo "=== SD SPI Exerciser generated into $(SD_SPI_CFG_DIR) ==="

generate-config-flash-exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.ConfigFlashExerciserTopVerilog"
	@echo "=== Config Flash Exerciser Verilog generated: $(GENERATED_CFG_FLASH_V) ==="

generate-flash-programmer:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.FlashProgrammerTopVerilog"
	@echo "=== Flash Programmer Verilog generated: $(GENERATED_FLASH_PROG_V) ==="

# --------------------------------------------------------------------------
# Quartus build
# --------------------------------------------------------------------------

build: $(SOF_FILE)

# PROJECT FILES ARE GENERATED, not tracked. QuartusProjectMain emits a Tcl
# script from JopConfig; quartus_sh runs it and QUARTUS writes the .qsf/.qpf
# itself, so their format follows the tool version rather than being frozen by
# hand (item 57). Proven equivalent: same RTL, hand-written project versus this
# one, 11,076 LE and 268,416 memory bits and +9.714 ns setup slack either way,
# with clk_in on B14 from a User assignment.
#
# Only jop_sdram is converted so far. The exerciser, flash-programmer, dbfpga
# and smp8-test projects still have hand-written .qsf files.
# EVERYTHING GENERATED LIVES UNDER build/<config>/. The old in-tree layout
# still works for the projects not yet converted, so the two coexist: only
# jop_sdram builds out of build/, and the exerciser, flash-programmer, dbfpga
# and smp8-test projects are untouched.
#
# The config directory is keyed by the INVOCATION (preset plus arguments), not
# by the entity name -- JopSmpSdramTop is the same for 2 cores and 12, so
# keying on it would let two configurations share a PLL. See BuildLayout, which
# also owns the structure: change it there, not here.
$(QUARTUS_PRJ)/$(REV).sdc:
	cd $(PROJECT_ROOT) && sbt "runMain jop.generate.TimingConstraintsMain $(CFG) --write build/$(CFG_NAME)/quartus/$(REV).sdc"

$(QUARTUS_PRJ)/setup_proj.tcl:
	cd $(PROJECT_ROOT) && sbt "runMain jop.generate.QuartusProjectMain $(CFG) --revision $(REV) --write build/$(CFG_NAME)/quartus/setup_proj.tcl"

# Quartus writes the .qsf/.qpf itself, in the project directory, from our Tcl.
$(QUARTUS_PRJ)/$(REV).qsf: $(QUARTUS_PRJ)/setup_proj.tcl $(QUARTUS_PRJ)/$(REV).sdc
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sh -t setup_proj.tcl

# TWO dram_pll.vhd FILES EXIST HERE AND THEY ARE NOT THE SAME PLL.
#
#   generated/dram_pll.vhd  written by DramPllGen from the preset's clkMhz.
#                           Used by jop_sdram.qsf, jop_smp_sdram.qsf and
#                           jop_smp_bram.qsf -- i.e. every JOP build.
#   dram_pll.vhd (here)     hand-written, 60 MHz, still used by the exerciser,
#                           flash-programmer, dbfpga and smp8-test projects.
#
# The JOP targets below depend on the GENERATED one, so regenerating at a new
# frequency refits. They used to depend on the hand-written file, which always
# exists and therefore never triggered anything -- and reading it to find out
# what clock a build ran at gives the wrong answer (it says 60 MHz; the STA
# clock table for a 36 MHz preset says 27.777 ns / 18-over-25). Check the STA
# report, not either file.
$(SOF_FILE): $(GEN_STAMP) $(QUARTUS_PRJ)/$(REV).qsf $(CFG_DIR)/ip/dram_pll.vhd
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_map $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_fit $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_asm $(REV)
	cd $(QUARTUS_PRJ) && $(QUARTUS_BIN)/quartus_sta $(REV)
	@echo "=== Build complete: $(SOF_FILE) ==="

build-smp8: $(SOF_SMP8_FILE)

$(SOF_SMP8_FILE): $(GENERATED_SMP_V) $(QP_SMP8).qsf $(QP).sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_SMP8)
	$(QUARTUS_BIN)/quartus_fit $(QP_SMP8)
	$(QUARTUS_BIN)/quartus_asm $(QP_SMP8)
	$(QUARTUS_BIN)/quartus_sta $(QP_SMP8)
	@echo "=== 8-core SMP test Build complete: $(SOF_SMP8_FILE) ==="

build-dbfpga: $(SOF_DBFPGA_FILE)

$(SOF_DBFPGA_FILE): $(GENERATED_DBFPGA_V) $(QP_DBFPGA).qsf $(QP).sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_DBFPGA)
	$(QUARTUS_BIN)/quartus_fit $(QP_DBFPGA)
	$(QUARTUS_BIN)/quartus_asm $(QP_DBFPGA)
	$(QUARTUS_BIN)/quartus_sta $(QP_DBFPGA)
	@echo "=== DB_FPGA Build complete: $(SOF_DBFPGA_FILE) ==="

build-dbfpga-vgadma: $(SOF_DBFPGA_FILE)

build-flash-boot: $(SOF_FLASH_BOOT_FILE)

$(SOF_FLASH_BOOT_FILE): $(GENERATED_FLASH_BOOT_V) $(QP_FLASH_BOOT).qsf $(QP).sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_FLASH_BOOT)
	$(QUARTUS_BIN)/quartus_fit $(QP_FLASH_BOOT)
	$(QUARTUS_BIN)/quartus_asm $(QP_FLASH_BOOT)
	$(QUARTUS_BIN)/quartus_sta $(QP_FLASH_BOOT)
	@echo "=== Flash Boot Build complete: $(SOF_FLASH_BOOT_FILE) ==="

build-sd-native-exerciser:
	cd $(SD_NATIVE_PRJ) && $(QUARTUS_BIN)/quartus_map $(QP_SD_NATIVE)
	cd $(SD_NATIVE_PRJ) && $(QUARTUS_BIN)/quartus_fit $(QP_SD_NATIVE)
	cd $(SD_NATIVE_PRJ) && $(QUARTUS_BIN)/quartus_asm $(QP_SD_NATIVE)
	cd $(SD_NATIVE_PRJ) && $(QUARTUS_BIN)/quartus_sta $(QP_SD_NATIVE)
	@echo "=== SD Native Exerciser built: $(SD_NATIVE_PRJ)/output_files/$(QP_SD_NATIVE).sof ==="

$(SOF_SD_NATIVE_FILE): $(GENERATED_SD_NATIVE_V) $(QP_SD_NATIVE).qsf sd_exerciser.sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_SD_NATIVE)
	$(QUARTUS_BIN)/quartus_fit $(QP_SD_NATIVE)
	$(QUARTUS_BIN)/quartus_asm $(QP_SD_NATIVE)
	$(QUARTUS_BIN)/quartus_sta $(QP_SD_NATIVE)
	@echo "=== SD Native Exerciser Build complete: $(SOF_SD_NATIVE_FILE) ==="

build-sd-spi-exerciser:
	cd $(SD_SPI_PRJ) && $(QUARTUS_BIN)/quartus_map $(QP_SD_SPI)
	cd $(SD_SPI_PRJ) && $(QUARTUS_BIN)/quartus_fit $(QP_SD_SPI)
	cd $(SD_SPI_PRJ) && $(QUARTUS_BIN)/quartus_asm $(QP_SD_SPI)
	cd $(SD_SPI_PRJ) && $(QUARTUS_BIN)/quartus_sta $(QP_SD_SPI)
	@echo "=== SD SPI Exerciser built: $(SD_SPI_PRJ)/output_files/$(QP_SD_SPI).sof ==="

$(SOF_SD_SPI_FILE): $(GENERATED_SD_SPI_V) $(QP_SD_SPI).qsf sd_exerciser.sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_SD_SPI)
	$(QUARTUS_BIN)/quartus_fit $(QP_SD_SPI)
	$(QUARTUS_BIN)/quartus_asm $(QP_SD_SPI)
	$(QUARTUS_BIN)/quartus_sta $(QP_SD_SPI)
	@echo "=== SD SPI Exerciser Build complete: $(SOF_SD_SPI_FILE) ==="

build-config-flash-exerciser: $(SOF_CFG_FLASH_FILE)

$(SOF_CFG_FLASH_FILE): $(GENERATED_CFG_FLASH_V) $(QP_CFG_FLASH).qsf sd_exerciser.sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_CFG_FLASH)
	$(QUARTUS_BIN)/quartus_fit $(QP_CFG_FLASH)
	$(QUARTUS_BIN)/quartus_asm $(QP_CFG_FLASH)
	$(QUARTUS_BIN)/quartus_sta $(QP_CFG_FLASH)
	@echo "=== Config Flash Exerciser Build complete: $(SOF_CFG_FLASH_FILE) ==="

build-flash-programmer: $(SOF_FLASH_PROG_FILE)

$(SOF_FLASH_PROG_FILE): $(GENERATED_FLASH_PROG_V) $(QP_FLASH_PROG).qsf sd_exerciser.sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_FLASH_PROG)
	$(QUARTUS_BIN)/quartus_fit $(QP_FLASH_PROG)
	$(QUARTUS_BIN)/quartus_asm $(QP_FLASH_PROG)
	$(QUARTUS_BIN)/quartus_sta $(QP_FLASH_PROG)
	@echo "=== Flash Programmer Build complete: $(SOF_FLASH_PROG_FILE) ==="

# --------------------------------------------------------------------------
# FPGA programming
# --------------------------------------------------------------------------

$(QP).cdf: $(SOF_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

# This board SHARES the Terasic USB-Blaster with the A-E115FB (that board's own
# Pico drives 3.3V into a 1.8V-banked JTAG and is blocked on level shifters), so
# the cable gets moved between the two and programming the wrong one succeeds
# silently. `assert-device` is a read-only chain scan that catches it.
#
# EVERY program target blocks on it, DELIBERATELY, including while this board's
# IDCODE is still unrecorded (it was unplugged when the check was written, and a
# guard that cannot verify fails closed). So the first program attempt after
# swapping boards will refuse and print the detected IDCODE:
#
#   IDCODE for 'ep4cgx150' is not recorded yet. Detected 020F70DD on ...
#
# Paste that value into the DEVICES table in fpga/scripts/jtag_probe_map and the
# block clears permanently. Failing closed until then is the point: it prompts
# the fix at the moment the information is actually available, rather than
# letting a wrong-board program succeed silently in the meantime.
.PHONY: assert-device
assert-device:
	@$(PROJECT_ROOT)/fpga/scripts/jtag_probe_map --assert-device ep4cgx150

program: assert-device $(SOF_FILE) $(QP).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP).cdf
	@echo "=== FPGA programmed ==="

$(QP_SMP).cdf: $(SOF_SMP_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_SMP).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-smp: assert-device $(SOF_SMP_FILE) $(QP_SMP).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_SMP).cdf
	@echo "=== SMP FPGA programmed ==="

$(QP_SMP8).cdf: $(SOF_SMP8_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_SMP8).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-smp8: assert-device $(SOF_SMP8_FILE) $(QP_SMP8).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_SMP8).cdf
	@echo "=== 8-core SMP test FPGA programmed ==="

$(QP_DBFPGA).cdf: $(SOF_DBFPGA_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_DBFPGA).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-dbfpga: assert-device $(SOF_DBFPGA_FILE) $(QP_DBFPGA).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_DBFPGA).cdf
	@echo "=== DB_FPGA FPGA programmed ==="

program-dbfpga-vgadma: assert-device $(SOF_DBFPGA_FILE) $(QP_DBFPGA).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_DBFPGA).cdf
	@echo "=== DB_FPGA VGA DMA FPGA programmed ==="

$(QP_SD_NATIVE).cdf: $(SOF_SD_NATIVE_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_SD_NATIVE).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-sd-native-exerciser: assert-device
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG \
	    -o "p;$(SD_NATIVE_PRJ)/output_files/$(QP_SD_NATIVE).sof"
	@echo "=== SD Native Exerciser FPGA programmed ==="

$(QP_SD_SPI).cdf: $(SOF_SD_SPI_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_SD_SPI).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-sd-spi-exerciser: assert-device
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG \
	    -o "p;$(SD_SPI_PRJ)/output_files/$(QP_SD_SPI).sof"
	@echo "=== SD SPI Exerciser FPGA programmed ==="

$(QP_CFG_FLASH).cdf: $(SOF_CFG_FLASH_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_CFG_FLASH).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-config-flash-exerciser: assert-device $(SOF_CFG_FLASH_FILE) $(QP_CFG_FLASH).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_CFG_FLASH).cdf
	@echo "=== Config Flash Exerciser FPGA programmed ==="

$(QP_FLASH_PROG).cdf: $(SOF_FLASH_PROG_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_FLASH_PROG).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-flash-programmer: assert-device $(SOF_FLASH_PROG_FILE) $(QP_FLASH_PROG).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_FLASH_PROG).cdf
	@echo "=== Flash Programmer FPGA programmed ==="

$(QP_FLASH_BOOT).cdf: $(SOF_FLASH_BOOT_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CGX150DF27) Path("$(OUTPUT_DIR)/") File("$(QP_FLASH_BOOT).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program-flash-boot: assert-device $(SOF_FLASH_BOOT_FILE) $(QP_FLASH_BOOT).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_FLASH_BOOT).cdf
	@echo "=== Flash Boot FPGA programmed ==="

# --------------------------------------------------------------------------
# Flash image creation and programming
# --------------------------------------------------------------------------

# Convert .sof to .rpd (via .pof) and create combined flash image
# RPD has correct AS option bits; RBF does not.
flash-image: $(SOF_FLASH_BOOT_FILE)
	$(QUARTUS_BIN)/quartus_cpf -c -d EPCS128 $(SOF_FLASH_BOOT_FILE) $(OUTPUT_DIR)/$(QP_FLASH_BOOT).pof
	$(QUARTUS_BIN)/quartus_cpf -c $(OUTPUT_DIR)/$(QP_FLASH_BOOT).pof $(OUTPUT_DIR)/$(QP_FLASH_BOOT).rpd
	python3 ../scripts/make_flash_image.py \
		$(OUTPUT_DIR)/$(QP_FLASH_BOOT).rpd $(JOP_FILE) \
		$(OUTPUT_DIR)/flash_image.bin --jop-offset $(JOP_OFFSET) \
		--jop-binary $(JOP_APP_BIN)
	@echo "=== Flash image created: $(OUTPUT_DIR)/flash_image.bin ==="

# Create .jic from .sof + .jop for flash programming via .cof
flash-jic: flash-image
	@JOP_SIZE=$$(stat -c%s $(JOP_APP_BIN)) && \
	JOP_START=$$(printf '%08X' $$(($(JOP_OFFSET)))) && \
	JOP_END=$$(printf '%08X' $$(($(JOP_OFFSET) + $$JOP_SIZE - 1))) && \
	{ echo '<?xml version="1.0" encoding="US-ASCII" standalone="yes"?>'; \
	  echo '<cof>'; \
	  echo '  <output_filename>$(FLASH_JIC)</output_filename>'; \
	  echo '  <n_pages>1</n_pages>'; \
	  echo '  <width>1</width>'; \
	  echo '  <mode>7</mode>'; \
	  echo '  <sof_data>'; \
	  echo '    <user_name>Page_0</user_name>'; \
	  echo '    <page_flags>1</page_flags>'; \
	  echo '    <bit0>'; \
	  echo '      <sof_filename>$(SOF_FLASH_BOOT_FILE)</sof_filename>'; \
	  echo '    </bit0>'; \
	  echo '  </sof_data>'; \
	  echo '  <raw_binary_data>'; \
	  echo '    <raw_binary_filename>$(JOP_APP_BIN)</raw_binary_filename>'; \
	  echo "    <start_address>$$JOP_START</start_address>"; \
	  echo "    <end_address>$$JOP_END</end_address>"; \
	  echo '  </raw_binary_data>'; \
	  echo '  <eprom_name>EPCS128</eprom_name>'; \
	  echo '  <flash_loader_device>EP4CGX150</flash_loader_device>'; \
	  echo '</cof>'; } > $(FLASH_COF)
	$(QUARTUS_BIN)/quartus_cpf -c $(FLASH_COF)
	@echo "=== JIC created: $(FLASH_JIC) ==="

# Program flash via JTAG (indirect, using Quartus SFL)
# Two-step process: pre-load SFL bridge, then program .jic
# The SFL must be loaded first on Cyclone IV GX boards.
SFL_SOF := $(QUARTUS_BIN)/../common/devinfo/programmer/sfl_ep4cgx150.sof
program-flash: assert-device flash-jic
	@echo "Loading Serial Flash Loader into FPGA..."
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" --mode=JTAG \
		-o "P;$(SFL_SOF)@1"
	@echo "Programming flash with bitstream + application..."
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" --mode=JTAG \
		-o "P;$(FLASH_JIC)@1"
	@echo "=== Flash programmed. Power-cycle board for autonomous boot. ==="

# --------------------------------------------------------------------------
# UART-based flash programming (via FlashProgrammerTop)
# --------------------------------------------------------------------------

flash-program:
	python3 ../scripts/flash_program.py $(OUTPUT_DIR)/flash_image.bin \
		--port $(SERIAL_PORT) --baud $(BAUD_RATE) -v

# --------------------------------------------------------------------------
# Serial download and UART monitor
# --------------------------------------------------------------------------

download:
	python3 ../scripts/download.py -e $(JOP_FILE) $(SERIAL_PORT) $(BAUD_RATE)

# Reset the core over the serial line, no JTAG and no reprogramming. The
# bitstream must contain UartResetEscape (2026-08-18 or later); on older ones
# this is inert rather than harmful.
reset:
	python3 ../scripts/download.py -R $(SERIAL_PORT) $(BAUD_RATE)

# The point of the reset work: swap the running application without touching
# the FPGA. `make redownload JOP_FILE=/path/to/App.jop`.
redownload:
	python3 ../scripts/download.py -e -r $(JOP_FILE) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 ../scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf output_files db incremental_db $(QP).cdf $(QP_SMP).cdf $(QP_SMP8).cdf $(QP_DBFPGA).cdf $(QP_FLASH_BOOT).cdf $(QP_SD_NATIVE).cdf $(QP_SD_SPI).cdf $(QP_CFG_FLASH).cdf $(QP_FLASH_PROG).cdf
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
