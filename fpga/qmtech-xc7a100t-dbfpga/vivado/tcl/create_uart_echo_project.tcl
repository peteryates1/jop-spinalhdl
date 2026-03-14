# Vivado project creation for XC7A100T + DB_FPGA V5 UART echo test.
# Run with: vivado -mode batch -source vivado/tcl/create_uart_echo_project.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "uart_echo"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a100tfgg676-2

# Add generated RTL
set rtl_dir [file normalize [file join $repo_root ../../spinalhdl/generated]]
set rtl_file [file join $rtl_dir UartEchoTop.v]
if {[file exists $rtl_file]} {
  add_files -norecurse $rtl_file
} else {
  puts "ERROR: $rtl_file not found. Run 'sbt \"runMain jop.system.UartEchoTopVerilog\"' first."
  exit 1
}

# Add constraints
set xdc_file [file join $repo_root vivado/constraints/uart_echo.xdc]
if {[file exists $xdc_file]} {
  add_files -fileset constrs_1 -norecurse $xdc_file
}

set_property top UartEchoTop [current_fileset]
update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
