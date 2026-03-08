# QMTECH XC7A100T Wukong V3 — SDRAM Exerciser constraints
# Pin assignments from docs/boards/qmtech-wukong-board.md

# 50 MHz system clock (Y1 oscillator)
set_property PACKAGE_PIN M21 [get_ports {clk_in}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk_in}]
create_clock -period 20.000 -name sys_clk [get_ports {clk_in}]

# UART TX (CH340N on-board)
set_property PACKAGE_PIN E3 [get_ports {ser_txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd}]

# On-board LEDs (active high)
set_property PACKAGE_PIN G21 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN G20 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

# J10 PMOD diagnostic LEDs (active high, active low, or direct drive)
set_property PACKAGE_PIN D5 [get_ports {j10_led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[0]}]
set_property PACKAGE_PIN G5 [get_ports {j10_led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[1]}]
set_property PACKAGE_PIN G7 [get_ports {j10_led[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[2]}]
set_property PACKAGE_PIN G8 [get_ports {j10_led[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[3]}]
set_property PACKAGE_PIN E5 [get_ports {j10_led[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[4]}]
set_property PACKAGE_PIN E6 [get_ports {j10_led[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[5]}]
set_property PACKAGE_PIN D6 [get_ports {j10_led[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[6]}]
set_property PACKAGE_PIN G6 [get_ports {j10_led[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {j10_led[7]}]

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

# SDR SDRAM IOB packing (shared)
source ../../../../fpga/constraints/sdram_sdr.xdc

# ============================================================================
# Configuration
# ============================================================================
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
