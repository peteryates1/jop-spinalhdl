package jop.utils

import spinal.core.sim._

/**
 * Shared SpinalSim setup for the long-running JOP system simulations.
 *
 * WHY THIS EXISTS. Verilator gives a register with no reset a RANDOM initial
 * value, drawn from the simulation seed. `grep -rE "= *Reg(Next)? *\(" ... |
 * grep -v init(` counts ~405 such registers in this design, so "the seed" is
 * really "the power-up state of 405 flip-flops". An FPGA does not do this --
 * it powers up at zero -- so a seed-dependent failure is usually a simulator
 * artefact rather than a bug in the hardware.
 *
 * This bit CI for a week (item 30). Two runs of commit caa8abbb, with all five
 * input fingerprints byte-identical and the same runner and Verilator build,
 * disagreed purely on seed:
 *
 *   seed  564015666  ->  132 tests ok
 *   seed -748081925  ->  hangs in clazzinit(), zero results
 *
 * The seed was the only variable in the whole pipeline, so it was necessarily
 * the cause. `--x-initial 0` starts every register at zero, matching the FPGA,
 * and removes the entire class.
 *
 * WHY EARLIER SEED REPLAYS "EXONERATED" THE SEED. A seed only names an initial
 * state relative to a fixed netlist AND a fixed Verilator build. CI runs
 * Verilator 5.020 (ubuntu-24.04 apt); a Debian workstation runs 5.032. Feeding
 * CI's failing seed to a local run therefore reproduces nothing, and a local
 * seed sweep that comes back clean proves nothing about CI. Pin the version
 * before trusting a replay -- see the note in .github/workflows/ci.yml.
 *
 * ESCAPE HATCH. `JOP_SIM_XINIT=random` restores Verilator's randomisation, for
 * when you WANT to hunt missing resets. Randomised state that can stop the
 * machine booting is worth fixing on its own merits; zeroing it here is what
 * makes CI a regression detector rather than a random number generator.
 */
object JopSimDefaults {

  /** True unless JOP_SIM_XINIT=random asks for Verilator's randomisation back. */
  def randomiseXState: Boolean =
    sys.env.get("JOP_SIM_XINIT").map(_.trim.toLowerCase).contains("random")

  /**
   * Apply the X-state defence to any config. Used by the system sims below and
   * by `TestVectorUtils.simWave`, which every unit test builds on -- item 29
   * was a unit test failing on seed 360571106 for exactly this reason.
   */
  def xInitial(config: SpinalSimConfig): SpinalSimConfig =
    if (randomiseXState) config else config.addSimulatorFlag("--x-initial 0")

  /** `SimConfig` with the X-state defence applied unless explicitly disabled. */
  def config: SpinalSimConfig = {
    if (randomiseXState)
      println("Sim X-state: RANDOM (JOP_SIM_XINIT=random) — expect seed-dependent failures")
    else
      println("Sim X-state: zeroed (--x-initial 0; set JOP_SIM_XINIT=random to randomise)")
    xInitial(SimConfig)
  }

  /**
   * Seed for `doSim`, overridable so a CI failure can be replayed exactly.
   * Unset means random, i.e. unchanged behaviour.
   *
   *   JOP_SIM_SEED=-748081925 sbt "Test/runMain jop.system.JopJvmTestsBramSim"
   *
   * Note the sign: seeds are signed 32-bit and negative ones occur often. A
   * `[0-9]+` pattern that drops the minus hands you a different seed.
   */
  def seed(): Int = {
    val s = sys.env.get("JOP_SIM_SEED").map(_.trim).filter(_.nonEmpty).map(_.toInt)
      .getOrElse(scala.util.Random.nextInt())
    println(s"Simulation seed: $s  (set JOP_SIM_SEED to replay)")
    s
  }
}
