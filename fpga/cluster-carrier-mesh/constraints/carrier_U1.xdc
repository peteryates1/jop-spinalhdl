# JOP cluster carrier - socket U1 (node 0)
# GENERATED from mesh_pads.csv - mesh pin plan v2 (P-side RX clocks).
# ASSUMPTION: bank VCCO = 3.3 V (not yet measured). If 2.5 V, change LVCMOS33 -> LVCMOS25.
# Port naming: p<k>_<tx|rx>_<clk|d[3:0]|fr>, k = mesh port (peer node = this node XOR k).

## Module 25 MHz oscillator and on-module LED (from wuxx i9plus v6.1 pin map)
set_property -dict {PACKAGE_PIN K4 IOSTANDARD LVCMOS33} [get_ports clk25]
create_clock -name clk25 -period 40.000 [get_ports clk25]
set_property -dict {PACKAGE_PIN A18 IOSTANDARD LVCMOS33} [get_ports led_n]

## Carrier UART to RP2040 (U5). Direction assumed: FPGA drives *_TX.
set_property -dict {PACKAGE_PIN H19 IOSTANDARD LVCMOS33} [get_ports uart_tx]  ;# SODIMM 197
set_property -dict {PACKAGE_PIN B17 IOSTANDARD LVCMOS33} [get_ports uart_rx]  ;# SODIMM 198

## Mesh port P1 <-> U2
set_property -dict {PACKAGE_PIN T5 IOSTANDARD LVCMOS33} [get_ports {p1_rx_clk}]  ;# SODIMM 92, bank 34, net MX_U2_U1_CLK
set_property -dict {PACKAGE_PIN J5 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[0]}]  ;# SODIMM 94, bank 35, net MX_U2_U1_D0
set_property -dict {PACKAGE_PIN J6 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[1]}]  ;# SODIMM 96, bank 35, net MX_U2_U1_D1
set_property -dict {PACKAGE_PIN W5 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[2]}]  ;# SODIMM 98, bank 34, net MX_U2_U1_D2
set_property -dict {PACKAGE_PIN L5 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[3]}]  ;# SODIMM 100, bank 35, net MX_U2_U1_D3
set_property -dict {PACKAGE_PIN L6 IOSTANDARD LVCMOS33} [get_ports {p1_rx_fr}]  ;# SODIMM 102, bank 35, net MX_U2_U1_FR
set_property -dict {PACKAGE_PIN R4 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_clk}]  ;# SODIMM 90, bank 34, net MX_U1_U2_CLK
set_property -dict {PACKAGE_PIN R3 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[0]}]  ;# SODIMM 84, bank 34, net MX_U1_U2_D0
set_property -dict {PACKAGE_PIN N13 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[1]}]  ;# SODIMM 112, bank 14, net MX_U1_U2_D1
set_property -dict {PACKAGE_PIN M3 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[2]}]  ;# SODIMM 86, bank 35, net MX_U1_U2_D2
set_property -dict {PACKAGE_PIN W6 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[3]}]  ;# SODIMM 104, bank 34, net MX_U1_U2_D3
set_property -dict {PACKAGE_PIN V7 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_fr}]  ;# SODIMM 110, bank 34, net MX_U1_U2_FR
create_clock -name p1_rx_clk -period 20.000 [get_ports p1_rx_clk]   ;# 50 MHz SDR bring-up
# TX launches data on the falling edge of the forwarded clock -> centre-aligned at RX rising edge.
set_input_delay -clock [get_clocks p1_rx_clk] -clock_fall -max  1.500 [get_ports {p1_rx_d[*] p1_rx_fr}]
set_input_delay -clock [get_clocks p1_rx_clk] -clock_fall -min -1.500 [get_ports {p1_rx_d[*] p1_rx_fr}] -add_delay

## Mesh port P2 <-> U3
set_property -dict {PACKAGE_PIN V4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_clk}]  ;# SODIMM 88, bank 34, net MX_U3_U1_CLK
set_property -dict {PACKAGE_PIN G3 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[0]}]  ;# SODIMM 74, bank 35, net MX_U3_U1_D0
set_property -dict {PACKAGE_PIN J4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[1]}]  ;# SODIMM 76, bank 35, net MX_U3_U1_D1
set_property -dict {PACKAGE_PIN G4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[2]}]  ;# SODIMM 78, bank 35, net MX_U3_U1_D2
set_property -dict {PACKAGE_PIN F4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[3]}]  ;# SODIMM 80, bank 35, net MX_U3_U1_D3
set_property -dict {PACKAGE_PIN K2 IOSTANDARD LVCMOS33} [get_ports {p2_rx_fr}]  ;# SODIMM 70, bank 35, net MX_U3_U1_FR
set_property -dict {PACKAGE_PIN H3 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_clk}]  ;# SODIMM 58, bank 35, net MX_U1_U3_CLK
set_property -dict {PACKAGE_PIN J1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[0]}]  ;# SODIMM 60, bank 35, net MX_U1_U3_D0
set_property -dict {PACKAGE_PIN K1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[1]}]  ;# SODIMM 62, bank 35, net MX_U1_U3_D1
set_property -dict {PACKAGE_PIN L1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[2]}]  ;# SODIMM 64, bank 35, net MX_U1_U3_D2
set_property -dict {PACKAGE_PIN M1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[3]}]  ;# SODIMM 66, bank 35, net MX_U1_U3_D3
set_property -dict {PACKAGE_PIN J2 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_fr}]  ;# SODIMM 68, bank 35, net MX_U1_U3_FR
create_clock -name p2_rx_clk -period 20.000 [get_ports p2_rx_clk]   ;# 50 MHz SDR bring-up
# TX launches data on the falling edge of the forwarded clock -> centre-aligned at RX rising edge.
set_input_delay -clock [get_clocks p2_rx_clk] -clock_fall -max  1.500 [get_ports {p2_rx_d[*] p2_rx_fr}]
set_input_delay -clock [get_clocks p2_rx_clk] -clock_fall -min -1.500 [get_ports {p2_rx_d[*] p2_rx_fr}] -add_delay

## Mesh port P3 <-> U4
set_property -dict {PACKAGE_PIN C18 IOSTANDARD LVCMOS33} [get_ports {p3_rx_clk}]  ;# SODIMM 192, bank 16, net MX_U4_U1_CLK
set_property -dict {PACKAGE_PIN H15 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[0]}]  ;# SODIMM 172, bank 15, net MX_U4_U1_D0
set_property -dict {PACKAGE_PIN G18 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[1]}]  ;# SODIMM 174, bank 15, net MX_U4_U1_D1
set_property -dict {PACKAGE_PIN G17 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[2]}]  ;# SODIMM 176, bank 15, net MX_U4_U1_D2
set_property -dict {PACKAGE_PIN G16 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[3]}]  ;# SODIMM 178, bank 15, net MX_U4_U1_D3
set_property -dict {PACKAGE_PIN F16 IOSTANDARD LVCMOS33} [get_ports {p3_rx_fr}]  ;# SODIMM 180, bank 16, net MX_U4_U1_FR
set_property -dict {PACKAGE_PIN J19 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_clk}]  ;# SODIMM 164, bank 15, net MX_U1_U4_CLK
set_property -dict {PACKAGE_PIN J14 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[0]}]  ;# SODIMM 160, bank 15, net MX_U1_U4_D0
set_property -dict {PACKAGE_PIN J15 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[1]}]  ;# SODIMM 162, bank 15, net MX_U1_U4_D1
set_property -dict {PACKAGE_PIN H13 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[2]}]  ;# SODIMM 166, bank 15, net MX_U1_U4_D2
set_property -dict {PACKAGE_PIN H14 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[3]}]  ;# SODIMM 168, bank 15, net MX_U1_U4_D3
set_property -dict {PACKAGE_PIN H17 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_fr}]  ;# SODIMM 170, bank 15, net MX_U1_U4_FR
create_clock -name p3_rx_clk -period 20.000 [get_ports p3_rx_clk]   ;# 50 MHz SDR bring-up
# TX launches data on the falling edge of the forwarded clock -> centre-aligned at RX rising edge.
set_input_delay -clock [get_clocks p3_rx_clk] -clock_fall -max  1.500 [get_ports {p3_rx_d[*] p3_rx_fr}]
set_input_delay -clock [get_clocks p3_rx_clk] -clock_fall -min -1.500 [get_ports {p3_rx_d[*] p3_rx_fr}] -add_delay

## Each mesh RX clock is asynchronous to clk25 and to the other ports (separate oscillators per module).
set_clock_groups -asynchronous -group [get_clocks clk25] -group [get_clocks p1_rx_clk] -group [get_clocks p2_rx_clk] -group [get_clocks p3_rx_clk]
## Bitstream: SPI flash boot
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property CFGBVS VCCO [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
