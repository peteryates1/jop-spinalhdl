package jop.generate

/** Where a STANDALONE top's artefacts go, and the summary console.mk reads.
  *
  * A standalone top -- an exerciser, a flash programmer, a UART echo -- is not
  * a JopConfig preset, so it has no `resolvedSystems`, no microcode and no
  * generated project. For a long time that was taken to mean it could not live
  * under `build/<config>/` either, and every one of them generated into
  * `spinalhdl/generated`: eight build products written into the source tree.
  *
  * It does not follow. All these designs need is a directory keyed by their own
  * name, which BuildLayout can give them exactly as it does a preset. The part
  * they genuinely cannot have -- pin and timing generation from a BoardDesign
  * -- is a separate question, and the Vivado ones answer it with a hand-written
  * XDC anyway.
  *
  * `summary` matters more than it looks: console.mk derives the console rate
  * from the build's own summary rather than from a Makefile constant (status
  * item 70), so a design that does not write one gets an EMPTY baud and a
  * `make monitor` with no rate at all.
  */
object StandaloneBuild {

  def rtlDir(cfgName: String): String =
    BuildLayout.default.rtlDir(cfgName, Seq.empty)

  def configDir(cfgName: String): String =
    BuildLayout.default.configDir(cfgName, Seq.empty)

  /** Write `<entity>.summary.txt` beside the RTL, in the shape console.mk
    * greps. `uartBaud` is optional: a design with no UART simply omits the
    * line rather than inventing a rate. */
  def summary(cfgName: String, entity: String, board: String, fpga: String,
              clkMhz: Int, uartBaud: Option[Int] = None,
              extra: Seq[(String, String)] = Seq.empty): Unit = {
    val lines =
      f"  Entity:        $entity%n" +
      f"  Board:         $board%n" +
      f"  FPGA:          $fpga%n" +
      f"  Clock:       $clkMhz MHz%n" +
      uartBaud.map(b => f"  UART baud:   $b%n").getOrElse("") +
      extra.map { case (k, v) => f"  $k%-14s $v%n" }.mkString
    val path = s"${rtlDir(cfgName)}/$entity.summary.txt"
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new java.io.PrintWriter(f)
    try w.print(s"=== $entity Build Configuration ===%n".format() + lines)
    finally w.close()
    println(s"Wrote $path")
  }
}
