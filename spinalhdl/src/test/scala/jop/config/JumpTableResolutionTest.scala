package jop.config

import org.scalatest.funsuite.AnyFunSuite
import jop.pipeline.JumpTableInitData

/**
 * Every preset must resolve its jump table.
 *
 * `JumpTableInitData.useAlt` throws when a bytecode is set to `Microcode` but
 * the ROM has no `_sw` handler for it, instead of silently leaving the entry
 * pointing at the `_hw` (compute unit) default. That strictness is only useful
 * if a bad configuration is caught by the build rather than by someone running
 * the JVM suite on hardware — hence this test.
 *
 * It also pins the coverage figure the strictness rests on: 12 of the 32
 * configurable bytecodes have a `_sw` alternate. If a `_sw` handler is added to
 * jvm.asm, that number changes and the expectation below should be updated
 * deliberately, not silently.
 */
class JumpTableResolutionTest extends AnyFunSuite {

  /** Presets that build real hardware or simulations, by name for error output. */
  private def presets: Seq[(String, JopConfig)] = Seq(
    "ep4cgx150Serial"          -> JopConfig.ep4cgx150Serial,
    "ep4cgx150Bram"            -> JopConfig.ep4cgx150Bram,
    "ep4cgx150BramGc"          -> JopConfig.ep4cgx150BramGc,
    "ep4cgx150BramSerial"      -> JopConfig.ep4cgx150BramSerial,
    "ep4cgx150HwMath"          -> JopConfig.ep4cgx150HwMath,
    "ep4cgx150HwFloat"         -> JopConfig.ep4cgx150HwFloat,
    "ep4cgx150McFallback"      -> JopConfig.ep4cgx150McFallback,
    "ep4cgx150Smp(2)"          -> JopConfig.ep4cgx150Smp(2),
    "cyc5000Serial"            -> JopConfig.cyc5000Serial,
    "cyc5000Smp(2)"            -> JopConfig.cyc5000Smp(2),
    "auSerial"                 -> JopConfig.auSerial,
    "auMinimal"                -> JopConfig.auMinimal,
    "simulation"               -> JopConfig.simulation,
    "minimum"                  -> JopConfig.minimum,
    "max1000Sdram"             -> JopConfig.max1000Sdram,
    "ep4ce6Sdram"              -> JopConfig.ep4ce6Sdram,
    "wukongSdram"              -> JopConfig.wukongSdram,
    "wukongDdr3"               -> JopConfig.wukongDdr3,
    "wukongBram"               -> JopConfig.wukongBram,
    "wukongBramFull"           -> JopConfig.wukongBramFull,
    "wukongFull"               -> JopConfig.wukongFull,
    "wukongDdr3AllCu"          -> JopConfig.wukongDdr3AllCu,
    "wukongDdr3DspMul"         -> JopConfig.wukongDdr3DspMul,
    "wukongDdr3Fcu"            -> JopConfig.wukongDdr3Fcu,
    "wukongDdr3Lcu"            -> JopConfig.wukongDdr3Lcu,
    "wukongNoDcu"              -> JopConfig.wukongNoDcu,
    "wukongSdrAllCu"           -> JopConfig.wukongSdrAllCu,
    "wukongSdrFull"            -> JopConfig.wukongSdrFull,
    "wukongSmp(2)"             -> JopConfig.wukongSmp(2),
    "wukongSmpMinimal(2)"      -> JopConfig.wukongSmpMinimal(2),
    "wukongFullSmp(2)"         -> JopConfig.wukongFullSmp(2),
    "wukongDual"               -> JopConfig.wukongDual,
    "wukongDualIndependent"    -> JopConfig.wukongDualIndependent,
    "wukongDualIndependentSmp" -> JopConfig.wukongDualIndependentSmp(2, 100),
    "xc7a100tDbSerial"         -> JopConfig.xc7a100tDbSerial,
    "xc7a100tDbFull"           -> JopConfig.xc7a100tDbFull,
    "xc7a100tDbSmp(2)"         -> JopConfig.xc7a100tDbSmp(2),
    "ae115fbDdr2"              -> JopConfig.ae115fbDdr2,
    "colorlightI5Bram"         -> JopConfig.colorlightI5Bram,
    "colorlightI5Sdram"        -> JopConfig.colorlightI5Sdram)

  test("every preset resolves its jump table") {
    val failures = presets.flatMap { case (name, cfg) =>
      try {
        // Resolve for every core of every system — per-core configs can differ
        // (heterogeneous SMP), so resolving only core 0 would miss a bad one.
        for (sys <- cfg.resolvedSystems; cc <- sys.coreConfigs) cc.resolveJumpTable
        None
      } catch {
        case e: Throwable => Some(s"$name: ${e.getMessage}")
      }
    }
    assert(failures.isEmpty,
      s"${failures.length} preset(s) failed to resolve:\n" + failures.mkString("\n\n"))
  }

  test("the default JopCoreConfig resolves") {
    // This is what JopCoreTestHarness uses, so the JVM-test simulations depend
    // on it independently of any preset.
    JopCoreConfig().resolveJumpTable
  }

  test("a Microcode bytecode with no _sw handler is rejected — long-standing case") {
    // dadd has no dadd_sw and was already marked NoMicrocode, so BytecodeConfig
    // .validate rejects it before the jump table is ever resolved.
    val ex = intercept[IllegalArgumentException] {
      JopCoreConfig(bytecodes = Map("dadd" -> "mc"))
    }
    assert(ex.getMessage.contains("dadd"), s"should name the bytecode: ${ex.getMessage}")
  }

  test("a Microcode bytecode with no _sw handler is rejected — the eight still open") {
    // idiv, irem, i2f, f2i, fcmpl and fcmpg have no _sw handler but were marked
    // JavaOk, so `mc` passed validation and then silently kept the compute-unit
    // entry — the exact configuration that produces wrong arithmetic instead of
    // a build error. fneg, fcmpl and fcmpg have since left this group: fneg
    // already had a microcode implementation and only lacked the _sw label,
    // and fcmpl/fcmpg gained real _sw handlers (item 19 tier 1).
    for (name <- Seq("idiv", "irem", "i2f", "f2i", "fadd", "fsub", "fmul", "fdiv")) {
      val ex = intercept[IllegalArgumentException] {
        JopCoreConfig(bytecodes = Map(name -> "mc")).resolveJumpTable
      }
      assert(ex.getMessage.contains(name), s"should name $name: ${ex.getMessage}")
    }
  }

  test("the NoMicrocode constraint matches what the ROM actually provides") {
    // The constraint column is the declaration; useAlt throwing is the backstop.
    // They must agree, or a bytecode is either silently mis-dispatched (marked
    // JavaOk with no _sw) or needlessly forbidden (marked NoMicrocode with one).
    //
    // JavaOnly counts as forbidding microcode too, and more strongly: it forbids
    // hardware as well. frem and drem are the two, added when frem's absence
    // from the registry let `"*" -> "hw"` drive SUPPORT_FLOAT false and drop the
    // SoftFloat library it runs in. The invariant being pinned is "nothing
    // claims a microcode handler the ROM does not have", so both constraints
    // satisfy it; keeping the test literal would have made adding a Java-only
    // bytecode look like a regression.
    val alts = JumpTableInitData.simulation.altEntries.keySet
    val noSw = BytecodeConfig.all.filterNot(e => alts.contains(e.opcode)).map(_.name).toSet
    val noMc = BytecodeConfig.all.filter(e =>
      e.constraint == ImpConstraint.NoMicrocode ||
      e.constraint == ImpConstraint.JavaOnly).map(_.name).toSet
    assert(noSw == noMc,
      s"no _sw but microcode not forbidden: ${(noSw -- noMc).toSeq.sorted.mkString(", ")}; " +
      s"microcode forbidden but _sw exists: ${(noMc -- noSw).toSeq.sorted.mkString(", ")}")
  }

  test("imul_sw and lmul_sw are mutually exclusive") {
    // imul_sw is a self-contained shift-add loop needing no CU, selected by
    // imul = mc. lmul_sw drives ICU imul_wide, whose multiplier only exists
    // when imul = hw. So no single build can exercise both, and any config
    // asking for it must be rejected rather than silently producing an ICU
    // with no multiplier — which is what happened before the require was
    // corrected to check needsIntMul.
    val ex = intercept[IllegalArgumentException] {
      JopCoreConfig(bytecodes = Map("imul" -> "mc", "lmul" -> "mc",
                                    "idiv" -> "hw", "irem" -> "hw"))
    }
    assert(ex.getMessage.contains("lmul"), ex.getMessage)
    // The two legal halves, each fine on its own.
    JopCoreConfig(bytecodes = Map("imul" -> "mc")).resolveJumpTable   // imul_sw
    JopCoreConfig(bytecodes = Map("imul" -> "hw", "lmul" -> "mc")).resolveJumpTable // lmul_sw
  }

  test("_sw coverage is 12 of 32 configurable bytecodes") {
    val alts = JumpTableInitData.simulation.altEntries.keySet
    val configurable = BytecodeConfig.all.map(_.opcode).toSet
    val covered = configurable.intersect(alts)
    assert(covered.size == 12,
      s"expected 12 bytecodes with a _sw alternate, found ${covered.size}. " +
      "If a _sw handler was added to jvm.asm this is good news — update this " +
      "expectation and item 18 in docs/current-status.md.")
    // Every one of these 12 is now exercised by a passing simulation. The
    // four bogus float alternates (fadd/fsub/fmul/fdiv, which drove the removed
    // BmbFpu over I/O) were deleted, so "has an alternate" and "has a working
    // alternate" once again mean the same thing here.
    //
    // Long is fully covered; double has nothing. Pin that shape too, since it
    // is the actual gap rather than the raw count.
    val longOps = BytecodeConfig.all.filter(_.group == "long").map(_.opcode).toSet
    assert(longOps.subsetOf(alts), "all long ops should have a _sw alternate")
    val doubleOps = BytecodeConfig.all.filter(_.group == "double").map(_.opcode).toSet
    assert(doubleOps.intersect(alts).isEmpty,
      "no double op is expected to have a _sw alternate yet")
  }
}
