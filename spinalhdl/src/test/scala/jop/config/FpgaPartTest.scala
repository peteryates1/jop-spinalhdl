package jop.config

import org.scalatest.funsuite.AnyFunSuite

import java.io.File
import scala.io.Source

/**
 * A VIVADO FLOW MUST TIME FOR THE PART ITS BOARD DECLARES.
 *
 * Found 2026-10-28, rebuilding `xc7a100tDbEth` for status item 168: the build
 * summary said `FPGA: XC7A100T-1FGG676C` while Vivado built and timed for
 * `xc7a100tfgg676-2`. Parts.scala had ONE XC7A100T, speed grade -1, shared by
 * the Wukong and the QMTECH core board, whose board docs both say -2, and each
 * flow carried its own PART constant. Three of the four Vivado boards
 * disagreed with their Board. That is not cosmetic: a timing pass at -2 says
 * nothing about a -1 chip.
 *
 * Every fpga/<dir>/Makefile that sets PART is listed here against its Board,
 * and the list must name every such Makefile, so a new Vivado board cannot
 * slip past.
 */
class FpgaPartTest extends AnyFunSuite {

  private val vivadoFlows: Map[String, Board] = Map(
    "alchitry-au"               -> Board.AlchitryAuV2,
    "alchitry-au-ddr3-test"     -> Board.AlchitryAuV2,
    "colorlight-db-i9plus"      -> Board.ColorlightI9Plus,
    "qmtech-xc7a100t-dbfpga-v5" -> Board.QmtechXC7A100T,
    "qmtech-xc7a100t-wukong"    -> Board.WukongXC7A100T)

  private val PartLine = """^PART\s*[:?]?=\s*(\S+)\s*$""".r

  private def makefilePart(dir: String): Option[String] = {
    val f = new File(s"fpga/$dir/Makefile")
    if (!f.isFile) None
    else {
      val src = Source.fromFile(f)
      try src.getLines().collectFirst { case PartLine(p) => p } finally src.close()
    }
  }

  private def vivadoPartOf(board: Board): String = {
    val fpga = board.fpga.getOrElse(fail(s"${board.name} declares no FPGA"))
    fpga.vivadoPart.getOrElse(fail(s"${board.name}: ${fpga.name} is not a Xilinx device"))
  }

  test("every Makefile that sets PART is mapped to its board here") {
    val dirs = new File("fpga").listFiles.filter(_.isDirectory).map(_.getName)
      .filter(d => makefilePart(d).nonEmpty).toSet
    assert(dirs.nonEmpty, "no fpga/*/Makefile sets PART -- is the working directory the repo root?")
    assert(dirs == vivadoFlows.keySet,
      s"unmapped: ${(dirs -- vivadoFlows.keySet).toSeq.sorted}, stale: ${(vivadoFlows.keySet -- dirs).toSeq.sorted}")
  }

  test("every Vivado flow builds and times for the part its board declares") {
    val wrong = vivadoFlows.toSeq.sortBy(_._1).flatMap { case (dir, board) =>
      val part = makefilePart(dir).get
      val declared = vivadoPartOf(board)
      if (part == declared) None
      else Some(s"fpga/$dir: Makefile PART $part, but ${board.name} declares ${board.fpga.get.name} ($declared)")
    }
    assert(wrong.isEmpty, wrong.mkString("\n", "\n", ""))
  }

  // THE ORDERING CODES, every board. Quartus takes `name` verbatim as DEVICE, so
  // an Altera name that moved would build a different chip; the three Xilinx
  // boards are -2 since 2026-10-28, from their board docs and flows.
  test("every board names its chip exactly") {
    val expected = Seq(
      Board.QmtechEP4CGX150 -> "EP4CGX150DF27I7",
      Board.CYC5000         -> "5CEBA2U15C8",
      Board.ColorlightI5    -> "LFE5U-25F-6BG381C",
      Board.ColorlightI9Plus -> "XC7A50T-1FGG484C",
      Board.AlchitryAuV2    -> "XC7A35T-2FTG256C",
      Board.AE115FB         -> "EP4CE115F23I7",
      Board.WukongXC7A100T  -> "XC7A100T-2FGG676C",
      Board.QmtechXC7A100T  -> "XC7A100T-2FGG676C",
      Board.MAX1000         -> "10M08SAU169C8G",
      Board.GenericEP4CE6   -> "EP4CE6E22C8")
    for ((board, code) <- expected)
      assert(board.fpga.map(_.name).contains(code), s"${board.name}: ${board.fpga.map(_.name)}")
  }

  test("a device defaults to its slowest grade, and a board overrides it") {
    // Xilinx and Lattice count up to faster, Altera up to slower.
    assert(FpgaDevice.XC7A100T.name == "XC7A100T-1FGG676C")
    assert(FpgaDevice.LFE5U25F.name == "LFE5U-25F-6BG381C")
    assert(FpgaDevice.EP4CGX150DF27.name == "EP4CGX150DF27C8")
    assert(FpgaDevice.XC7A100T.withGrade(2).vivadoPart.contains("xc7a100tfgg676-2"))
    assert(FpgaDevice.EP4CGX150DF27.withGrade(7, 'I').name == "EP4CGX150DF27I7")
    assert(FpgaDevice.EP4CGX150DF27.vivadoPart.isEmpty, "Vivado builds Xilinx parts only")
  }

  test("a grade the device is not sold in is refused") {
    val e = intercept[IllegalArgumentException](FpgaDevice.XC7A100T.withGrade(4))
    assert(e.getMessage.contains("not sold in grade C4"), e.getMessage)
    intercept[IllegalArgumentException](FpgaDevice.EP4CGX150DF27.withGrade(6))
    intercept[IllegalArgumentException](FpgaDevice.LFE5U25F.withGrade(5))
  }
}
