#
# JOP SDRAM - QMTECH EP4CGX150 Build & Program
#
# Targets:
#   make microcode     - Build serial-boot microcode
#   make generate      - Generate Verilog from SpinalHDL
#   make generate-smp  - Generate SMP Verilog (2 cores)
#   make build         - Quartus compile (map, fit, asm, sta)
#   make program       - Program FPGA via JTAG
#   make download      - Send HelloWorld.jop via serial
#   make monitor       - Monitor UART output (1 Mbaud)
#   make build-smp     - Quartus compile SMP (2 cores)
#   make program-smp   - Program SMP FPGA via JTAG
#   make all           - microcode + generate + build
#   make run           - program + download + monitor
#   make full          - microcode + generate + build + run
#   make full-smp      - microcode + generate-smp + build-smp + run-smp
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

# Quartus projects
QP = jop_sdram
QP_SMP = jop_smp_sdram
OUTPUT_DIR = output_files
SOF_FILE = $(OUTPUT_DIR)/$(QP).sof
SOF_SMP_FILE = $(OUTPUT_DIR)/$(QP_SMP).sof

.PHONY: all microcode generate generate-smp build build-smp program program-smp download monitor run run-smp full full-smp clean

# Default: microcode + generate + build
all: microcode generate build

# Full flow: microcode, generate, build, program, download, monitor
full: microcode generate build run

# SMP full flow
full-smp: microcode generate-smp build-smp run-smp

# Program + download + monitor
run: program download monitor
run-smp: program-smp download monitor

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
	rm -rf output_files db incremental_db $(QP).cdf $(QP_SMP).cdf
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
