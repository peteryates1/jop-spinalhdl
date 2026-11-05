package jop.sim

import org.scalatest.funsuite.AnyFunSuite

/**
 * The verdicts in `SimVerdict`, which sims exit on (items 176, 177). Those
 * sims printed what they saw and exited 0, so none of them could fail; a
 * verdict that cannot fail is the same defect one level down, so each pass
 * here has a failure beside it.
 *
 * DoAll's rule is `fpga/scripts/hw_verify.py`'s, and so are the cases: a sim
 * and a board must pass the same output. hw_verify's judge once reported
 * "fail=0" on every hardware run by construction -- it matched `fail` as a
 * whole word, and DoAll prints `failed!` (its test_hw_verify.py has the story).
 */
class SimVerdictTest extends AnyFunSuite {

  private val doAll = SimVerdict.doAll _

  test("DoAll passes on every announced test ok, and only then") {
    val good = "DoAll: 3 tests\nBasic ok\nArray ok\nDeepRecursion ok\nJVM exit!\n"
    assert(doAll(good).isRight, doAll(good))
    val short = "DoAll: 3 tests\nBasic ok\nArray ok\n"            // one never finished
    assert(doAll(short).isLeft, doAll(short))
  }

  test("DoAll fails on a failed test, as hw_verify counts it") {
    val out = "DoAll: 3 tests\nBasic ok\nDeepRecursion failed!\nArray ok\nJVM exit!\n"
    assert(doAll(out) == Left("2 ok of 3 tests, 1 fail, 0 uncaught"))
  }

  test("DoAll does not count a test NAMED for an exception as a failure") {
    val out = "DoAll: 3 tests\nHwExceptionTest ok\nExcept ok\nNullPointer ok\nJVM exit!\n"
    assert(doAll(out).isRight, doAll(out))
  }

  test("DoAll fails on an uncaught exception, and with no count announced") {
    val crash = "DoAll: 2 tests\nBasic ok\nArray ok\nUncaught exception\n"
    assert(doAll(crash).isLeft, doAll(crash))
    assert(doAll("Basic ok\nArray ok\n") == Left("DoAll never announced its test count"))
  }

  test("HelloWorld passes on Hello World and nothing else") {
    assert(SimVerdict.helloWorld("M0\nHello World!\n").isRight)
    assert(SimVerdict.helloWorld("Small boot\nGC init...\n").isLeft)
  }
}
