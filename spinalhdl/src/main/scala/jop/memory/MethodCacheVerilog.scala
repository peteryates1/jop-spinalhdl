package jop.memory

import spinal.core._

/**
 * Emit MethodCache alone, for out-of-context area measurement.
 *
 * The method cache's LUT cost scales with BLOCK COUNT, and it is per core, so
 * at four cores it multiplies (item 53). Measuring it inside a full build means
 * reading a hierarchical report off a post-route checkpoint after a 30-minute
 * run; measuring it out of context takes seconds and isolates the geometry from
 * everything else that moves between builds.
 *
 * OOC numbers are not the numbers a real build reports -- no surrounding logic
 * to pack against, no IP, and the boundary is unconstrained. Use them to compare
 * geometries and variants of THIS component against each other, which is what
 * they are good for, and not as a prediction of a build total.
 *
 * Usage: sbt "runMain jop.memory.MethodCacheVerilog <jpcWidth> <blockBits>"
 */
object MethodCacheVerilog extends App {
  val jpcWidth  = if (args.length > 0) args(0).toInt else 13
  val blockBits = if (args.length > 1) args(1).toInt else 6

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "spinalhdl/generated/ooc",
    defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = LOW)
  ).generate(MethodCache(jpcWidth = jpcWidth, blockBits = blockBits))
   .printPruned()

  println(s"MethodCacheVerilog: jpcWidth=$jpcWidth blockBits=$blockBits " +
          s"blocks=${1 << blockBits} blockWords=${1 << (jpcWidth - 2 - blockBits)}")
}
