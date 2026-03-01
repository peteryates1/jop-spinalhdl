# Config flash SPI pins — minimal constraints (no BITSTREAM.CONFIG)
set_property PACKAGE_PIN J13 [get_ports {cf_mosi}]
set_property IOSTANDARD LVCMOS33 [get_ports {cf_mosi}]
set_property PACKAGE_PIN J14 [get_ports {cf_miso}]
set_property IOSTANDARD LVCMOS33 [get_ports {cf_miso}]
set_property PACKAGE_PIN L12 [get_ports {cf_cs}]
set_property IOSTANDARD LVCMOS33 [get_ports {cf_cs}]
set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
