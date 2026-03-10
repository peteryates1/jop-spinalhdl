# Re-benchmark FCU and DCU after multiply pipelining
set script_dir  [file dirname [file normalize [info script]]]
set repo_root   [file normalize [file join $script_dir ../../../..]]
set gen_dir     [file join $repo_root spinalhdl/generated]
set build_dir   [file normalize [file join $script_dir ../../vivado/build/cu_bench]]
set part        "xc7a100tfgg676-2"
set period_ns   10.0

set units {FloatCuBench DoubleCuBench}

foreach unit $units {
    puts "========================================================================"
    puts "INFO: Benchmarking $unit at [expr {1000.0 / $period_ns}] MHz"
    puts "========================================================================"

    set vfile [file join $gen_dir ${unit}.v]
    set unit_dir [file join $build_dir $unit]
    file mkdir $unit_dir

    read_verilog $vfile
    synth_design -top $unit -part $part -flatten_hierarchy rebuilt
    create_clock -period $period_ns -name clk [get_ports clk]
    write_checkpoint -force [file join $unit_dir post_synth.dcp]

    report_timing_summary -file [file join $unit_dir timing_synth.rpt]
    set synth_wns [get_property SLACK [get_timing_paths -max_paths 1 -setup]]
    puts "INFO: $unit post-synth WNS = ${synth_wns} ns"

    report_utilization -file [file join $unit_dir utilization.rpt]

    opt_design
    place_design
    route_design
    write_checkpoint -force [file join $unit_dir post_route.dcp]

    report_timing_summary -file [file join $unit_dir timing_route.rpt]
    report_timing -nworst 5 -file [file join $unit_dir timing_paths.rpt]
    set route_wns [get_property SLACK [get_timing_paths -max_paths 1 -setup]]
    puts "INFO: $unit post-route WNS = ${route_wns} ns"

    report_utilization -file [file join $unit_dir utilization_route.rpt]
    puts "INFO: $unit SUMMARY: synth_wns=${synth_wns} ns, route_wns=${route_wns} ns"
    puts ""

    close_design
}

puts "========================================================================"
puts "INFO: Pipelined benchmarks complete."
puts "========================================================================"
