package jop.net

import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.net.URLClassLoader
import java.nio.file.{Files, Path, Paths}
import javax.tools.ToolProvider
import scala.collection.mutable

/**
 * java/net's com.jopdesign.net, compiled for the host on a shim of the three
 * JOP runtime classes it uses: Native (rd and wr, handed to whatever Io the
 * test installs -- a MAC, a clock), Const (I/O addresses and the unit's
 * identity) and JVMHelp (the console, kept in a StringBuilder).
 */
object JavaNetStack {
  val IoEth = 0x50
  val IoMdio = 0x58
  val IoWd = 0x30
  val IoUsCnt = 0x71
  val MacHi = 0x024A
  val MacLo = 0x4F500001

  private val shim = Map(
    "Native" ->
      """package com.jopdesign.sys;
        |public class Native {
        |  public interface Io { int rd(int addr); void wr(int val, int addr); }
        |  public static Io io;
        |  public static int rd(int addr) { return io.rd(addr); }
        |  public static void wr(int val, int addr) { io.wr(val, addr); }
        |}
        |""".stripMargin,
    "Const" ->
      s"""package com.jopdesign.sys;
         |public class Const {
         |  public static final int IO_ETH = $IoEth;
         |  public static final int IO_MDIO = $IoMdio;
         |  public static final int IO_WD = $IoWd;
         |  public static final int IO_US_CNT = $IoUsCnt;
         |  public static final int ETH_PHY_ADDR = 1;
         |  public static final boolean ETH_GIGABIT = true;
         |  public static final int ETH_MAC_HI = $MacHi;
         |  public static final int ETH_MAC_LO = $MacLo;
         |  public static final String ETH_HOSTNAME = "";
         |}
         |""".stripMargin,
    "JVMHelp" ->
      """package com.jopdesign.sys;
        |public class JVMHelp {
        |  public static StringBuilder out = new StringBuilder();
        |  public static void wr(char c) { out.append(c); }
        |  public static void wr(String s) { out.append(s); }
        |}
        |""".stripMargin)

  /** The compiled classes, once per test JVM. */
  lazy val classes: Path = {
    val src = Paths.get("java/net/src/com/jopdesign/net")
    val work = Files.createTempDirectory("javanet")
    val shimDir = Files.createDirectories(work.resolve("shim/com/jopdesign/sys"))
    val shimFiles = shim.map { case (n, text) =>
      val f = shimDir.resolve(s"$n.java")
      Files.write(f, text.getBytes("UTF-8"))
      f.toString
    }
    val stack = Files.list(src).iterator()
    val stackFiles = mutable.ArrayBuffer[String]()
    while (stack.hasNext) {
      val f = stack.next()
      if (f.toString.endsWith(".java")) stackFiles += f.toString
    }
    val out = Files.createDirectories(work.resolve("classes"))
    val javac = ToolProvider.getSystemJavaCompiler
    require(javac != null, "no system Java compiler -- these tests need a JDK")
    val err = new java.io.ByteArrayOutputStream
    val rc = javac.run(null, null, err,
      (Seq("--release", "8", "-nowarn", "-d", out.toString) ++ stackFiles ++ shimFiles): _*)
    require(rc == 0, s"javac failed:\n$err")
    out
  }

  /** A fresh load of the stack: its statics are its own. */
  def load(): Loaded = new Loaded(new URLClassLoader(Array(classes.toUri.toURL), getClass.getClassLoader))

  class Loaded(val loader: ClassLoader) {
    def cls(name: String): Class[_] =
      loader.loadClass(if (name.contains('.')) name else s"com.jopdesign.net.$name")

    /** A public method by name and argument count; its exception unwrapped. */
    def call(target: AnyRef, c: Class[_], name: String, args: AnyRef*): AnyRef = {
      val m = c.getMethods.find(m => m.getName == name && m.getParameterCount == args.size &&
        m.getParameterTypes.zip(args).forall { case (t, a) => a == null || box(t).isInstance(a) })
        .getOrElse(throw new NoSuchMethodException(s"${c.getName}.$name/${args.size}"))
      try m.invoke(target, args: _*)
      catch { case e: InvocationTargetException => throw e.getCause }
    }
    def static(className: String, name: String, args: AnyRef*): AnyRef = call(null, cls(className), name, args: _*)
    def field(target: AnyRef, className: String, name: String): AnyRef = cls(className).getField(name).get(target)
    def setStatic(className: String, name: String, value: AnyRef): Unit = cls(className).getField(name).set(null, value)

    /** Route Native.rd/wr to io. */
    def install(io: (Int => Int, (Int, Int) => Unit)): Unit = {
      val ioCls = cls("com.jopdesign.sys.Native$Io")
      val h = new InvocationHandler {
        def invoke(p: AnyRef, m: Method, a: Array[AnyRef]): AnyRef = m.getName match {
          case "rd" => Int.box(io._1(a(0).asInstanceOf[Integer]))
          case "wr" => io._2(a(0).asInstanceOf[Integer], a(1).asInstanceOf[Integer]); null
          case "hashCode" => Int.box(System.identityHashCode(p))
          case "equals" => java.lang.Boolean.valueOf(p eq a(0))
          case "toString" => "FakeIo"
        }
      }
      cls("com.jopdesign.sys.Native").getField("io")
        .set(null, Proxy.newProxyInstance(loader, Array(ioCls), h))
    }
  }

  private def box(t: Class[_]): Class[_] =
    if (t == classOf[Int]) classOf[Integer]
    else if (t == classOf[Boolean]) classOf[java.lang.Boolean]
    else t
}
