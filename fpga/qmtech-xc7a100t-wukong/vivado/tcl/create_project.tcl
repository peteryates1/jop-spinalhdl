# Vivado project creation for JOP BRAM on Wukong XC7A100T.
# Run with: vivado -mode batch -source vivado/tcl/create_project.tcl

set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "wukong_jop_bram"

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a100tfgg676-2

# Add generated RTL + .bin sidecar files
set rtl_dir [file normalize [file join $repo_root ../../spinalhdl/generated]]
set rtl_file [file join $rtl_dir JopBramWukongTop.v]
if {[file exists $rtl_file]} {
  add_files -norecurse $rtl_file
} else {
  puts "ERROR: $rtl_file not found. Run 'make generate' first."
  exit 1
}

# Add .bin sidecar files (BRAM init data)
foreach bin_file [glob -nocomplain [file join $rtl_dir JopBramWukongTop*.bin]] {
  add_files -norecurse $bin_file
}

# Add constraints
set xdc_file [file join $repo_root vivado/constraints/wukong_jop_bram.xdc]
if {[file exists $xdc_file]} {
  add_files -fileset constrs_1 -norecurse $xdc_file
}

# Add ClkWiz IP
set clk_xci [file join $repo_root vivado/ip/clk_wiz_0/clk_wiz_0.xci]
if {[file exists $clk_xci]} {
  add_files -norecurse $clk_xci
} else {
  puts "ERROR: $clk_xci not found. Run 'make create-ip' first."
  exit 1
}

set_property top JopBramWukongTop [current_fileset]
update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
