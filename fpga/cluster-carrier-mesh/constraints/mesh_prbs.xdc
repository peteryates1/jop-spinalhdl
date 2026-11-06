## The mesh PRBS test's own clock relationships (jop.system.MeshPrbsTop), read
## AFTER the socket's pin plan, carrier_U<n>.xdc, which creates every clock
## named here.
##
## The pin plan groups clk25 as `-group [get_clocks clk25]`, which does not take
## in the clocks the design's MMCM derives from it -- here the 50 MHz link
## clock, clk_sys_mesh_clk, and in a JOP build its system clock. Without this
## line Vivado TIMES every crossing between that clock and the three RX clocks
## as if they were related. This design's crossings are FlowCCByToggle (each
## RX port's status, once a window) and BufferCC (each RX clock's activity bit).
set_clock_groups -asynchronous \
  -group [get_clocks -include_generated_clocks clk25] \
  -group [get_clocks p1_rx_clk] -group [get_clocks p2_rx_clk] -group [get_clocks p3_rx_clk]

## The UART (115200 baud) and the LED are slow, and asynchronous to anything
## that could time them.
set_false_path -from [get_ports uart_rx]
set_false_path -to [get_ports {uart_tx led_n}]
