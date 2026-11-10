package jop.io

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.ClockDomain

/**
 * JopTop merges the clock domains the devices make with
 * DeviceClockDomains.orElse. Its own merge once named three fields of four,
 * and the fourth, a BUFIO's domain for Rgmii1000's IDDRs, was lost: the BUFIO
 * drove nothing (ColorlightDbI9PlusEth1000Test follows each IDDR's clock to
 * the cell that drives it).
 */
class DeviceClockDomainsTest extends AnyFunSuite {

  // Built through the constructor, so that a field added later is covered
  // without a change here. A ClockDomain needs an elaboration to exist, so
  // the field is marked with Some(null): it is the Option that is merged.
  private val ctor = classOf[DeviceClockDomains].getConstructors.head
  private val arity = ctor.getParameterCount
  private val marked: Option[ClockDomain] = Some(null)

  /** All None but field `i`. */
  private def only(i: Int): DeviceClockDomains =
    ctor.newInstance((0 until arity).map(j => if (j == i) marked else None): _*)
      .asInstanceOf[DeviceClockDomains]

  test("orElse keeps every field, from either side") {
    val none = DeviceClockDomains()
    assert(arity == none.productArity)
    for (i <- 0 until arity) {
      val name = none.productElementName(i)
      assert((only(i) orElse none).productElement(i) == marked, s"$name from the left is lost")
      assert((none orElse only(i)).productElement(i) == marked, s"$name from the right is lost")
    }
  }
}
