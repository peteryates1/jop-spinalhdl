# fpga-db-colorlight-i5 base board + Colorlight i9+ (XC7A50T-FGG484).
# Hand-written for the bring-up jig (see ../README.md). Balls come from
# Netlist_fpga-db-colorlight-i5_2026-10-10.enet (U5 symbol = i9+ balls,
# checked against wuxx colorlight_i9plus_v6.1.md: 0 mismatches).
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
# Unused pins float: most RP2040 GPIOs land on FPGA balls, never pull them.
set_property BITSTREAM.CONFIG.UNUSEDPIN PULLNONE [current_design]

set_property -dict {PACKAGE_PIN K4   IOSTANDARD LVCMOS33} [get_ports clk25] ;# 25 MHz on the module
create_clock -period 40.000 -name clk25 [get_ports clk25]
set_property -dict {PACKAGE_PIN A18  IOSTANDARD LVCMOS33} [get_ports led_mod] ;# module D2

set_property -dict {PACKAGE_PIN M1   IOSTANDARD LVCMOS33} [get_ports {led[0]}] ;# LED0
set_property -dict {PACKAGE_PIN L1   IOSTANDARD LVCMOS33} [get_ports {led[1]}] ;# LED1
set_property -dict {PACKAGE_PIN K1   IOSTANDARD LVCMOS33} [get_ports {led[2]}] ;# LED2
set_property -dict {PACKAGE_PIN J1   IOSTANDARD LVCMOS33} [get_ports {led[3]}] ;# LED3
set_property -dict {PACKAGE_PIN H3   IOSTANDARD LVCMOS33} [get_ports {led[4]}] ;# LED4
set_property -dict {PACKAGE_PIN U1   IOSTANDARD LVCMOS33} [get_ports {led[5]}] ;# LED5
set_property -dict {PACKAGE_PIN V5   IOSTANDARD LVCMOS33} [get_ports {led[6]}] ;# LED6
set_property -dict {PACKAGE_PIN U5   IOSTANDARD LVCMOS33} [get_ports {led[7]}] ;# LED7
set_property -dict {PACKAGE_PIN P5   IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[0]}] ;# SW_RESET
set_property -dict {PACKAGE_PIN T6   IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[1]}] ;# SW_OPTION
set_property -dict {PACKAGE_PIN U7   IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[2]}] ;# SW_SELECT
set_property -dict {PACKAGE_PIN U6   IOSTANDARD LVCMOS33 PULLTYPE PULLUP} [get_ports {sw_n[3]}] ;# SW_START
set_property -dict {PACKAGE_PIN T3   IOSTANDARD LVCMOS33} [get_ports {eth_led[0]}] ;# ETH1_LED-L+
set_property -dict {PACKAGE_PIN T4   IOSTANDARD LVCMOS33} [get_ports {eth_led[1]}] ;# ETH1_LED-R+

set_property -dict {PACKAGE_PIN Y21  IOSTANDARD LVCMOS33} [get_ports {uart_rx}] ;# GPIO4
set_property -dict {PACKAGE_PIN AB21 IOSTANDARD LVCMOS33} [get_ports {uart_tx}] ;# GPIO5
set_property -dict {PACKAGE_PIN P14  IOSTANDARD LVCMOS33} [get_ports {uart1_rx}] ;# GPIO16
set_property -dict {PACKAGE_PIN R14  IOSTANDARD LVCMOS33} [get_ports {uart1_tx}] ;# GPIO17

set_property -dict {PACKAGE_PIN W5   IOSTANDARD LVCMOS33} [get_ports {sram_a[0]}] ;# SRAM_A0
set_property -dict {PACKAGE_PIN L6   IOSTANDARD LVCMOS33} [get_ports {sram_a[1]}] ;# SRAM_A1
set_property -dict {PACKAGE_PIN V7   IOSTANDARD LVCMOS33} [get_ports {sram_a[2]}] ;# SRAM_A2
set_property -dict {PACKAGE_PIN N14  IOSTANDARD LVCMOS33} [get_ports {sram_a[3]}] ;# SRAM_A3
set_property -dict {PACKAGE_PIN N15  IOSTANDARD LVCMOS33} [get_ports {sram_a[4]}] ;# SRAM_A4
set_property -dict {PACKAGE_PIN M15  IOSTANDARD LVCMOS33} [get_ports {sram_a[5]}] ;# SRAM_A5
set_property -dict {PACKAGE_PIN L16  IOSTANDARD LVCMOS33} [get_ports {sram_a[6]}] ;# SRAM_A6
set_property -dict {PACKAGE_PIN N22  IOSTANDARD LVCMOS33} [get_ports {sram_a[7]}] ;# SRAM_A7
set_property -dict {PACKAGE_PIN K14  IOSTANDARD LVCMOS33} [get_ports {sram_a[8]}] ;# SRAM_A8
set_property -dict {PACKAGE_PIN L15  IOSTANDARD LVCMOS33} [get_ports {sram_a[9]}] ;# SRAM_A9
set_property -dict {PACKAGE_PIN M16  IOSTANDARD LVCMOS33} [get_ports {sram_a[10]}] ;# SRAM_A10
set_property -dict {PACKAGE_PIN N19  IOSTANDARD LVCMOS33} [get_ports {sram_a[11]}] ;# SRAM_A11
set_property -dict {PACKAGE_PIN N18  IOSTANDARD LVCMOS33} [get_ports {sram_a[12]}] ;# SRAM_A12
set_property -dict {PACKAGE_PIN P15  IOSTANDARD LVCMOS33} [get_ports {sram_a[13]}] ;# SRAM_A13
set_property -dict {PACKAGE_PIN N13  IOSTANDARD LVCMOS33} [get_ports {sram_a[14]}] ;# SRAM_A14
set_property -dict {PACKAGE_PIN W6   IOSTANDARD LVCMOS33} [get_ports {sram_a[15]}] ;# SRAM_A15
set_property -dict {PACKAGE_PIN L5   IOSTANDARD LVCMOS33} [get_ports {sram_a[16]}] ;# SRAM_A16
set_property -dict {PACKAGE_PIN N17  IOSTANDARD LVCMOS33} [get_ports {sram_d[0]}] ;# SRAM_D0
set_property -dict {PACKAGE_PIN P17  IOSTANDARD LVCMOS33} [get_ports {sram_d[1]}] ;# SRAM_D1
set_property -dict {PACKAGE_PIN R17  IOSTANDARD LVCMOS33} [get_ports {sram_d[2]}] ;# SRAM_D2
set_property -dict {PACKAGE_PIN R18  IOSTANDARD LVCMOS33} [get_ports {sram_d[3]}] ;# SRAM_D3
set_property -dict {PACKAGE_PIN U18  IOSTANDARD LVCMOS33} [get_ports {sram_d[4]}] ;# SRAM_D4
set_property -dict {PACKAGE_PIN T18  IOSTANDARD LVCMOS33} [get_ports {sram_d[5]}] ;# SRAM_D5
set_property -dict {PACKAGE_PIN U17  IOSTANDARD LVCMOS33} [get_ports {sram_d[6]}] ;# SRAM_D6
set_property -dict {PACKAGE_PIN V17  IOSTANDARD LVCMOS33} [get_ports {sram_d[7]}] ;# SRAM_D7
set_property -dict {PACKAGE_PIN P16  IOSTANDARD LVCMOS33} [get_ports {sram_ce_n}] ;# SRAM_CE#
set_property -dict {PACKAGE_PIN R16  IOSTANDARD LVCMOS33} [get_ports {sram_oe_n}] ;# SRAM_OE#
set_property -dict {PACKAGE_PIN R19  IOSTANDARD LVCMOS33} [get_ports {sram_we_n}] ;# SRAM_WE#

# The jig's I/O is slow and registered; the async SRAM gets >= 40 ns per phase.
set_false_path -to [get_ports -filter {DIRECTION == OUT || DIRECTION == INOUT}]
set_false_path -from [get_ports -filter {DIRECTION == IN || DIRECTION == INOUT}]
