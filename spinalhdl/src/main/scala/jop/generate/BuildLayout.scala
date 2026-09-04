package jop.generate

/**
 * Where generated and transient build products live: `build/<config>/...`,
 * one directory per build CONFIGURATION, and nothing generated anywhere else.
 *
 * WHY A CONFIG IS NOT AN ENTITY. The obvious key is `JopConfig.entityName`, and
 * it is wrong: that name encodes only the memory type, the board suffix and
 * whether `cpuCnt >= 2` -- not how many cores, and none of the overrides. So
 * `ep4cgx150Smp 2` and `ep4cgx150Smp 12 36 mcache=14/5` are both
 * `JopSmpSdramTop`, and keying artefacts on it lets a 2-core 80 MHz build and a
 * 12-core 36 MHz build share a directory and overwrite each other's PLL. That
 * is exactly the bug that made a shared `generated/dram_pll.vhd` reclock the
 * single-core build: same defect, one level up.
 *
 * The key is therefore the INVOCATION -- preset plus arguments, sanitised:
 *
 *   ep4cgx150Serial                   -> ep4cgx150Serial
 *   ep4cgx150Smp 12 36 mcache=14/5    -> ep4cgx150Smp-12-36-mcache14_5
 *   wukongSmp 4 bc=double:java        -> wukongSmp-4-bcdouble_java
 *
 * Deterministic, readable, and it needs no per-switch table -- a new override
 * appears in the name for free. Two spellings of the same configuration give
 * two directories, which wastes disk and is never wrong; two DIFFERENT
 * configurations can never collide, which is the property that matters.
 */
/**
 * The layout itself is DATA, not constants baked into the generators.
 *
 * Parameterised for two reasons. The old tree (`spinalhdl/generated`,
 * `fpga/<board>/generated`, `output_files/`) and the new one must coexist while
 * boards are converted one at a time -- an all-at-once move would touch 75
 * files across ten board directories with no way to verify most of them. And
 * the structure will change again: nesting, naming and depth should be one
 * edit here, not a hunt through generators.
 *
 * Nothing derived from the layout may be written down twice. In particular the
 * DEPTH of a tool's working directory below the repo root is COMPUTED from the
 * path, because a hardcoded `3` is a second copy of the structure and goes
 * stale the moment a level is added or removed.
 */
case class BuildLayout(
  root: String = "build",
  rtl: String = "rtl",
  ip: String = "ip",
  quartus: String = "quartus",
  vivado: String = "vivado",
  nextpnr: String = "nextpnr",
  java: String = "java",
  asm: String = "asm",
  standalone: String = "standalone"
) {

  /** Directory name for one invocation. */
  def configName(preset: String, args: Seq[String]): String = {
    val parts = preset +: args.filterNot(_ == preset)
    parts.map(BuildLayout.sanitise).filter(_.nonEmpty).mkString("-")
  }

  def configDir(preset: String, args: Seq[String]): String =
    s"$root/${configName(preset, args)}"

  private def sub(d: String)(preset: String, args: Seq[String]): String =
    s"${configDir(preset, args)}/$d"

  def rtlDir     = sub(rtl) _
  def ipDir      = sub(ip) _
  def quartusDir = sub(quartus) _
  def vivadoDir  = sub(vivado) _
  def nextpnrDir = sub(nextpnr) _
  def javaDir    = sub(java) _
  def asmDir     = sub(asm) _

  /** Where a STANDALONE top goes -- a single component or testbench emitted on
    * its own (`Shift`, `MethodCacheTb`, an exerciser), not part of a preset.
    *
    * These have no configuration to key on: they are not built from a JopConfig
    * preset, so `configDir` has nothing to name them with. They still must not
    * write into the source tree, so they get their own branch of the build
    * tree, one directory per top so two generators cannot overwrite each
    * other's output. */
  def standaloneDir(top: String): String = s"$root/$standalone/${BuildLayout.sanitise(top)}"

  /** How many levels below the repo root a directory sits, counted from the
    * path rather than assumed. */
  def depthOf(repoRelativeDir: String): Int =
    repoRelativeDir.split('/').count(_.nonEmpty)

  /** Rewrite a repo-root-relative path for a tool whose working directory is
    * `dir`. Relative, not absolute, so a generated project is relocatable and
    * records nothing about this machine's layout. */
  def relativeTo(dir: String, repoRelativePath: String): String =
    ("../" * depthOf(dir)) + repoRelativePath
}

object BuildLayout {

  /** The layout in force. Generators take a BuildLayout and default to this,
    * so changing the structure is one edit. */
  val default: BuildLayout = BuildLayout()

  /** Path-safe: `mcache=14/5` -> `mcache14_5`, `bc=double:java` -> `bcdouble_java`. */
  def sanitise(s: String): String =
    s.replace("=", "").replace("/", "_").replace(":", "_")
     .replaceAll("[^A-Za-z0-9._-]", "")
}

/**
 * Prints the configuration directory for an invocation, so Makefiles can ask
 * for it instead of reimplementing `configName`.
 *
 * The sanitisation rules are deliberately NOT duplicated in Make. A second copy
 * of the naming would go stale the first time an override spelling changed, and
 * the failure would be a silently split build directory rather than an error --
 * the same class of defect this layout exists to remove.
 */
object BuildLayoutMain extends App {
  val preset = args.headOption.getOrElse(
  sys.error(
    "no preset given. Pass the preset name as the first argument.\n" +
    "There is deliberately no default: this main writes a constraint or\n" +
    "project file, and a default silently produces a WELL-FORMED file for\n" +
    "the wrong board at the path --write names, which then builds."))
  // `--out <file>` WRITES THE ANSWER RATHER THAN PRINTING IT.
  //
  // Callers used to scrape stdout for a line matching `^[info] build/...`.
  // That reads sbt's LOG rather than this program's output, and the prefix is
  // an artefact of sbt forking the run and re-logging the child's stdout at
  // INFO -- so it depends on the sbt version and on the log level in force.
  // CI ran at a level where those lines never appeared: sbt succeeded, printed
  // the directory, and the caller saw nothing, then reported "produced no
  // directory for preset" as though the preset were unknown. A file cannot be
  // swallowed by a log level.
  val outIdx = args.indexWhere(_.equalsIgnoreCase("--out"))
  val rest = args.drop(1).filterNot(_.equalsIgnoreCase("buildtree"))
                 .filterNot(_.equalsIgnoreCase("--out"))
                 .filterNot(a => outIdx >= 0 && args.lift(outIdx + 1).contains(a))
  val dir = BuildLayout.default.configDir(preset, rest.toIndexedSeq)
  if (outIdx >= 0) {
    val path = args.lift(outIdx + 1).getOrElse(
      sys.error("--out needs a file path"))
    val f = new java.io.File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val w = new java.io.PrintWriter(f)
    try w.println(dir) finally w.close()
  }
  println(dir)
}
