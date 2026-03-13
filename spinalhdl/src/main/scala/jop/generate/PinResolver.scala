package jop.generate

import jop.config._
import jop.io.DeviceTypes

/** Pin assignment: maps an FPGA pin to a Verilog port name */
case class PinAssignment(fpgaPin: String, verilogPort: String)

/**
 * Shared pin resolution for constraint generators (QSF, XDC).
 *
 * Resolution chain:
 *   DeviceDriver.pins: verilogPort -> deviceSignal
 *   BoardDevice.mapping: deviceSignal -> connectorRef
 *   SystemAssembly.resolvePin: connectorRef -> fpgaPin
 */
object PinResolver {

  /** Resolve clock FPGA pin from assembly's CLOCK_* device */
  def clockFpgaPin(assembly: SystemAssembly): Option[String] =
    assembly.allDevices
      .find(_.part.startsWith("CLOCK_"))
      .flatMap(d => assembly.pinMapping(d.part).get("clock"))

  /** Resolve reset FPGA pin from SWITCH device's "reset" mapping */
  def resetFpgaPin(assembly: SystemAssembly): Option[String] =
    assembly.allDevices
      .filter(_.part == "SWITCH")
      .flatMap(_.mapping.get("reset"))
      .flatMap(assembly.resolvePin)
      .headOption

  /** Resolve SDRAM clock FPGA pin */
  def sdramClockFpgaPin(assembly: SystemAssembly): Option[String] =
    assembly.memoryDevices
      .filter(_._2.memType == MemoryType.SDRAM_SDR)
      .headOption
      .flatMap { case (_, md) => assembly.pinMapping(md.name).get("CLK") }

  /** Resolve LED pins, sorted by index */
  def ledPins(assembly: SystemAssembly): Seq[PinAssignment] = {
    val ledMappings = assembly.allPinMappings("LED")
    ledMappings.toSeq
      .sortBy { case (signal, _) => signal.stripPrefix("led").toInt }
      .map { case (signal, fpgaPin) =>
        PinAssignment(fpgaPin, s"led[${signal.stripPrefix("led")}]")
      }
  }

  /** Resolve SDR SDRAM pins from memory device mapping */
  def sdramPins(assembly: SystemAssembly): Seq[PinAssignment] = {
    val sdramDevices = assembly.memoryDevices.filter(_._2.memType == MemoryType.SDRAM_SDR)
    sdramDevices.headOption match {
      case Some((_, md)) =>
        val devicePins = assembly.pinMapping(md.name)
        DeviceDriver.sdramPins.flatMap { case (verilogPort, deviceSignal) =>
          devicePins.get(deviceSignal).map(PinAssignment(_, verilogPort))
        }.toSeq
      case None => Seq.empty
    }
  }

  /** Resolve driver pins (UART, Ethernet, VGA, SD) — legacy path via DeviceDriver enum */
  def driverPins(assembly: SystemAssembly, sys: JopSystem): Seq[PinAssignment] =
    sys.drivers.flatMap { driver =>
      val devicePins = assembly.pinMapping(driver.devicePart)
      driver.pins.flatMap { case (verilogPort, deviceSignal) =>
        devicePins.get(deviceSignal).map(PinAssignment(_, verilogPort))
      }
    }

  /** Resolve device pins from effectiveDevices + DeviceTypes registry.
   *  Each DeviceInstance with a devicePart is resolved through the assembly. */
  def devicePins(assembly: SystemAssembly,
                 devices: Map[String, DeviceInstance]): Seq[PinAssignment] =
    devices.values.toSeq.flatMap { inst =>
      for {
        part <- inst.devicePart.toSeq
        info <- DeviceTypes.registry.get(inst.deviceType).toSeq
        boardPins = assembly.pinMapping(part)
        (verilogPort, deviceSignal) <- info.verilogPins(inst.params)
        fpgaPin <- boardPins.get(deviceSignal)
      } yield PinAssignment(fpgaPin, verilogPort)
    }
}
