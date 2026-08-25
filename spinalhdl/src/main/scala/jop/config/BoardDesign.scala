package jop.config

/**
 * What a constraint generator needs to know about a design. Not a JOP design --
 * ANY design on these boards.
 *
 * WHY THIS EXISTS. The board data was already generic: every `PinResolver`
 * method takes a `SystemAssembly` and nothing else, and `Board`, `FpgaDevice`
 * and `BoardDevice` mention nothing JOP-specific. But the five generators each
 * declared `JopConfig` as their parameter type while reading only the handful
 * of fields below -- so a design that was not a JOP configuration could not use
 * them, for no reason beyond the signature.
 *
 * That is why the SD, config-flash, SPI and flash-programmer exercisers still
 * carry hand-written .qsf files: not because the framework refused them, but
 * because they had no way to say "I am this entity, on this assembly, driving
 * these devices". They can now.
 *
 * NOT INCLUDED, deliberately: anything about cores, caches, bytecodes or
 * memory sizing. A constraint file is about pins, clocks and the part -- if a
 * generator ever needs more than this, that is a signal it is doing something
 * other than constraining I/O.
 */
trait BoardDesign {

  /** The boards this design is built on -- the source of every pin. */
  def assembly: SystemAssembly

  /** Top-level entity name, as the synthesiser will see it. */
  def entityName: String

  /** A human-readable name for the design, used in generated headers. */
  def designName: String

  /** Peripherals this design actually drives. Constraining what the BOARD
    * carries rather than what the DESIGN instantiates is a real bug, not a
    * harmless surplus: it produced `sdram_*` constraints in a DDR3 build and an
    * `e_rxc` clock group on a UART-only build, and Quartus responded by
    * silently discarding the whole `set_clock_groups`. */
  def devices: Map[String, DeviceInstance]

  /** Reset port, pin and polarity, or None if the design has no reset input. */
  def resetInput: Option[ResetInput]

  /** Does any system in this design drive SDR SDRAM? Distinct from `memType`,
    * which describes one system: a dual-cluster design has two. */
  def usesSdr: Boolean

  /** The primary system's memory type, if it has one. Decides how many PLL
    * outputs the timing constraints must name. */
  def memType: Option[MemoryType]

  def fpga: FpgaDevice
  def fpgaFamily: FpgaFamily

  /** How many LEDs the DESIGN drives, which is not how many the assembly
    * offers. `JopTop` sizes its port from `assembly.fpgaBoard.ledCount`, so the
    * generators must use the same number or they assign pins that do not exist:
    * on the EP4CGX150 + DB v4 the top has `led[1:0]` while the assembly offers
    * seven, and five assignments were being emitted into thin air. */
  def ledCount: Int = assembly.fpgaBoard.ledCount
}
