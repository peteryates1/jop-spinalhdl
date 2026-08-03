# JOP-side constraints. The memory interface is constrained by the IP's own
# ddr2_64bit_phy_ddr_timing.sdc, which the phy .qip pulls in.
set_false_path -from [get_ports reset] -to *
set_false_path -from [get_ports ser_rxd] -to *
set_false_path -from * -to [get_ports {led[*]}]
set_false_path -from * -to [get_ports ser_txd]
