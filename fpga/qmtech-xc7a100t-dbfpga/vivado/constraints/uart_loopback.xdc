# QMTECH XC7A100T + DB_FPGA V5 — UART Loopback (R25 → P25)
# No clock — purely combinational path for path sanity-check.
# RP2040 UART1: GPIO4 (TX) → J2:40 → FPGA R25 (rxd)
#               GPIO5 (RX) ← J2:39 ← FPGA P25 (txd)

# R25 = rxd: RP2040 GPIO4 TX → FPGA RX
set_property PACKAGE_PIN R25 [get_ports {rxd}]
set_property IOSTANDARD LVCMOS33 [get_ports {rxd}]

# P25 = txd: FPGA TX → RP2040 GPIO5 RX
set_property PACKAGE_PIN P25 [get_ports {txd}]
set_property IOSTANDARD LVCMOS33 [get_ports {txd}]

# Suppress timing analysis — no clocks, fully combinational
set_false_path -from [get_ports rxd] -to [get_ports txd]

set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.GENERAL.COMPRESS TRUE [current_design]
