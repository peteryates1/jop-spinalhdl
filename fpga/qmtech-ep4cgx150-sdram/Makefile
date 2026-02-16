#
# JOP SDRAM - QMTECH EP4CGX150 Build & Program
#
# Targets:
#   make microcode - Build serial-boot microcode
#   make generate  - Generate Verilog from SpinalHDL
#   make build     - Quartus compile (map, fit, asm, sta)
#   make program   - Program FPGA via JTAG
#   make download  - Send HelloWorld.jop via serial
#   make monitor   - Monitor UART output (1 Mbaud)
#   make all       - microcode + generate + build
#   make run       - program + download + monitor
#   make full      - microcode + generate + build + run
#

# Quartus installation
QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin

# JTAG programmer
BLASTER_TYPE ?= USB-Blaster

# Serial port for UART
SERIAL_PORT ?= /dev/ttyUSB1
BAUD_RATE = 1000000

# JOP application file
JOP_FILE ?= /home/peter/git/jopmin/java/Smallest/HelloWorld.jop

# Project paths
SPINALHDL_DIR = ../../core/spinalhdl
ASM_DIR = ../../asm
GENERATED_V = $(SPINALHDL_DIR)/generated/JopSdramTop.v

# Quartus project
QP = jop_sdram
OUTPUT_DIR = output_files
SOF_FILE = $(OUTPUT_DIR)/$(QP).sof

.PHONY: all microcode generate build program download monitor run full clean

# Default: microcode + generate + build
all: microcode generate build

# Full flow: microcode, generate, build, program, download, monitor
full: microcode generate build run

# Program + download + monitor
run: program download monitor

# --------------------------------------------------------------------------
# Serial-boot microcode
# --------------------------------------------------------------------------

microcode:
	cd $(ASM_DIR) && make -e JVM_TYPE=SERIAL
	mkdir -p $(ASM_DIR)/generated/serial
	cp $(ASM_DIR)/generated/mem_rom.dat $(ASM_DIR)/generated/serial/mem_rom.dat
	cp $(ASM_DIR)/generated/rom.mif $(ASM_DIR)/generated/serial/rom.mif
	cp $(ASM_DIR)/generated/mem_ram.dat $(ASM_DIR)/generated/serial/mem_ram.dat
	cd $(ASM_DIR) && make -e JVM_TYPE=SIMULATION
	@echo "=== Serial microcode generated ==="

# --------------------------------------------------------------------------
# SpinalHDL Verilog generation
# --------------------------------------------------------------------------

generate:
	cd $(SPINALHDL_DIR) && sbt "runMain jop.system.JopSdramTopVerilog"
	@echo "=== Verilog generated: $(GENERATED_V) ==="

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

# --------------------------------------------------------------------------
# Serial download and UART monitor
# --------------------------------------------------------------------------

download:
	python3 download.py -e $(JOP_FILE) $(SERIAL_PORT) $(BAUD_RATE)

monitor:
	python3 monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf output_files db incremental_db $(QP).cdf
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
