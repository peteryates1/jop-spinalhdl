# Vivado project creation script for DDR3 Exerciser on Alchitry AU V2.
# Run with: vivado -mode batch -source vivado/tcl/create_project.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "ddr3_test"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a35tftg256-2

# Add generated RTL (only Ddr3ExerciserTop.v)
set rtl_dir [file normalize [file join $repo_root ../../spinalhdl/generated]]
set rtl_file [file join $rtl_dir Ddr3ExerciserTop.v]
if {[file exists $rtl_file]} {
  add_files -norecurse $rtl_file
}

# Add constraints
set xdc_file [file join $repo_root vivado/constraints/alchitry_au_v2.xdc]
if {[file exists $xdc_file]} {
  add_files -fileset constrs_1 -norecurse $xdc_file
}

# Add Clock Wizard IP (shared with alchitry-au)
set ip_base [file normalize [file join $repo_root ../alchitry-au/vivado/ip]]
set clk_wiz_xci [file join $ip_base clk_wiz_0/clk_wiz_0.xci]
if {[file exists $clk_wiz_xci]} {
  add_files -norecurse $clk_wiz_xci
  generate_target all [get_files $clk_wiz_xci]
}

# Add MIG IP (shared with alchitry-au)
set mig_xci [file join $ip_base mig_7series_0/mig_7series_0.xci]
if {[file exists $mig_xci]} {
  add_files -norecurse $mig_xci
  generate_target all [get_files $mig_xci]
}

# Set top module
if {[llength [get_files -quiet [file join $rtl_dir Ddr3ExerciserTop.v]]] > 0} {
  set_property top Ddr3ExerciserTop [current_fileset]
}

update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
