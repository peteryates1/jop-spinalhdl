# QMTECH XC7A100T Wukong V3 — Dual-cluster (DDR3 + SDR SDRAM) constraints
# DDR3 pin constraints are handled by the MIG IP XDC files.
# This file covers board-level pins, SDR SDRAM, and dual-UART.

# ============================================================================
# 50 MHz system clock (Y1 oscillator)
# ============================================================================
set_property PACKAGE_PIN M21 [get_ports {clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk}]
create_clock -period 20.000 -name sys_clk [get_ports {clk}]

# ============================================================================
# Reset (active-low from SpinalHDL default CD)
# ============================================================================
set_property PACKAGE_PIN H7 [get_ports {resetn}]
set_property IOSTANDARD LVCMOS33 [get_ports {resetn}]

# ============================================================================
# Primary UART — CH340N on-board (E3/F3)
# Default (uart_sel=0): DDR3 cluster. When uart_sel=1: SDR cluster.
# ============================================================================
set_property PACKAGE_PIN E3 [get_ports {ser_txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd}]
set_property PACKAGE_PIN F3 [get_ports {ser_rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd}]

# ============================================================================
# Secondary UART — J12 header (Pico UART0)
# U14=FPGA TX → Pico GP1 (RX), V14=FPGA RX ← Pico GP0 (TX)
# Default (uart_sel=0): SDR cluster. When uart_sel=1: DDR3 cluster.
# ============================================================================
set_property PACKAGE_PIN U14 [get_ports {ser_txd_1}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd_1}]
set_property PACKAGE_PIN V14 [get_ports {ser_rxd_1}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd_1}]

# ============================================================================
# UART MUX select — J12 pin 5 (U15), active high, directly driven by Pico GP2
# 0 = DDR3 on CH340 + SDR on J12 (default)
# 1 = SDR on CH340 + DDR3 on J12
# ============================================================================
set_property PACKAGE_PIN U15 [get_ports {uart_sel}]
set_property IOSTANDARD LVCMOS33 [get_ports {uart_sel}]
set_property PULLDOWN TRUE [get_ports {uart_sel}]

# ============================================================================
# On-board LEDs (active high)
# LED[0] = DDR3 cluster heartbeat/hang, LED[1] = SDR cluster heartbeat/hang
# ============================================================================
set_property PACKAGE_PIN G21 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN G20 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

# ============================================================================
# SDR SDRAM (W9825G6KH-6, 32 MB, 16-bit) — all LVCMOS33
# ============================================================================

# SDRAM clock
set_property PACKAGE_PIN G22 [get_ports {sdram_clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_clk}]

# Control signals
set_property PACKAGE_PIN H22 [get_ports {sdram_CKE}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_CKE}]
set_property PACKAGE_PIN L25 [get_ports {sdram_CSn}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_CSn}]
set_property PACKAGE_PIN K26 [get_ports {sdram_RASn}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_RASn}]
set_property PACKAGE_PIN K25 [get_ports {sdram_CASn}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_CASn}]
set_property PACKAGE_PIN J26 [get_ports {sdram_WEn}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_WEn}]

# Bank address [1:0]
set_property PACKAGE_PIN M26 [get_ports {sdram_BA[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_BA[1]}]
set_property PACKAGE_PIN M25 [get_ports {sdram_BA[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_BA[0]}]

# Data mask [1:0]
set_property PACKAGE_PIN K23 [get_ports {sdram_DQM[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQM[1]}]
set_property PACKAGE_PIN J25 [get_ports {sdram_DQM[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQM[0]}]

# Address bus [12:0]
set_property PACKAGE_PIN R26 [get_ports {sdram_ADDR[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[0]}]
set_property PACKAGE_PIN P25 [get_ports {sdram_ADDR[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[1]}]
set_property PACKAGE_PIN P26 [get_ports {sdram_ADDR[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[2]}]
set_property PACKAGE_PIN N26 [get_ports {sdram_ADDR[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[3]}]
set_property PACKAGE_PIN M24 [get_ports {sdram_ADDR[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[4]}]
set_property PACKAGE_PIN M22 [get_ports {sdram_ADDR[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[5]}]
set_property PACKAGE_PIN L24 [get_ports {sdram_ADDR[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[6]}]
set_property PACKAGE_PIN L23 [get_ports {sdram_ADDR[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[7]}]
set_property PACKAGE_PIN L22 [get_ports {sdram_ADDR[8]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[8]}]
set_property PACKAGE_PIN K21 [get_ports {sdram_ADDR[9]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[9]}]
set_property PACKAGE_PIN R25 [get_ports {sdram_ADDR[10]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[10]}]
set_property PACKAGE_PIN K22 [get_ports {sdram_ADDR[11]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[11]}]
set_property PACKAGE_PIN J21 [get_ports {sdram_ADDR[12]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_ADDR[12]}]

# Data bus [15:0]
set_property PACKAGE_PIN D25 [get_ports {sdram_DQ[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[0]}]
set_property PACKAGE_PIN D26 [get_ports {sdram_DQ[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[1]}]
set_property PACKAGE_PIN E25 [get_ports {sdram_DQ[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[2]}]
set_property PACKAGE_PIN E26 [get_ports {sdram_DQ[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[3]}]
set_property PACKAGE_PIN F25 [get_ports {sdram_DQ[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[4]}]
set_property PACKAGE_PIN G25 [get_ports {sdram_DQ[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[5]}]
set_property PACKAGE_PIN G26 [get_ports {sdram_DQ[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[6]}]
set_property PACKAGE_PIN H26 [get_ports {sdram_DQ[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[7]}]
set_property PACKAGE_PIN J24 [get_ports {sdram_DQ[8]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[8]}]
set_property PACKAGE_PIN J23 [get_ports {sdram_DQ[9]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[9]}]
set_property PACKAGE_PIN H24 [get_ports {sdram_DQ[10]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[10]}]
set_property PACKAGE_PIN H23 [get_ports {sdram_DQ[11]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[11]}]
set_property PACKAGE_PIN G24 [get_ports {sdram_DQ[12]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[12]}]
set_property PACKAGE_PIN F24 [get_ports {sdram_DQ[13]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[13]}]
set_property PACKAGE_PIN F23 [get_ports {sdram_DQ[14]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[14]}]
set_property PACKAGE_PIN E23 [get_ports {sdram_DQ[15]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sdram_DQ[15]}]

# SDR SDRAM IOB packing
set_property IOB TRUE [get_ports {sdram_DQ[*]}]
set_property IOB TRUE [get_ports {sdram_DQM[*]}]
set_property IOB TRUE [get_ports {sdram_ADDR[*]}]
set_property IOB TRUE [get_ports {sdram_BA[*]}]
set_property IOB TRUE [get_ports {sdram_CKE}]
set_property IOB TRUE [get_ports {sdram_CSn}]
set_property IOB TRUE [get_ports {sdram_RASn}]
set_property IOB TRUE [get_ports {sdram_CASn}]
set_property IOB TRUE [get_ports {sdram_WEn}]

# ============================================================================
# SDR SDRAM timing constraints and async clock groups are applied
# POST-SYNTHESIS in build_dual_bitstream.tcl (IP internal pins must be resolved).
# ============================================================================

# ============================================================================
# Debug: J12 header (Pico GPIO capture — fast BMB/SDRAM handshake signals)
# Directly driven from SDR clock domain (~80 MHz)
# ============================================================================
set_property PACKAGE_PIN U16 [get_ports {dbg_j12[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[0]}]
set_property PACKAGE_PIN V17 [get_ports {dbg_j12[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[1]}]
set_property PACKAGE_PIN W18 [get_ports {dbg_j12[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[2]}]
set_property PACKAGE_PIN W19 [get_ports {dbg_j12[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[3]}]
set_property PACKAGE_PIN U20 [get_ports {dbg_j12[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[4]}]
set_property PACKAGE_PIN Y21 [get_ports {dbg_j12[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[5]}]
set_property PACKAGE_PIN V22 [get_ports {dbg_j12[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[6]}]
set_property PACKAGE_PIN W23 [get_ports {dbg_j12[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j12[7]}]

# ============================================================================
# Debug: J13 PMOD (8-LED visual — SDR state indicators)
# ============================================================================
set_property PACKAGE_PIN N22 [get_ports {dbg_j13[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[0]}]
set_property PACKAGE_PIN N21 [get_ports {dbg_j13[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[1]}]
set_property PACKAGE_PIN R20 [get_ports {dbg_j13[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[2]}]
set_property PACKAGE_PIN T22 [get_ports {dbg_j13[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[3]}]
set_property PACKAGE_PIN P20 [get_ports {dbg_j13[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[4]}]
set_property PACKAGE_PIN N23 [get_ports {dbg_j13[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[5]}]
set_property PACKAGE_PIN P21 [get_ports {dbg_j13[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[6]}]
set_property PACKAGE_PIN R21 [get_ports {dbg_j13[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {dbg_j13[7]}]

# ============================================================================
# Configuration
# ============================================================================
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
