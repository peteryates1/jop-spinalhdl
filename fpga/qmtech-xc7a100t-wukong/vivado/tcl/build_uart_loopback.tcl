set repo_root [file normalize [file join [file dirname [info script]] ../..]]
set build_dir [file join $repo_root vivado/build/wukong_uart_loopback]
file mkdir $build_dir
read_verilog [file join $repo_root rtl/WukongUartLoopback.v]
read_xdc     [file join $repo_root vivado/constraints/wukong_uart_loopback.xdc]
synth_design -top WukongUartLoopback -part xc7a100tfgg676-2
opt_design
place_design
route_design
write_bitstream -force [file join $build_dir WukongUartLoopback.bit]
puts "LOOPBACK BITSTREAM DONE"
