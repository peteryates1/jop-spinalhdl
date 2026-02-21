package jop.utils

import java.io.{File, FileWriter, PrintWriter}
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object TestHistory {
  val historyFile = "test-history.tsv"
  val header = "timestamp\ttest_name\tplatform\tresult\tjop_file\tjop_sha256\trom_sha256\tram_sha256\tnotes"

  def sha256(path: String): String = {
    val file = new File(path)
    if (!file.exists()) return "MISSING"
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = java.nio.file.Files.readAllBytes(file.toPath)
    digest.digest(bytes).map("%02x".format(_)).mkString
  }

  def record(testName: String, platform: String, result: String,
             jopFile: String, romFile: String, ramFile: String,
             notes: String = ""): Unit = {
    val file = new File(historyFile)
    val needsHeader = !file.exists() || file.length() == 0
    val pw = new PrintWriter(new FileWriter(file, true))
    if (needsHeader) pw.println(header)
    val ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    pw.println(s"$ts\t$testName\t$platform\t$result\t$jopFile\t${sha256(jopFile)}\t${sha256(romFile)}\t${sha256(ramFile)}\t$notes")
    pw.close()
  }

  /** Call at sim start, returns a RunRecord for later finish() call */
  def startRun(testName: String, platform: String,
               jopFile: String, romFile: String, ramFile: String): RunRecord = {
    val jopHash = sha256(jopFile)
    val romHash = sha256(romFile)
    val ramHash = sha256(ramFile)
    RunRecord(testName, platform, jopFile, jopHash, romHash, ramHash)
  }

  case class RunRecord(testName: String, platform: String,
                       jopFile: String, jopHash: String, romHash: String, ramHash: String) {
    def finish(result: String, notes: String = ""): Unit = {
      val file = new File(historyFile)
      val needsHeader = !file.exists() || file.length() == 0
      val pw = new PrintWriter(new FileWriter(file, true))
      if (needsHeader) pw.println(header)
      val ts = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
      pw.println(s"$ts\t$testName\t$platform\t$result\t$jopFile\t$jopHash\t$romHash\t$ramHash\t$notes")
      pw.close()
    }
  }
}
