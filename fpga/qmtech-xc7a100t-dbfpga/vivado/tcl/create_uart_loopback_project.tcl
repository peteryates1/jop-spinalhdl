# Vivado project creation for XC7A100T + DB_FPGA V5 UART loopback test.
# Run with: vivado -mode batch -source vivado/tcl/create_uart_loopback_project.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "uart_loopback"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a100tfgg676-2

set rtl_file [file normalize [file join $repo_root vivado/rtl/UartLoopback.v]]
add_files -norecurse $rtl_file

set xdc_file [file normalize [file join $repo_root vivado/constraints/uart_loopback.xdc]]
add_files -fileset constrs_1 -norecurse $xdc_file

set_property top UartLoopback [current_fileset]
update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
