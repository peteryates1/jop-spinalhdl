# Config flash SPI pins for JOP flash boot on Alchitry Au V2.
#
# Flash: SST26VF032BT-104I/MF (Microchip, 32Mbit/4MB, SPI/QSPI)
# FPGA:  XC7A35T-2FTG256, Bank 14, LVCMOS33
#
# On Xilinx 7 Series, CCLK (E8) is a dedicated pin driven via STARTUPE2 —
# no XDC entry needed.  DQ0 (MOSI), DQ1 (MISO), and FCS_B (CS) are regular
# user I/O after configuration.
#
# DQ2 (K15) and DQ3 (K16) are unused in SPI x1 mode.

# DQ0 / MOSI (FPGA -> flash)
set_property PACKAGE_PIN J13 [get_ports {cf_mosi}]
set_property IOSTANDARD LVCMOS33 [get_ports {cf_mosi}]

# DQ1 / MISO (flash -> FPGA)
set_property PACKAGE_PIN J14 [get_ports {cf_miso}]
set_property IOSTANDARD LVCMOS33 [get_ports {cf_miso}]

# FCS_B / chip select
set_property PACKAGE_PIN L12 [get_ports {cf_cs}]
set_property IOSTANDARD LVCMOS33 [get_ports {cf_cs}]

# Configuration bank voltage (Bank 14 = 3.3V on Au V2)
# Required for proper post-configuration I/O on config pins (FCS_B, D00, D01)
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]

# SPI flash bitstream settings — only needed for flash-boot bitstreams.
# For JTAG-programmed designs (like the flash programmer), these are not
# strictly required but don't hurt.
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 1 [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 33 [current_design]
