# Vivado project creation script for JOP DDR3 on QMTECH XC7A100T Wukong.
# Run with: vivado -mode batch -source vivado/tcl/create_ddr3_project.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "wukong_jop_ddr3"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a100tfgg676-2

# Add generated RTL
set rtl_dir [file normalize [file join $repo_root ../../spinalhdl/generated]]
set rtl_file [file join $rtl_dir JopDdr3WukongTop.v]
if {[file exists $rtl_file]} {
  add_files -norecurse $rtl_file
}

# Add generated .bin files (BRAM init)
set bin_files [glob -nocomplain [file join $rtl_dir JopDdr3WukongTop*.bin]]
if {[llength $bin_files] > 0} {
  add_files -norecurse $bin_files
}

# Add constraints
set xdc_file [file join $repo_root vivado/constraints/wukong_ddr3.xdc]
if {[file exists $xdc_file]} {
  add_files -fileset constrs_1 -norecurse $xdc_file
}

# Add Clock Wizard IP
set clk_wiz_xci [file join $repo_root vivado/ip/clk_wiz_0/clk_wiz_0.xci]
if {[file exists $clk_wiz_xci]} {
  add_files -norecurse $clk_wiz_xci
  generate_target all [get_files $clk_wiz_xci]
}

# Add MIG IP
set mig_xci [file join $repo_root vivado/ip/mig_7series_0/mig_7series_0.xci]
if {[file exists $mig_xci]} {
  add_files -norecurse $mig_xci
  generate_target all [get_files $mig_xci]
}

# Set top module
if {[llength [get_files -quiet [file join $rtl_dir JopDdr3WukongTop.v]]] > 0} {
  set_property top JopDdr3WukongTop [current_fileset]
}

update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
