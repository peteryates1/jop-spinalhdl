# Build bitstream for UartTxGen (0xAA at 2 Mbaud on A5 + P25).
set script_dir [file dirname [file normalize [info script]]]
set repo_root  [file normalize [file join $script_dir ../..]]
set proj_xpr   [file join $repo_root vivado/build/uart_txgen/uart_txgen.xpr]

if {![file exists $proj_xpr]} {
  puts "ERROR: Project not found — run create_uart_txgen_project.tcl first"
  exit 1
}
open_project $proj_xpr
reset_run synth_1
launch_runs synth_1 -jobs 8
wait_on_run synth_1
reset_run impl_1
launch_runs impl_1 -to_step write_bitstream -jobs 8
wait_on_run impl_1
set impl_dir [get_property DIRECTORY [get_runs impl_1]]
puts "INFO: Bitstream: ${impl_dir}/UartTxGen.bit"
