# Self-contained: everything this jig needs sits beside it. Deliberately does
# NOT reach into the board directory or the generated build tree -- see
# ../README.md.
set here      [file normalize [file dirname [info script]]]
set build_dir [file join $here build]
file mkdir $build_dir
read_verilog [file join $here WukongUartLoopback.v]
read_xdc     [file join $here wukong_uart_loopback.xdc]
synth_design -top WukongUartLoopback -part xc7a100tfgg676-2
opt_design
place_design
route_design
write_bitstream -force [file join $build_dir WukongUartLoopback.bit]
puts "LOOPBACK BITSTREAM DONE"
