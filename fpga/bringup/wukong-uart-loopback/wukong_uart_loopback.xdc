# UART loopback jig — see rtl/WukongUartLoopback.v
set_property PACKAGE_PIN M21 [get_ports {clk_in}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk_in}]
create_clock -period 20.000 -name clk_in [get_ports {clk_in}]

# On-board CH340 (control path, known good)
set_property PACKAGE_PIN E3 [get_ports {ser_txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd}]
set_property PACKAGE_PIN F3 [get_ports {ser_rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd}]

# J11.4/.3 -> Pico GP13/GP12 = Pico uart0
set_property PACKAGE_PIN G8 [get_ports {ser_txd_1}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd_1}]
set_property PACKAGE_PIN G7 [get_ports {ser_rxd_1}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd_1}]

# J11.2/.1 -> Pico GP5/GP4 = Pico uart1
set_property PACKAGE_PIN G5 [get_ports {ser_txd_2}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd_2}]
set_property PACKAGE_PIN D5 [get_ports {ser_rxd_2}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd_2}]

set_property PACKAGE_PIN G21 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN G20 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]

# J11.4/.3 (board doc: A5/A4)
set_property PACKAGE_PIN A5 [get_ports {ser_txd_3}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd_3}]
set_property PACKAGE_PIN A4 [get_ports {ser_rxd_3}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd_3}]

# J11.2/.1 (board doc: F4/H4)
set_property PACKAGE_PIN F4 [get_ports {ser_txd_4}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd_4}]
set_property PACKAGE_PIN H4 [get_ports {ser_rxd_4}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd_4}]
