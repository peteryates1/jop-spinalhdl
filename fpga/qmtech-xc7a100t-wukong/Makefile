REPO_ROOT := $(shell cd ../.. && pwd)
VIVADO := /opt/xilinx/2025.2/Vivado/bin/vivado
VIVADO_ENV := export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
OPENFPGALOADER := openFPGALoader
BITSTREAM := vivado/build/wukong_jop_bram/wukong_jop_bram.runs/impl_1/JopBramWukongTop.bit
DDR3_BITSTREAM := vivado/build/wukong_jop_ddr3_np/JopDdr3WukongTop.bit
DDR3_SMP_BITSTREAM := vivado/build/wukong_jop_ddr3_smp_np/JopSmpDdr3WukongTop.bit
SDRAM_BITSTREAM := vivado/build/wukong_sdram_exerciser/SdramExerciserWukongTop.bit
JOP_SDRAM_BITSTREAM := vivado/build/wukong_jop_sdram/JopSdramWukongTop.bit
DUAL_BITSTREAM := vivado/build/wukong_jop_dual_np/JopDualWukongTop.bit
# UART_DEV is the board's own CH340N, and applies to the SINGLE-SYSTEM builds
# only -- those use SystemAssembly.wukong with DeviceDriver.UartCh340. The
# DUAL-cluster builds do NOT: the CH340N at E3/F3 is hardwired on the PCB and
# cannot be tapped, so both halves come out of the on-board Pico 2 W instead
# (DDR3 -> PICO_UART1 F4/H4, SDR -> J11_UART A5/A4). See
# docs/architecture/system-configuration.md.
#
# Resolved by alias, since ttyUSBn moves on every replug and this board's CH340
# has no serial to key on -- see fpga/scripts/usb_serial_map, whose header
# explains why these two CH340s need special handling.
UART_DEV ?= $(shell $(REPO_ROOT)/fpga/scripts/usb_serial_map --by-id wukong 2>/dev/null \
              || echo /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0)
UART_BAUD := 1000000
# Exactly 2 Mbaud since 2026-08-18. It used to be 2037000: ui_clk is
# 91.6758 MHz at Ddr3_366, an integer divisor gave 2.0372 Mbaud, and this
# constant existed purely to chase that rounding error. jop.io.Uart now uses a
# fractional baud generator (UartBaudTick), so the wire rate is the requested
# one on any clock.
DDR3_UART_BAUD := 2000000
JOP_FILE ?= $(REPO_ROOT)/java/apps/Smallest/HelloWorld.jop
# This board's Pico 2 W CDC. There are TWO Pico dirtyJtag probes on this host
# (this one and the XC7A100T's E662C876C3506A26), so a bare /dev/ttyACM0 is
# whichever enumerated first -- it can point at the wrong board. The Pico DOES
# have a serial, so this one resolves the ordinary way.
DUAL_SDR_UART_DEV ?= $(shell $(REPO_ROOT)/fpga/scripts/usb_serial_map --by-id wukong-pico-0 2>/dev/null \
                       || echo /dev/serial/by-id/usb-Jean_THOMAS_Pico_Dirty_JTAG_0B383D0435D957EC-if01)
# Both dual halves run at 2 Mbaud -- see JopConfig.wukongDualIndependent, where
# PICO_UART1 and J11_UART both set baudRate 2000000. This said 115200, which no
# longer matches the preset.
DUAL_UART_BAUD := 2000000
DUAL_SDR_UART_BAUD := $(DUAL_UART_BAUD)
# DDR3 half -> PICO_UART1 -> the Pico's SECOND CDC (if03).
DUAL_DDR3_UART_DEV ?= $(shell $(REPO_ROOT)/fpga/scripts/usb_serial_map --by-id wukong-pico-1 2>/dev/null \
                        || echo /dev/serial/by-id/usb-Jean_THOMAS_Pico_Dirty_JTAG_0B383D0435D957EC-if03)

.PHONY: generate create-ip create-project build program program-flash monitor all clean
.PHONY: ddr3-generate ddr3-create-ip ddr3-create-project ddr3-build ddr3-program ddr3-monitor ddr3-download ddr3-reset ddr3-redownload
.PHONY: ddr3-smp-generate ddr3-smp-build ddr3-smp-program ddr3-smp-monitor
.PHONY: dual-generate dual-create-ip dual-build dual-program dual-monitor dual-sdr-monitor dual-sdr-download
.PHONY: sdram-generate sdram-create-ip sdram-build sdram-program sdram-monitor
.PHONY: jop-sdram-generate jop-sdram-build jop-sdram-smp-build jop-sdram-program jop-sdram-monitor
.PHONY: blink-create-project blink-build blink-program

all: build

# === JOP BRAM targets ===

generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog wukongBram"

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
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog wukongFull"

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
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(DDR3_UART_BAUD)

ddr3-download:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -e $(JOP_FILE) $(UART_DEV) $(DDR3_UART_BAUD)

# Reset the core over UART. On DDR3 this is a CORE-ONLY reset -- the MIG keeps
# its calibration, so it costs microseconds, not the seconds a retrain would.
ddr3-reset:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -R $(UART_DEV) $(DDR3_UART_BAUD)

# Swap the running application without reprogramming the FPGA.
ddr3-redownload:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -e -r $(JOP_FILE) $(UART_DEV) $(DDR3_UART_BAUD)

# === JOP DDR3 SMP targets ===

ddr3-smp-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog wukongSmp 2"

ddr3-smp-build:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_ddr3_smp_bitstream.tcl

ddr3-smp-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(DDR3_SMP_BITSTREAM)

ddr3-smp-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(UART_DEV) $(UART_BAUD)

# === JOP Dual-cluster (DDR3 + SDR) targets ===

dual-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog wukongDualIndependent"

dual-create-ip: ddr3-create-ip
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/create_sdram_clk_wiz.tcl

dual-build:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_dual_bitstream.tcl

dual-program:
	sudo $(OPENFPGALOADER) -c dirtyJtag $(DUAL_BITSTREAM)

# The dual build's DDR3 half. VERIFIED on hardware 2026-08-22: both halves come
# out of the Pico's two CDCs at 2 Mbaud, and the CH340 carries nothing at all.
# The two are told apart by their GC card size, which is derived from memory
# size -- 512-word cards on if03 (DDR3, PICO_UART1) and 32-word on if01 (SDR,
# J11_UART). This target used to watch UART_DEV (the CH340) at UART_BAUD
# (1 Mbaud), so it was wrong in both the port and the rate.
dual-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(DUAL_DDR3_UART_DEV) $(DUAL_UART_BAUD)

# SDR cluster: UART via Pico ttyACM0 at 115200 baud
dual-sdr-monitor:
	$(REPO_ROOT)/fpga/scripts/monitor.py $(DUAL_SDR_UART_DEV) $(DUAL_SDR_UART_BAUD)

dual-sdr-download:
	python3 $(REPO_ROOT)/fpga/scripts/download.py -e \
	  $(REPO_ROOT)/java/apps/Smallest/HelloWorld.jop \
	  $(DUAL_SDR_UART_DEV) $(DUAL_SDR_UART_BAUD)

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

# Verilog AND constraints, both from the same JopConfig. This board's XDC is
# generated rather than hand-maintained; see build_jop_sdram_bitstream.tcl.
# Generated artefacts go under build/<config>/, keyed by the invocation --
# see jop.generate.BuildLayout. JOP_CFG_DIR tells the Vivado script where; unset,
# it falls back to the old in-tree layout, so unconverted flows are unaffected.
SDRAM_CFG      ?= wukongSdram
SDRAM_CFG_DIR   = $(REPO_ROOT)/build/$(SDRAM_CFG)

jop-sdram-generate:
	cd $(REPO_ROOT) && sbt "runMain jop.system.JopTopVerilog $(SDRAM_CFG) buildtree"
	cd $(REPO_ROOT) && sbt "runMain jop.generate.XdcGeneratorMain $(SDRAM_CFG) --write build/$(SDRAM_CFG)/vivado/wukong_jop_sdram.xdc"

jop-sdram-build:
	$(VIVADO_ENV) && JOP_CFG_DIR=$(SDRAM_CFG_DIR) $(VIVADO) -mode batch -source vivado/tcl/build_jop_sdram_bitstream.tcl

# SMP SDR: the counterpart of ddr3-smp-build, so SDR and DDR3 can be compared on
# one board with only the memory path differing. Entity is JopSmpSdramWukongTop
# (cpuCnt >= 2 inserts "Smp"), which the single-core script does not build.
jop-sdram-smp-build:
	$(VIVADO_ENV) && $(VIVADO) -mode batch -source vivado/tcl/build_jop_sdram_smp_bitstream.tcl

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
