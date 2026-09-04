package jop.utils

import jop.generate.BuildLayout

/**
 * Where a simulation finds the `.jop` image it runs.
 *
 * WHY THIS EXISTS. Sims used to name the image by a source path --
 * `"java/apps/JvmTests/DoAll.jop"` -- and that is where the Java build wrote
 * its products before the build-tree move. Two things were wrong with it.
 *
 * The path is a SOURCE path, so build products lived in the source tree; a
 * clean clone had no image at all, and a flow that "worked" often only worked
 * because someone had once run the app build by hand.
 *
 * The subtler one: `Const.java` is generated PER CONFIGURATION -- I/O
 * addresses, SUPPORT_FLOAT, the linker's method-size limit -- and was generated
 * into a source tree shared by every configuration. So the literal resolved to
 * "whichever preset was built last". A sim elaborates one piece of hardware and
 * loaded an image linked for another, and the two agreed only because both
 * happened to land on the same defaults. Nothing enforced it, and nothing would
 * have reported it: a preset that trims `jpcWidth` to save LUTs emits a smaller
 * METHOD_MAX_SIZE, and a sim running that image against a different method
 * cache would simply behave differently.
 *
 * Naming the preset makes the hardware and the image come from ONE name.
 */
object SimApp {

  /**
   * The preset the BRAM sims' images are linked against.
   *
   * `ep4cgx150Serial` is not an arbitrary pick: it overrides only `memConfig`,
   * so every geometry field -- `jpcWidth`, `blockBits`, the stack widths --
   * is the `JopCoreConfig()` default, which is exactly what `JopCoreTestHarness`
   * elaborates. It is also what `JOP_PRESET ?=` already defaulted to, so this
   * records the existing behaviour rather than changing it.
   *
   * A sim that elaborates something else must pass its own preset.
   */
  val defaultPreset: String = "ep4cgx150Serial"

  /** `.jop` image for an app, in the build tree of the given configuration. */
  def jop(app: String, image: String, preset: String = defaultPreset): String =
    s"${BuildLayout.default.javaDir(preset, Seq.empty)}/apps/$app/$image.jop"
}
