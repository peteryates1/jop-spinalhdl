# QMTECH XC7A100T Core Board + DB_FPGA V5 — JOP DDR3 base constraints
# Board-level (non-DDR3) pins only. DDR3 pin constraints are in the MIG IP XDC.
# For Ethernet + SD constraints, include xc7a100t_dbv5_ddr3.xdc.

# 50 MHz system clock (U22 oscillator)
set_property PACKAGE_PIN U22 [get_ports {clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk}]
create_clock -period 20.000 -name sys_clk [get_ports {clk}]

# Reset (active-low user button SW2 on core board)
set_property PACKAGE_PIN P4 [get_ports {resetn}]
set_property IOSTANDARD LVCMOS33 [get_ports {resetn}]

# UART via RP2040 ttyACM0 (DB_FPGA V5 J3 pins 7/8 -> core board U4 -> B5/A5)
# RP2040 GPIO0 (TX) -> J3 pin 7 -> FPGA B5 (FPGA rxd)
# RP2040 GPIO1 (RX) <- J3 pin 8 <- FPGA A5 (FPGA txd)
set_property PACKAGE_PIN A5 [get_ports {ser_txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd}]
set_property PACKAGE_PIN B5 [get_ports {ser_rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd}]

# On-board LEDs (active low: D5=T23, D6=R23)
set_property PACKAGE_PIN T23 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN R23 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

# Configuration
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
