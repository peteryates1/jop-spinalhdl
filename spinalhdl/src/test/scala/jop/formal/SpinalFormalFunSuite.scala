package jop.formal

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.Tag
import spinal.core._
import spinal.core.formal._

/**
 * Base class for formal verification tests.
 *
 * Mirrors SpinalHDL's own SpinalFormalFunSuite (from spinal.lib.formal)
 * which is not published as a library dependency.
 */
class SpinalFormalFunSuite extends AnyFunSuite {
  implicit val className: String = getClass.getSimpleName()

  def assert(assertion: Bool): Unit = {
    spinal.core.assert(assertion)
  }

  def assume(assertion: Bool): Unit = {
    spinal.core.assume(assertion)
  }

  def test(testName: String)(testFun: => Unit): Unit = {
    super.test("formal_" + testName, Tag("formal")) {
      testFun
    }
  }

  def shouldFail(body: => Unit): Unit = org.scalatest.Assertions.assert(try {
    body
    false
  } catch {
    case _: Throwable => true
  })
}
