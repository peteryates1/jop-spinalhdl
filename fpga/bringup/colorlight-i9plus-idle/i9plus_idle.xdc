# Colorlight i9+ module-only pins; see I9PlusIdle.v.
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.CONFIG.UNUSEDPIN PULLNONE [current_design]
# Boot speed from the module flash (MX25L12833F): compressed at 33 MHz x1 loads
# in well under a second; the defaults (2.2 MB at 3 MHz) take ~6 s. x4 would
# need the flash's QE bit set.
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 33 [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 1 [current_design]

set_property -dict {PACKAGE_PIN K4  IOSTANDARD LVCMOS33} [get_ports clk25]
create_clock -period 40.000 -name clk25 [get_ports clk25]
set_property -dict {PACKAGE_PIN A18 IOSTANDARD LVCMOS33} [get_ports led_mod]
