# ---------------------------------------------------------------------------
# The serial console, once. Toolchain-independent: included by quartus.mk and
# usable directly by the Vivado and nextpnr boards.
#
# The board sets CONSOLE_ALIAS (a name from fpga/scripts/usb_serial_map) and
# gets SERIAL_PORT, BAUD, download, redownload, reset and monitor.
#
# THE BAUD COMES FROM THE BUILD, NOT FROM A CONSTANT. Every board Makefile used
# to carry its own BAUD_RATE / UART_BAUD / DDR3_UART_BAUD -- twelve of them,
# seven saying 2 M and four saying 1 M -- and none of them was what the
# bitstream actually used. The rate is baked in at elaboration and each build
# records it in <Top>.summary.txt, so that file is the authority and this reads
# it. Two boards refused to download in one session because a Makefile constant
# disagreed with the config; on the Wukong the same constant was wrong for one
# DDR3 preset and right for another. See status item 70.
#
# BAUD may still be overridden on the command line for a deliberate experiment.
# ---------------------------------------------------------------------------

# Resolved by SERIAL, never by /dev/ttyUSBn: the numbers move on every replug,
# and more than one board of each kind is attached to this host.
SERIAL_PORT ?= $(shell $(PROJECT_ROOT)/fpga/scripts/usb_serial_map --by-id $(CONSOLE_ALIAS))

# $$NF, not $$3. A single-system summary reads "  UART baud:   2000000" so the
# rate is field 3, but a MULTI-SYSTEM one prefixes each line with the system
# name -- "  [ddr3] UART baud:   2000000" -- which shifts it and yielded the
# literal string "baud:". Nothing caught it because no dual-cluster board used
# console.mk until the Wukong did. The rate is always the last field.
#
# head -1 takes the FIRST system's rate. Both halves of the dual build run at
# 2 Mbaud (JopConfig.wukongDualIndependent sets PICO_UART1 and J11_UART alike),
# so this is correct today; if a future dual config gives its halves different
# rates, this needs to select by system rather than take the first.
BAUD ?= $(shell grep -h 'UART baud' $(CFG_DIR)/rtl/*.summary.txt 2>/dev/null \
           | head -1 | awk '{print $$NF}')

JOP_FILE ?= $(CFG_DIR)/java/apps/Smallest/HelloWorld.jop

# A design whose UART is TRANSMIT-ONLY -- the SDRAM exerciser reports results
# and listens to nothing -- sets CONSOLE_TXONLY=yes. It still gets `monitor`;
# download, redownload and reset are meaningless without a receiver, and are
# replaced by targets that say so. Defining them in the board Makefile instead
# would work, but every such board would draw "overriding recipe for target"
# warnings, and warnings that are normal are warnings nobody reads.
CONSOLE_TXONLY ?= no

.PHONY: download redownload reset monitor console-info

console-info:
	@echo "console : $(CONSOLE_ALIAS) -> $(SERIAL_PORT)"
	@echo "baud    : $(BAUD)  (from $(CFG_DIR)/rtl/*.summary.txt)"
	@echo "image   : $(JOP_FILE)"

ifeq ($(CONSOLE_TXONLY),yes)

download redownload reset:
	@echo "N/A: this design's UART is transmit-only — use 'make monitor'." >&2
	@exit 1

else

download:
	python3 $(PROJECT_ROOT)/fpga/scripts/download.py -e $(JOP_FILE) $(SERIAL_PORT) $(BAUD)

# Swap the running application without reprogramming the FPGA. Needs a
# bitstream containing UartResetEscape (2026-08-18 or later).
redownload:
	python3 $(PROJECT_ROOT)/fpga/scripts/download.py -e -r $(JOP_FILE) $(SERIAL_PORT) $(BAUD)

reset:
	python3 $(PROJECT_ROOT)/fpga/scripts/download.py -R $(SERIAL_PORT) $(BAUD)

endif

monitor:
	python3 $(PROJECT_ROOT)/fpga/scripts/monitor.py $(SERIAL_PORT) $(BAUD)
