# ---------------------------------------------------------------------------
# Create a Vivado project, once. Sibling of vivado_build_project.tcl.
#
# Driven by environment, the way JOP_CFG_DIR already reaches the non-project
# scripts -- not by -tclargs, so a caller can set only what it needs and the
# rest keeps its default.
#
#   JOP_PROJ       project name                              (required)
#   JOP_PART       e.g. xc7a100tfgg676-2                     (required)
#   JOP_TOP        top-level entity                          (required)
#   JOP_BUILD_DIR  directory the project is created under    (required)
#   JOP_RTL        space-separated RTL files                 (required)
#   JOP_XDC        space-separated constraint files          (optional)
#   JOP_IP         space-separated .xci files                (optional)
#   JOP_BIN_GLOB   glob for BRAM-init .bin sidecars          (optional)
#   JOP_GEN_HINT   what to run if the RTL is missing         (optional)
#   JOP_IP_GEN_TARGET  yes => generate_target all on each .xci (optional)
#
# WHY THIS EXISTS. Nine create_project scripts across four boards differed only
# in these fields -- and had drifted apart in the parts that were NOT supposed
# to vary. Some checked that the RTL existed and exited with a useful message,
# some added it blind and let Vivado fail later with a synthesis error naming a
# missing module. Same class of divergence as the nine .cdf generators on the
# Quartus side: copies that start identical and rot at different rates.
# ---------------------------------------------------------------------------

proc jop_env {name {default ""}} {
  if {[info exists ::env($name)] && $::env($name) ne ""} { return $::env($name) }
  return $default
}

proc jop_require {name} {
  set v [jop_env $name]
  if {$v eq ""} {
    puts "ERROR: $name must be set (vivado_create_project.tcl)"
    exit 1
  }
  return $v
}

set proj_name  [jop_require JOP_PROJ]
set part       [jop_require JOP_PART]
set top        [jop_require JOP_TOP]
set build_dir  [file normalize [jop_require JOP_BUILD_DIR]]
set rtl_files  [jop_require JOP_RTL]
set xdc_files  [jop_env JOP_XDC]
set ip_files   [jop_env JOP_IP]
set bin_glob   [jop_env JOP_BIN_GLOB]
set gen_hint   [jop_env JOP_GEN_HINT "run 'make generate' first"]

file mkdir $build_dir
create_project -force $proj_name [file join $build_dir $proj_name] -part $part

# RTL is REQUIRED to exist. Adding a missing file and letting synthesis fail
# later reports it as an undefined module, which reads like an RTL bug rather
# than a build-order mistake.
foreach f $rtl_files {
  set f [file normalize $f]
  if {![file exists $f]} {
    puts "ERROR: RTL not found: $f"
    puts "       $gen_hint"
    exit 1
  }
  add_files -norecurse $f
}

# BRAM init sidecars emitted next to the RTL by SpinalHDL ($readmemb targets).
if {$bin_glob ne ""} {
  foreach f [glob -nocomplain $bin_glob] { add_files -norecurse $f }
}

# IP is required when named: a missing .xci means the create-ip step has not
# been run, and the resulting error is otherwise just as indirect as the RTL one.
foreach f $ip_files {
  set f [file normalize $f]
  if {![file exists $f]} {
    puts "ERROR: IP not found: $f"
    puts "       run the board's create-ip target first"
    exit 1
  }
  add_files -norecurse $f
  # Pre-generate the IP output products. Project mode will do this during
  # launch_runs anyway, so most callers do not ask -- but one did, and whether
  # a build works without it is not something to settle by assumption.
  if {[jop_env JOP_IP_GEN_TARGET] eq "yes"} {
    generate_target all [get_files $f]
  }
}

foreach f $xdc_files {
  set f [file normalize $f]
  if {[file exists $f]} {
    add_files -fileset constrs_1 -norecurse $f
  } else {
    puts "WARNING: constraints not found, skipped: $f"
  }
}

set_property top $top [current_fileset]
update_compile_order -fileset sources_1
close_project
puts "INFO: Created project [file join $build_dir $proj_name $proj_name.xpr]"
