# Vivado project creation script for UART Flash Programmer on Alchitry AU V2.
# Run with: vivado -mode batch -source vivado/tcl/create_project_flash_programmer.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "flash_programmer"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a35tftg256-2

# Add generated RTL
set rtl_dir [file normalize [file join $repo_root ../../spinalhdl/generated]]
set rtl_file [file join $rtl_dir FlashProgrammerDdr3Top.v]
if {[file exists $rtl_file]} {
  add_files -norecurse $rtl_file
}

# Add constraints: base (clock, UART, LEDs) + flash pins
set xdc_base [file join $repo_root vivado/constraints/alchitry_au_v2.xdc]
if {[file exists $xdc_base]} {
  add_files -fileset constrs_1 -norecurse $xdc_base
}
set xdc_flash [file join $repo_root vivado/constraints/flash.xdc]
if {[file exists $xdc_flash]} {
  add_files -fileset constrs_1 -norecurse $xdc_flash
}

# Set top module
if {[llength [get_files -quiet [file join $rtl_dir FlashProgrammerDdr3Top.v]]] > 0} {
  set_property top FlashProgrammerDdr3Top [current_fileset]
}

update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
