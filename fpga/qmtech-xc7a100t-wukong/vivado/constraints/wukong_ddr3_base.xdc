# QMTECH XC7A100T Wukong V3 — JOP DDR3 base constraints
# DDR3 pin constraints are handled by the MIG IP XDC files.
# This file covers board-level (non-DDR3) pins only.
# For Ethernet + SD constraints, use wukong_ddr3.xdc instead.

# 50 MHz system clock (Y1 oscillator)
set_property PACKAGE_PIN M21 [get_ports {clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk}]
create_clock -period 20.000 -name sys_clk [get_ports {clk}]

# Reset (directly active-low from SpinalHDL default CD)
set_property PACKAGE_PIN H7 [get_ports {resetn}]
set_property IOSTANDARD LVCMOS33 [get_ports {resetn}]

# UART on the J11 header, wired to a Pico's uart0.
#
# NOT the on-board CH340N at E3/F3. Those are hardwired to the CH340 on the
# PCB and cannot be tapped, and a second 1a86:7523 bridge on the host is
# indistinguishable from the A-E115FB's anyway. J11 is the way in:
#
#   J11.1 = H4  <- pico gpio4  (uart1 TX)  => FPGA ser_rxd
#   J11.2 = F4  -> pico gpio5  (uart1 RX)  => FPGA ser_txd
#   J11.3 = A4  <- pico gpio12 (uart0 TX)  => FPGA ser_rxd
#   J11.4 = A5  -> pico gpio13 (uart0 RX)  => FPGA ser_txd
#
# Directions are from the FPGA's point of view: ser_txd is an OUTPUT and must
# land on the Pico's RX. Crossing them gives silence at every baud, which is
# indistinguishable from a design that never boots -- exactly what happened
# before the pinout was checked.
#
# uart0 (A4/A5) chosen so the console appears on the Pico's CDC0, which is the
# first ttyACM of the pair. Swap to F4/H4 for uart1.
set_property PACKAGE_PIN A5 [get_ports {ser_txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd}]
set_property PACKAGE_PIN A4 [get_ports {ser_rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd}]

# On-board LEDs (active high)
set_property PACKAGE_PIN G21 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN G20 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

# Configuration
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
