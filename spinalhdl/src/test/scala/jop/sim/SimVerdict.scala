package jop.sim

/**
 * What a simulation's UART output must say for the sim to pass: Left is a
 * failure and why, Right a pass. A sim that prints what it saw and exits 0
 * cannot fail, and item 176 found six of them; these are the verdicts they
 * share now. SimVerdictTest holds them to hw_verify.py's own cases.
 */
object SimVerdict {

  /** HelloWorld's verdict. */
  def helloWorld(out: String): Either[String, String] =
    if (out.contains("Hello World")) Right("Hello World")
    else Left("no 'Hello World' on the UART")

  /** DoAll's verdict, by the rule `fpga/scripts/hw_verify.py` (`judge`,
    * `judge_count`) applies on a board, so a sim and a board pass the same
    * output: DoAll announces `DoAll: N tests` and has no summary line, so it
    * needs N `ok` lines, no `fail`/`failed`/`failure` as a word (a test NAMED
    * for an exception is not one), and no uncaught exception. The count comes
    * from DoAll, never from the sim: JopDcuCacheSim stopped at a 59 written
    * into it, nine tests short of the suite (item 177). */
  def doAll(out: String): Either[String, String] = {
    val oks = """(?m)\bok\s*$""".r.findAllIn(out).size
    val fails = """(?i)(?<![A-Za-z])fail(?:ed|ure|s)?(?![A-Za-z])""".r.findAllIn(out).size
    val crashed = "Uncaught exception".r.findAllIn(out).size
    announced(out) match {
      case None => Left("DoAll never announced its test count")
      case Some(n) if oks < n || fails > 0 || crashed > 0 =>
        Left(s"$oks ok of $n tests, $fails fail, $crashed uncaught")
      case Some(n) => Right(s"$oks ok of $n tests")
    }
  }

  /** The test count DoAll announces before it starts, once it has. */
  def announced(out: String): Option[Int] =
    """DoAll: (\d+) tests""".r.findFirstMatchIn(out).map(_.group(1).toInt)
}
