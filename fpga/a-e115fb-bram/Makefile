#
# JOP BRAM - A-E115FB EP4CE115 Build & Program
#
# Targets:
#   make generate  - Generate Verilog from SpinalHDL
#   make build     - Quartus compile (map, fit, asm, sta)
#   make program   - Program FPGA via JTAG (USB-Blaster)
#   make program-pico - Program FPGA via pico-fpga JTAG
#   make program-djtag - Program FPGA via pico-dirtyJtag + openFPGALoader
#   make monitor   - Monitor UART output (1 Mbaud)
#   make all       - generate + build
#   make run       - program + monitor
#   make full      - generate + build + program + monitor
#

# Quartus installation
QUARTUS_DIR ?= /opt/altera/25.1/quartus
QUARTUS_BIN = $(QUARTUS_DIR)/bin

# JTAG programmer
BLASTER_TYPE ?= USB-Blaster

# Pico FPGA debug tool
PICO_DEV ?= /dev/pico-la

# Serial port for UART monitoring (CH340 on base board)
SERIAL_PORT ?= /dev/ttyUSB0
BAUD_RATE = 1000000

# SpinalHDL project (sbt runs from repo root)
PROJECT_ROOT = ../..
GENERATED_V = $(PROJECT_ROOT)/spinalhdl/generated/JopBramTop.v

# Quartus project
QP = jop_bram
OUTPUT_DIR = output_files
SOF_FILE = $(OUTPUT_DIR)/$(QP).sof
RBF_FILE = $(OUTPUT_DIR)/$(QP).rbf

.PHONY: all generate build program program-pico program-djtag monitor run full clean

# Default: generate + build
all: generate build

# Full flow: generate, build, program, monitor
full: generate build program monitor

# Generate + program + monitor (skip build if SOF exists)
run: program monitor

# --------------------------------------------------------------------------
# SpinalHDL Verilog generation
# --------------------------------------------------------------------------

generate:
	cd $(PROJECT_ROOT) && sbt "runMain jop.system.JopBramTopVerilog"
	@echo "=== Verilog generated: $(GENERATED_V) ==="

# --------------------------------------------------------------------------
# Quartus build
# --------------------------------------------------------------------------

build: $(SOF_FILE)

$(SOF_FILE): $(GENERATED_V) $(QP).qsf $(QP).sdc ep4ce115_pll.vhd
	$(QUARTUS_BIN)/quartus_map $(QP)
	$(QUARTUS_BIN)/quartus_fit $(QP)
	$(QUARTUS_BIN)/quartus_asm $(QP)
	$(QUARTUS_BIN)/quartus_sta $(QP)
	@echo "=== Build complete: $(SOF_FILE) ==="

# --------------------------------------------------------------------------
# FPGA programming - USB-Blaster
# --------------------------------------------------------------------------

$(QP).cdf: $(SOF_FILE)
	@echo '/* Auto-generated CDF */' > $@
	@echo 'JedecChain;' >> $@
	@echo '	FileRevision(JESD32A);' >> $@
	@echo '	DefaultMfr(6E);' >> $@
	@echo '	P ActionCode(Cfg)' >> $@
	@echo '		Device PartName(EP4CE115F23) Path("$(OUTPUT_DIR)/") File("$(QP).sof") MfrSpec(OpMask(1));' >> $@
	@echo 'ChainEnd;' >> $@
	@echo 'AlteraBegin;' >> $@
	@echo '	ChainType(JTAG);' >> $@
	@echo 'AlteraEnd;' >> $@

program: $(SOF_FILE) $(QP).cdf
	$(QUARTUS_BIN)/quartus_pgm -c "$(BLASTER_TYPE)" -m JTAG $(QP).cdf
	@echo "=== FPGA programmed ==="

# --------------------------------------------------------------------------
# FPGA programming - pico-fpga JTAG
# --------------------------------------------------------------------------

$(RBF_FILE): $(SOF_FILE)
	$(QUARTUS_BIN)/quartus_cpf -c $(SOF_FILE) $(RBF_FILE)
	@echo "=== RBF generated: $(RBF_FILE) ==="

program-pico: $(RBF_FILE)
	@echo "Programming EP4CE115 via pico-fpga on $(PICO_DEV)..."
	@python3 ../scripts/pico_jtag_program.py $(PICO_DEV) $(RBF_FILE)
	@echo "=== FPGA programmed via pico-fpga ==="

# --------------------------------------------------------------------------
# FPGA programming - pico-dirtyJtag + openFPGALoader
# --------------------------------------------------------------------------

program-djtag: $(RBF_FILE)
	@echo "Programming EP4CE115 via pico-dirtyJtag + openFPGALoader..."
	sudo openFPGALoader -c dirtyJtag -m $(RBF_FILE)
	@echo "=== FPGA programmed via pico-dirtyJtag ==="

# --------------------------------------------------------------------------
# UART monitor (1 Mbaud, 8-N-1)
# --------------------------------------------------------------------------

monitor:
	python3 ../scripts/monitor.py $(SERIAL_PORT) $(BAUD_RATE)

# --------------------------------------------------------------------------
# Cleanup
# --------------------------------------------------------------------------

clean:
	rm -rf output_files db incremental_db $(QP).cdf
	rm -f *.rpt *.done *.jdi *.sld *.smsg *.summary *.pin
