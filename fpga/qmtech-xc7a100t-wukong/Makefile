REPO_ROOT := $(shell cd ../.. && pwd)
VIVADO := /opt/xilinx/2025.2/Vivado/bin/vivado
VIVADO_ENV := export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
OPENFPGALOADER := openFPGALoader
BITSTREAM := vivado/build/wukong_jop_bram/wukong_jop_bram.runs/impl_1/JopBramWukongTop.bit
DDR3_BITSTREAM := vivado/build/wukong_jop_ddr3_np/JopDdr3WukongTop.bit
SDRAM_BITSTREAM := vivado/build/wukong_sdram_exerciser/SdramExerciserWukongTop.bit
JOP_SDRAM_BITSTREAM := vivado/build/wukong_jop_sdram/JopSdramWukongTop.bit
UART_DEV := /dev/ttyUSB0
UART_BAUD := 1000000

.PHONY: generate create-ip create-project build program program-flash monitor all clean
.PHONY: ddr3-generate ddr3-create-ip ddr3-create-project ddr3-build ddr3-program ddr3-monitor
.PHONY: sdram-generate sdram-create-ip sdram-build sdram-program sdram-monitor
.PHONY: jop-sdram-generate jop-sdram-build jop-sdram-program jop-sdram-monitor
.PHONY: blink-create-project blink-build blink-program

all: build

# === JOP BRAM targets ===

generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopBramWukongTopVerilog"

create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_clk_wiz.tcl

create-project: generate
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_project.tcl

build: create-project
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_bitstream.tcl

program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(BITSTREAM)

program-flash:
	sudo $(OPENFPGALOADER) -c dirtyJtag --write-flash $(BITSTREAM)

monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === JOP DDR3 targets ===

ddr3-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopDdr3WukongTopVerilog"

ddr3-create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_ddr3_clk_wiz.tcl
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_mig.tcl

ddr3-create-project: ddr3-generate
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_ddr3_project.tcl

ddr3-build:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_ddr3_bitstream.tcl

ddr3-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(DDR3_BITSTREAM)

ddr3-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === SDRAM Exerciser targets ===

sdram-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.SdramExerciserWukongTopVerilog"

sdram-create-ip:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_sdram_clk_wiz.tcl

sdram-build:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_sdram_bitstream.tcl

sdram-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(SDRAM_BITSTREAM)

sdram-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === JOP SDRAM targets ===

jop-sdram-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopSdramWukongTopVerilog"

jop-sdram-build:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_jop_sdram_bitstream.tcl

jop-sdram-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(JOP_SDRAM_BITSTREAM)

jop-sdram-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === Blink targets (LED blinker for board bring-up) ===
# Requires pre-generated spinalhdl/generated/WukongBlink.v

blink-create-project:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_blink_project.tcl

blink-build: blink-create-project
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_blink_bitstream.tcl

blink-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag vivado/build/wukong_blink/wukong_blink.runs/impl_1/WukongBlink.bit

clean:
	rm -rf vivado/build vivado/ip
