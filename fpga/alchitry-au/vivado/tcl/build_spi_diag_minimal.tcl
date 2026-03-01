# Quick SPI diagnostic build with minimal constraints (no BITSTREAM.CONFIG)
set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set build_dir  [file normalize [file join $repo_root vivado/build]]
set proj_name  "spi_diag_minimal"
set rtl_dir    [file normalize [file join $repo_root ../../spinalhdl/generated]]

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part xc7a35tftg256-2

add_files -norecurse [file join $rtl_dir SpiDiagnosticTop.v]
add_files -fileset constrs_1 -norecurse [file join $repo_root vivado/constraints/alchitry_au_v2.xdc]
add_files -fileset constrs_1 -norecurse [file join $repo_root vivado/constraints/flash_minimal.xdc]

set_property top SpiDiagnosticTop [current_fileset]
update_compile_order -fileset sources_1

synth_design
opt_design
place_design
route_design
write_bitstream -force [file join $build_dir $proj_name SpiDiagnosticTop.bit]
puts "INFO: Bitstream: [file join $build_dir $proj_name SpiDiagnosticTop.bit]"
