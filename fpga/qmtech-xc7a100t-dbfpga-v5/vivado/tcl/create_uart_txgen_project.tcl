# Vivado project: UART TX generator outputting 0xAA at 2 Mbaud on P25 (and A5).
set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "uart_txgen"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a100tfgg676-2

add_files -norecurse [file normalize [file join $repo_root vivado/rtl/UartTxGen.v]]
add_files -fileset constrs_1 -norecurse [file normalize [file join $repo_root vivado/constraints/uart_txgen.xdc]]

set_property top UartTxGen [current_fileset]
update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
