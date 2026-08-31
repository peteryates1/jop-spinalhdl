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

.PHONY: download redownload reset monitor console-info require-baud require-port

# AN UNKNOWN BAUD MUST STOP THE FLOW, NOT FALL BACK TO A CONSTANT.
#
# The BAUD assignment above is a $(shell grep ... 2>/dev/null), so it yields
# EMPTY whenever the summary is absent or carries no 'UART baud' line -- and
# StandaloneBuild omits that line by design for a design with no UART. An empty
# variable expands to nothing, the positional argument vanishes, and
# download.py substitutes its own 2000000. On a 1 Mbaud design that prints
# garbage, which is indistinguishable from a board that never booted.
#
# That is the exact failure this file's header says it was written to remove:
# twelve boards each carried their own BAUD_RATE and two of them refused to
# download in one session because a constant disagreed with the config. The
# constant was not eliminated by centralising the derivation -- it survived in
# download.py, out of sight of anyone reading the Makefile.
#
# Passing BAUD=<rate> explicitly still works; the point is that it must be a
# choice someone made, not a value nobody chose.
require-baud:
	@if [ -z "$(BAUD)" ]; then \
	  echo "make: the baud rate is not known for this configuration." >&2; \
	  echo "  BAUD is read from $(CFG_DIR)/rtl/*.summary.txt, which is either" >&2; \
	  echo "  missing or carries no 'UART baud' line." >&2; \
	  echo "  Build this config first, or pass BAUD=<rate> deliberately." >&2; \
	  exit 1; \
	fi

# AN UNRESOLVED PORT MUST STOP THE FLOW TOO.
#
# SERIAL_PORT is a $(shell usb_serial_map ...) that yields EMPTY whenever the
# board is unplugged, the adapter has stalled its control endpoint, or the alias
# is not in the registry. With it empty the recipe becomes
#
#   download.py -R  2000000
#
# and download.py binds the BAUD as the positional PORT -- so it fails with
# "could not open port 2000000", naming the baud as the port. Loud but
# misleading, and the same shape as the baud defect: a value nobody chose
# arriving where a real one was expected. Status item 126.
require-port:
	@if [ -z "$(SERIAL_PORT)" ]; then \
	  echo "make: the serial port could not be resolved for '$(CONSOLE_ALIAS)'." >&2; \
	  echo "  SERIAL_PORT comes from fpga/scripts/usb_serial_map --by-id, which" >&2; \
	  echo "  found no match. The board may be unplugged, the alias may be" >&2; \
	  echo "  missing from the registry, or the adapter may have stalled its" >&2; \
	  echo "  control endpoint (try: usb_serial_map --reset $(CONSOLE_ALIAS))." >&2; \
	  exit 1; \
	fi

console-info:
	@echo "console : $(CONSOLE_ALIAS) -> $(SERIAL_PORT)"
	@echo "baud    : $(BAUD)  (from $(CFG_DIR)/rtl/*.summary.txt)"
	@echo "image   : $(JOP_FILE)"

ifeq ($(CONSOLE_TXONLY),yes)

download redownload reset:
	@echo "N/A: this design's UART is transmit-only — use 'make monitor'." >&2
	@exit 1

else

download: require-baud require-port
	python3 $(PROJECT_ROOT)/fpga/scripts/download.py -e $(JOP_FILE) $(SERIAL_PORT) $(BAUD)

# Swap the running application without reprogramming the FPGA. Needs a
# bitstream containing UartResetEscape (2026-08-18 or later).
redownload: require-baud require-port
	python3 $(PROJECT_ROOT)/fpga/scripts/download.py -e -r $(JOP_FILE) $(SERIAL_PORT) $(BAUD)

reset: require-baud require-port
	python3 $(PROJECT_ROOT)/fpga/scripts/download.py -R $(SERIAL_PORT) $(BAUD)

endif

monitor: require-baud require-port
	python3 $(PROJECT_ROOT)/fpga/scripts/monitor.py $(SERIAL_PORT) $(BAUD)
