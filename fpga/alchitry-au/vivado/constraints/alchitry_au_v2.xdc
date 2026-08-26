# Board-level constraints for JopDdr3Top ports on Alchitry AU V2.
# DDR3 constraints are provided by the MIG IP XDC files.

set_property PACKAGE_PIN N14 [get_ports {clk}]
set_property IOSTANDARD LVCMOS33 [get_ports {clk}]

set_property PACKAGE_PIN P6 [get_ports {resetn}]
set_property IOSTANDARD LVCMOS33 [get_ports {resetn}]

set_property PACKAGE_PIN K13 [get_ports {led[0]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[0]}]
set_property PACKAGE_PIN K12 [get_ports {led[1]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[1]}]
set_property PACKAGE_PIN L14 [get_ports {led[2]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[2]}]
set_property PACKAGE_PIN L13 [get_ports {led[3]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[3]}]
set_property PACKAGE_PIN M15 [get_ports {led[4]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[4]}]
set_property PACKAGE_PIN M14 [get_ports {led[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[5]}]
set_property PACKAGE_PIN M12 [get_ports {led[6]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[6]}]
set_property PACKAGE_PIN P14 [get_ports {led[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {led[7]}]

# UART on P15/P16, under BOTH namings, unconditionally. One .xdc serves three
# designs that do not agree on port names: the JopConfig presets emit
# ser_rxd/ser_txd, while FlashProgrammerDdr3Top and Ddr3ExerciserTop still use
# usb_rx/usb_tx.
#
# THE FOUR "expects at least one object" CRITICAL WARNINGS THIS PRODUCES ARE
# EXPECTED -- they are whichever naming the current design does not have. Two
# tempting fixes are both wrong, and both were tried on 2026-08-26:
#
#   deleting the unused pair   silently unconstrains the UART on the other two
#                              designs -- a board that programs and cannot talk
#   guarding with
#   `if {[llength [get_ports -quiet $p]]}`
#                              SKIPS THE CONSTRAINTS ENTIRELY. In project mode
#                              this file is evaluated in a pass where get_ports
#                              does not resolve, so the guard is false and both
#                              pairs are dropped: DRC UCIO-1, 2 unconstrained
#                              ports, no bitstream.
#
# Leave it unconditional. Four harmless warnings beat an unconstrained UART.
set_property PACKAGE_PIN P15 [get_ports {usb_rx}]
set_property IOSTANDARD LVCMOS33 [get_ports {usb_rx}]
set_property PACKAGE_PIN P16 [get_ports {usb_tx}]
set_property IOSTANDARD LVCMOS33 [get_ports {usb_tx}]
set_property PACKAGE_PIN P15 [get_ports {ser_rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_rxd}]
set_property PACKAGE_PIN P16 [get_ports {ser_txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {ser_txd}]
