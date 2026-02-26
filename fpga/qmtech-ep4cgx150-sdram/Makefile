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
#   make all             - microcode + generate + build
#   make run             - program + download + monitor
#   make full            - microcode + generate + build + run
#   make full-smp        - microcode + generate-smp + build-smp + run-smp
#   make full-dbfpga     - microcode + generate-dbfpga + build-dbfpga + run-dbfpga
#

# Quartus installation
QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin

# JTAG programmer
BLASTER_TYPE ?= USB-Blaster

# Serial port for UART
SERIAL_PORT ?= /dev/ttyUSB0
BAUD_RATE = 1000000

# Original JOP repository
JOP_HOME ?= /srv/git/jop

# JOP application file
JOP_FILE ?= $(JOP_HOME)/java/Smallest/HelloWorld.jop

# Project paths (sbt runs from repo root)
PROJECT_ROOT = ../..
ASM_DIR = ../../asm
GENERATED_V = $(PROJECT_ROOT)/spinalhdl/generated/JopSdramTop.v
GENERATED_SMP_V = $(PROJECT_ROOT)/spinalhdl/generated/JopSmpSdramTop.v
GENERATED_DBFPGA_V = $(PROJECT_ROOT)/spinalhdl/generated/JopSdramTop.v
GENERATED_SD_NATIVE_V = $(PROJECT_ROOT)/spinalhdl/generated/SdNativeExerciserTop.v
GENERATED_SD_SPI_V = $(PROJECT_ROOT)/spinalhdl/generated/SdSpiExerciserTop.v
GENERATED_CFG_FLASH_V = $(PROJECT_ROOT)/spinalhdl/generated/ConfigFlashExerciserTop.v

# Quartus projects
QP = jop_sdram
QP_SMP = jop_smp_sdram
QP_DBFPGA = jop_dbfpga
QP_SD_NATIVE = sd_native_exerciser
QP_SD_SPI = sd_spi_exerciser
QP_CFG_FLASH = config_flash_exerciser
OUTPUT_DIR = output_files
SOF_FILE = $(OUTPUT_DIR)/$(QP).sof
SOF_SMP_FILE = $(OUTPUT_DIR)/$(QP_SMP).sof
SOF_DBFPGA_FILE = $(OUTPUT_DIR)/$(QP_DBFPGA).sof
SOF_SD_NATIVE_FILE = $(OUTPUT_DIR)/$(QP_SD_NATIVE).sof
SOF_SD_SPI_FILE = $(OUTPUT_DIR)/$(QP_SD_SPI).sof
SOF_CFG_FLASH_FILE = $(OUTPUT_DIR)/$(QP_CFG_FLASH).sof

.PHONY: all microcode generate generate-smp generate-dbfpga generate-dbfpga-vgadma generate-sd-native-exerciser generate-sd-spi-exerciser generate-config-flash-exerciser build build-smp build-dbfpga build-dbfpga-vgadma build-sd-native-exerciser build-sd-spi-exerciser build-config-flash-exerciser program program-smp program-dbfpga program-dbfpga-vgadma program-sd-native-exerciser program-sd-spi-exerciser program-config-flash-exerciser download monitor run run-smp run-dbfpga run-dbfpga-vgadma full full-smp full-dbfpga full-dbfpga-vgadma full-sd-native-exerciser full-sd-spi-exerciser full-config-flash-exerciser clean

# Default: microcode + generate + build
all: microcode generate build

# Full flow: microcode, generate, build, program, download, monitor
full: microcode generate build run

# SMP full flow
full-smp: microcode generate-smp build-smp run-smp

# DB_FPGA full flow
full-dbfpga: microcode generate-dbfpga build-dbfpga run-dbfpga

# DB_FPGA VGA DMA full flow
full-dbfpga-vgadma: microcode generate-dbfpga-vgadma build-dbfpga-vgadma run-dbfpga-vgadma

# SD exerciser full flows (no microcode or download needed)
full-sd-native-exerciser: generate-sd-native-exerciser build-sd-native-exerciser program-sd-native-exerciser monitor
full-sd-spi-exerciser: generate-sd-spi-exerciser build-sd-spi-exerciser program-sd-spi-exerciser monitor
full-config-flash-exerciser: generate-config-flash-exerciser build-config-flash-exerciser program-config-flash-exerciser monitor

# Program + download + monitor
run: program download monitor
run-smp: program-smp download monitor
run-dbfpga: program-dbfpga download monitor
run-dbfpga-vgadma: program-dbfpga-vgadma download monitor

# --------------------------------------------------------------------------
# Serial-boot microcode
# --------------------------------------------------------------------------

microcode:
	cd $(ASM_DIR) && $(MAKE) serial
	@echo "=== Serial microcode generated ==="

# --------------------------------------------------------------------------
# SpinalHDL Verilog generation
# --------------------------------------------------------------------------

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopSdramTopVerilog"
	@echo "=== Verilog generated: $(GENERATED_V) ==="

generate-smp:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopSmpSdramTopVerilog"
	@echo "=== SMP Verilog generated: $(GENERATED_SMP_V) ==="

generate-dbfpga:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopDbFpgaTopVerilog"
	@echo "=== DB_FPGA Verilog generated: $(GENERATED_DBFPGA_V) ==="

generate-dbfpga-vgadma:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopDbFpgaVgaDmaTopVerilog"
	@echo "=== DB_FPGA VGA DMA Verilog generated: $(GENERATED_DBFPGA_V) ==="

generate-sd-native-exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.SdNativeExerciserTopVerilog"
	@echo "=== SD Native Exerciser Verilog generated: $(GENERATED_SD_NATIVE_V) ==="

generate-sd-spi-exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.SdSpiExerciserTopVerilog"
	@echo "=== SD SPI Exerciser Verilog generated: $(GENERATED_SD_SPI_V) ==="

generate-config-flash-exerciser:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.ConfigFlashExerciserTopVerilog"
	@echo "=== Config Flash Exerciser Verilog generated: $(GENERATED_CFG_FLASH_V) ==="

# --------------------------------------------------------------------------
# Quartus build
# --------------------------------------------------------------------------

build: $(SOF_FILE)

$(SOF_FILE): $(GENERATED_V) $(QP).qsf $(QP).sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP)
	$(QUARTUS_BIN)/quartus_fit $(QP)
	$(QUARTUS_BIN)/quartus_asm $(QP)
	$(QUARTUS_BIN)/quartus_sta $(QP)
	@echo "=== Build complete: $(SOF_FILE) ==="

build-smp: $(SOF_SMP_FILE)

$(SOF_SMP_FILE): $(GENERATED_SMP_V) $(QP_SMP).qsf $(QP).sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_SMP)
	$(QUARTUS_BIN)/quartus_fit $(QP_SMP)
	$(QUARTUS_BIN)/quartus_asm $(QP_SMP)
	$(QUARTUS_BIN)/quartus_sta $(QP_SMP)
	@echo "=== SMP Build complete: $(SOF_SMP_FILE) ==="

build-dbfpga: $(SOF_DBFPGA_FILE)

$(SOF_DBFPGA_FILE): $(GENERATED_DBFPGA_V) $(QP_DBFPGA).qsf $(QP).sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_DBFPGA)
	$(QUARTUS_BIN)/quartus_fit $(QP_DBFPGA)
	$(QUARTUS_BIN)/quartus_asm $(QP_DBFPGA)
	$(QUARTUS_BIN)/quartus_sta $(QP_DBFPGA)
	@echo "=== DB_FPGA Build complete: $(SOF_DBFPGA_FILE) ==="

build-dbfpga-vgadma: $(SOF_DBFPGA_FILE)

build-sd-native-exerciser: $(SOF_SD_NATIVE_FILE)

$(SOF_SD_NATIVE_FILE): $(GENERATED_SD_NATIVE_V) $(QP_SD_NATIVE).qsf sd_exerciser.sdc dram_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP_SD_NATIVE)
	$(QUARTUS_BIN)/quartus_fit $(QP_SD_NATIVE)
	$(QUARTUS_BIN)/quartus_asm $(QP_SD_NATIVE)
	$(QUARTUS_BIN)/quartus_sta $(QP_SD_NATIVE)
	@echo "=== SD Native Exerciser Build complete: $(SOF_SD_NATIVE_FILE) ==="

build-sd-spi-exerciser: $(SOF_SD_SPI_FILE)

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

program: $(SOF_FILE) $(QP).cdf
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

program-smp: $(SOF_SMP_FILE) $(QP_SMP).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_SMP).cdf
	@echo "=== SMP FPGA programmed ==="

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

program-dbfpga: $(SOF_DBFPGA_FILE) $(QP_DBFPGA).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_DBFPGA).cdf
	@echo "=== DB_FPGA FPGA programmed ==="

program-dbfpga-vgadma: $(SOF_DBFPGA_FILE) $(QP_DBFPGA).cdf
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

program-sd-native-exerciser: $(SOF_SD_NATIVE_FILE) $(QP_SD_NATIVE).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_SD_NATIVE).cdf
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

program-sd-spi-exerciser: $(SOF_SD_SPI_FILE) $(QP_SD_SPI).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_SD_SPI).cdf
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

program-config-flash-exerciser: $(SOF_CFG_FLASH_FILE) $(QP_CFG_FLASH).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP_CFG_FLASH).cdf
	@echo "=== Config Flash Exerciser FPGA programmed ==="

# --------------------------------------------------------------------------
# Serial download and UART monitor
# --------------------------------------------------------------------------

download:
	python3 ../scripts/download.py -e $(JOP_FILE) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 ../scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf output_files db incremental_db $(QP).cdf $(QP_SMP).cdf $(QP_DBFPGA).cdf $(QP_SD_NATIVE).cdf $(QP_SD_SPI).cdf $(QP_CFG_FLASH).cdf
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
