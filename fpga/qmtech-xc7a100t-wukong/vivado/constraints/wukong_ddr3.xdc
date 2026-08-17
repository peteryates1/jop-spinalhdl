# QMTECH XC7A100T Wukong V3 — JOP DDR3 constraints (full: base + Ethernet + SD)
# DDR3 pin constraints are handled by the MIG IP XDC files.

# Base constraints (clk, reset, UART, LEDs, config)
source [file join [file dirname [info script]] wukong_ddr3_base.xdc]

# ============================================================================
# Ethernet GMII (RTL8211EG PHY) — all LVCMOS33, Bank 34
# ============================================================================

# TX path (FPGA → PHY)
set_property PACKAGE_PIN U1 [get_ports {e_gtxc}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_gtxc}]
set_property PACKAGE_PIN T2 [get_ports {e_txen}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txen}]
set_property PACKAGE_PIN J1 [get_ports {e_txer}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txer}]
set_property PACKAGE_PIN R2 [get_ports {e_txd[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[0]}]
set_property PACKAGE_PIN P1 [get_ports {e_txd[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[1]}]
set_property PACKAGE_PIN N2 [get_ports {e_txd[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[2]}]
set_property PACKAGE_PIN N1 [get_ports {e_txd[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[3]}]
set_property PACKAGE_PIN M1 [get_ports {e_txd[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[4]}]
set_property PACKAGE_PIN L2 [get_ports {e_txd[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[5]}]
set_property PACKAGE_PIN K2 [get_ports {e_txd[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[6]}]
set_property PACKAGE_PIN K1 [get_ports {e_txd[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_txd[7]}]

# RX path (PHY → FPGA)
set_property PACKAGE_PIN P4 [get_ports {e_rxc}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxc}]
set_property PACKAGE_PIN L3 [get_ports {e_rxdv}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxdv}]
set_property PACKAGE_PIN U5 [get_ports {e_rxer}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxer}]
set_property PACKAGE_PIN M4 [get_ports {e_rxd[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[0]}]
set_property PACKAGE_PIN N3 [get_ports {e_rxd[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[1]}]
set_property PACKAGE_PIN N4 [get_ports {e_rxd[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[2]}]
set_property PACKAGE_PIN P3 [get_ports {e_rxd[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[3]}]
set_property PACKAGE_PIN R3 [get_ports {e_rxd[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[4]}]
set_property PACKAGE_PIN T3 [get_ports {e_rxd[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[5]}]
set_property PACKAGE_PIN T4 [get_ports {e_rxd[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[6]}]
set_property PACKAGE_PIN T5 [get_ports {e_rxd[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_rxd[7]}]

# Management (MDIO/MDC)
set_property PACKAGE_PIN H1 [get_ports {e_mdio[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_mdio[0]}]
set_property PACKAGE_PIN H2 [get_ports {e_mdc}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_mdc}]
set_property PACKAGE_PIN R1 [get_ports {e_resetn}]
set_property IOSTANDARD LVCMOS33 [get_ports {e_resetn}]

# GMII timing constraints
source ../../../../fpga/constraints/rtl8211eg_gmii.xdc

# CDC false paths: Ethernet 125 MHz ↔ MIG ui_clk (clk_pll_i)
# All crossings use SpinalHDL BufferCC/StreamFifoCC (safe gray-code CDC).
set_clock_groups -asynchronous \
    -group [get_clocks clk_125_ddr3_clk] \
    -group [get_clocks clk_pll_i]
set_clock_groups -asynchronous \
    -group [get_clocks e_rxc] \
    -group [get_clocks clk_pll_i] \
    -group [get_clocks clk_125_ddr3_clk]

# CDC false path: board oscillator (sys_clk, 50 MHz) <-> MIG ui_clk (clk_pll_i).
# Same justification as above -- the crossings are SpinalHDL BufferCC, e.g.
# io_uart_txd_buffercc -- but this one stayed INVISIBLE while ui_clk was exactly
# 2x sys_clk, because aligned edges gave the analyser a full period to play with.
# Lower ui_clk to a non-integer ratio (91.65 MHz for a 2727 ps MIG) and the same
# path is suddenly required to close in 0.325 ns:
#
#   Source:      cores_0/uart_1/.../_zz_io_txd_reg   clk_pll_i period=10.911
#   Destination: io_uart_txd_buffercc/buffers_0_reg  sys_clk   period=20.000
#   Requirement: 0.325ns (sys_clk rise@10780.000 - clk_pll_i rise@10779.675)
#
# which produced WNS -2.037 over 31 endpoints and looked like an arbiter failure
# until the failing path was actually read. It is a synchroniser; constrain it as
# one.
set_clock_groups -asynchronous \
    -group [get_clocks sys_clk] \
    -group [get_clocks clk_pll_i]

# ============================================================================
# SD Card (microSD J9, 4-bit native mode) — all LVCMOS33
# ============================================================================

set_property PACKAGE_PIN L4 [get_ports {sd_clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {sd_clk}]
set_property PACKAGE_PIN J8 [get_ports {sd_cmd[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sd_cmd[0]}]
set_property PACKAGE_PIN M5 [get_ports {sd_dat_0[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sd_dat_0[0]}]
set_property PACKAGE_PIN M7 [get_ports {sd_dat_1[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sd_dat_1[0]}]
set_property PACKAGE_PIN H6 [get_ports {sd_dat_2[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sd_dat_2[0]}]
set_property PACKAGE_PIN J6 [get_ports {sd_dat_3[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {sd_dat_3[0]}]
set_property PACKAGE_PIN N6 [get_ports {sd_cd}]
set_property IOSTANDARD LVCMOS33 [get_ports {sd_cd}]
