package jop.config

import org.scalatest.funsuite.AnyFunSuite

/**
 * `frem` has no hardware form, and the library it needs must survive a
 * wildcard that gives everything else to hardware.
 *
 * THE DEFECT. `frem` (0x72) was absent from `BytecodeConfig.all`, so
 * `resolve` never saw it. It is implemented only in Java --
 * `JVM.f_frem` -> `SoftFloat32.float_rem`, and `JopInstr.java` marks it
 * IMP_JAVA -- but that library is compiled in only when `Const.SUPPORT_FLOAT`
 * is set, and `ConstGenerator` derives that from
 * `needsJavaFloat` = "any REGISTERED float bytecode still resolves to Java".
 *
 * So on the four presets carrying `bytecodes = Map("*" -> "hw")`, every
 * registered float bytecode became Hardware, `needsJavaFloat` went false,
 * SUPPORT_FLOAT went false, and `f_frem` fell through to `JVMHelp.noim()`.
 * `wukongFull` failed DoAll at FloatTest, and README recorded the cause
 * backwards as "frem is forced to hardware" -- nothing forces it anywhere,
 * because a wildcard cannot reach a bytecode that is not in the registry.
 *
 * THE SEMANTICS BEING PINNED. `"*"` means every CONFIGURABLE bytecode. A
 * bytecode with exactly one possible implementation is not configurable, so
 * neither `"*"` nor a group key may silently retarget it -- while an EXPLICIT
 * `frem -> hw` must still be refused, loudly, rather than ignored.
 */
class SoftFloatLibraryTest extends AnyFunSuite {

  test("frem is in the registry and is Java-only") {
    val frem = BytecodeConfig.all.find(_.name == "frem")
    assert(frem.isDefined, "frem (0x72) is missing from BytecodeConfig.all")
    assert(frem.get.group == "float", "frem must be in the float group")
    assert(frem.get.constraint == ImpConstraint.JavaOnly,
      "frem has no microcode and no hardware handler — it is Java-only")
  }

  test("a wildcard to hardware leaves frem in Java") {
    val resolved = BytecodeConfig.resolve(Map("*" -> "hw"))
    assert(resolved("frem") == Implementation.Java,
      "\"*\" -> \"hw\" must not retarget a bytecode that has no hardware form")
  }

  test("a float-group key to hardware also leaves frem in Java") {
    val resolved = BytecodeConfig.resolve(Map("float" -> "hw"))
    assert(resolved("frem") == Implementation.Java,
      "a group key must not retarget a bytecode that has no hardware form")
  }

  test("an explicit frem -> hw is refused, not ignored") {
    val resolved = BytecodeConfig.resolve(Map("frem" -> "hw"))
    val e = intercept[IllegalArgumentException](BytecodeConfig.validate(resolved))
    assert(e.getMessage.contains("frem"),
      s"the refusal must name frem; got: ${e.getMessage}")
  }

  /** The property that actually decides whether the library is linked. */
  test("SUPPORT_FLOAT survives every preset that gives everything to hardware") {
    val wildcardPresets = Seq(
      "wukongFull"        -> JopConfig.wukongFull,
      "xc7a100tDbFull"    -> JopConfig.xc7a100tDbFull,
    )
    for ((name, cfg) <- wildcardPresets) {
      val cores = cfg.systems.map(_.coreConfig)
      assert(cores.exists(_.needsJavaFloat),
        s"$name: SUPPORT_FLOAT would be false, so SoftFloat32 is dropped and " +
        "f_frem falls through to JVMHelp.noim() — DoAll fails at FloatTest")
    }
  }
}
