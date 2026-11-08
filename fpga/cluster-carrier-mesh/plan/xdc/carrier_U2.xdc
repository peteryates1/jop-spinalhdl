# JOP cluster carrier - socket U2 (node 1)
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

## Mesh port P1 <-> U1
set_property -dict {PACKAGE_PIN Y4 IOSTANDARD LVCMOS33} [get_ports {p1_rx_clk}]  ;# SODIMM 89, bank 34, net MX_U1_U2_CLK
set_property -dict {PACKAGE_PIN Y3 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[0]}]  ;# SODIMM 83, bank 34, net MX_U1_U2_D0
set_property -dict {PACKAGE_PIN AB8 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[1]}]  ;# SODIMM 111, bank 34, net MX_U1_U2_D1
set_property -dict {PACKAGE_PIN W4 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[2]}]  ;# SODIMM 85, bank 34, net MX_U1_U2_D2
set_property -dict {PACKAGE_PIN AB7 IOSTANDARD LVCMOS33} [get_ports {p1_rx_d[3]}]  ;# SODIMM 103, bank 34, net MX_U1_U2_D3
set_property -dict {PACKAGE_PIN AA8 IOSTANDARD LVCMOS33} [get_ports {p1_rx_fr}]  ;# SODIMM 109, bank 34, net MX_U1_U2_FR
set_property -dict {PACKAGE_PIN AB5 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_clk}]  ;# SODIMM 91, bank 34, net MX_U2_U1_CLK
set_property -dict {PACKAGE_PIN AA5 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[0]}]  ;# SODIMM 93, bank 34, net MX_U2_U1_D0
set_property -dict {PACKAGE_PIN Y6 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[1]}]  ;# SODIMM 95, bank 34, net MX_U2_U1_D1
set_property -dict {PACKAGE_PIN AB6 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[2]}]  ;# SODIMM 97, bank 34, net MX_U2_U1_D2
set_property -dict {PACKAGE_PIN AA6 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_d[3]}]  ;# SODIMM 99, bank 34, net MX_U2_U1_D3
set_property -dict {PACKAGE_PIN Y7 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p1_tx_fr}]  ;# SODIMM 101, bank 34, net MX_U2_U1_FR
create_clock -name p1_rx_clk -period 20.000 [get_ports p1_rx_clk]   ;# 50 MHz SDR bring-up
# TX launches data on the falling edge of the forwarded clock -> centre-aligned at RX rising edge.
set_input_delay -clock [get_clocks p1_rx_clk] -clock_fall -max  1.500 [get_ports {p1_rx_d[*] p1_rx_fr}]
set_input_delay -clock [get_clocks p1_rx_clk] -clock_fall -min -1.500 [get_ports {p1_rx_d[*] p1_rx_fr}] -add_delay

## Mesh port P2 <-> U4
set_property -dict {PACKAGE_PIN V4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_clk}]  ;# SODIMM 88, bank 34, net MX_U4_U2_CLK
set_property -dict {PACKAGE_PIN G3 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[0]}]  ;# SODIMM 74, bank 35, net MX_U4_U2_D0
set_property -dict {PACKAGE_PIN J4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[1]}]  ;# SODIMM 76, bank 35, net MX_U4_U2_D1
set_property -dict {PACKAGE_PIN G4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[2]}]  ;# SODIMM 78, bank 35, net MX_U4_U2_D2
set_property -dict {PACKAGE_PIN F4 IOSTANDARD LVCMOS33} [get_ports {p2_rx_d[3]}]  ;# SODIMM 80, bank 35, net MX_U4_U2_D3
set_property -dict {PACKAGE_PIN K2 IOSTANDARD LVCMOS33} [get_ports {p2_rx_fr}]  ;# SODIMM 70, bank 35, net MX_U4_U2_FR
set_property -dict {PACKAGE_PIN H3 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_clk}]  ;# SODIMM 58, bank 35, net MX_U2_U4_CLK
set_property -dict {PACKAGE_PIN J1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[0]}]  ;# SODIMM 60, bank 35, net MX_U2_U4_D0
set_property -dict {PACKAGE_PIN K1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[1]}]  ;# SODIMM 62, bank 35, net MX_U2_U4_D1
set_property -dict {PACKAGE_PIN L1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[2]}]  ;# SODIMM 64, bank 35, net MX_U2_U4_D2
set_property -dict {PACKAGE_PIN M1 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_d[3]}]  ;# SODIMM 66, bank 35, net MX_U2_U4_D3
set_property -dict {PACKAGE_PIN J2 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p2_tx_fr}]  ;# SODIMM 68, bank 35, net MX_U2_U4_FR
create_clock -name p2_rx_clk -period 20.000 [get_ports p2_rx_clk]   ;# 50 MHz SDR bring-up
# TX launches data on the falling edge of the forwarded clock -> centre-aligned at RX rising edge.
set_input_delay -clock [get_clocks p2_rx_clk] -clock_fall -max  1.500 [get_ports {p2_rx_d[*] p2_rx_fr}]
set_input_delay -clock [get_clocks p2_rx_clk] -clock_fall -min -1.500 [get_ports {p2_rx_d[*] p2_rx_fr}] -add_delay

## Mesh port P3 <-> U3
set_property -dict {PACKAGE_PIN V18 IOSTANDARD LVCMOS33} [get_ports {p3_rx_clk}]  ;# SODIMM 143, bank 14, net MX_U3_U2_CLK
set_property -dict {PACKAGE_PIN W19 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[0]}]  ;# SODIMM 135, bank 14, net MX_U3_U2_D0
set_property -dict {PACKAGE_PIN AB18 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[1]}]  ;# SODIMM 137, bank 14, net MX_U3_U2_D1
set_property -dict {PACKAGE_PIN Y19 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[2]}]  ;# SODIMM 139, bank 14, net MX_U3_U2_D2
set_property -dict {PACKAGE_PIN AA19 IOSTANDARD LVCMOS33} [get_ports {p3_rx_d[3]}]  ;# SODIMM 141, bank 14, net MX_U3_U2_D3
set_property -dict {PACKAGE_PIN V19 IOSTANDARD LVCMOS33} [get_ports {p3_rx_fr}]  ;# SODIMM 145, bank 14, net MX_U3_U2_FR
set_property -dict {PACKAGE_PIN Y18 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_clk}]  ;# SODIMM 131, bank 14, net MX_U2_U3_CLK
set_property -dict {PACKAGE_PIN V9 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[0]}]  ;# SODIMM 123, bank 34, net MX_U2_U3_D0
set_property -dict {PACKAGE_PIN R14 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[1]}]  ;# SODIMM 125, bank 14, net MX_U2_U3_D1
set_property -dict {PACKAGE_PIN P14 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[2]}]  ;# SODIMM 127, bank 14, net MX_U2_U3_D2
set_property -dict {PACKAGE_PIN W17 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_d[3]}]  ;# SODIMM 129, bank 14, net MX_U2_U3_D3
set_property -dict {PACKAGE_PIN AA18 IOSTANDARD LVCMOS33 DRIVE 8 SLEW SLOW} [get_ports {p3_tx_fr}]  ;# SODIMM 133, bank 14, net MX_U2_U3_FR
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
