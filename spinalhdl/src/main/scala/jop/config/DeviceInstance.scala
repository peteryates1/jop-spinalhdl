package jop.config

/**
 * A named device instance within a core or cluster.
 *
 * Replaces boolean flags (hasUart, hasEth, etc.) with declarative device descriptions.
 * Devices are identified by type string, with optional board connector mapping and
 * device-specific parameters.
 *
 * @param deviceType  Device type key into DeviceTypes.registry ("uart", "ethernet", etc.)
 * @param mapping     Signal → board connector pin (e.g., "txd" -> "j10.1")
 * @param params      Device-specific parameters (baud rate, clock divider, etc.)
 * @param devicePart  Physical device part name on the board (e.g., "CP2102N", "RTL8211EG").
 *                    Used by PinResolver to look up pin mappings from BoardDevice.
 */
case class DeviceInstance(
  deviceType: String,
  mapping: Map[String, String] = Map.empty,
  params: Map[String, Any] = Map.empty,
  devicePart: Option[String] = None
)
