package jop.config

import hw.Board

/**
 * What JOP does on a board: what it calls its top-level entity there, and
 * whether an SDR build gets the stack cache.
 *
 * JOP's choices, not facts about the board, so they are kept on JOP's side of
 * the hardware layer (status item 173). Until 2026-11-03 they were three
 * fields of Board -- `entityTag`, `entitySuffix` and `useStackCache` -- the
 * last JOP settings there. A board not listed takes the defaults: an entity
 * named by its memory type alone, and no stack cache on SDR.
 *
 * @param entityTag     replaces the memory-type part of the entity name:
 *                      JopCyc5000Top rather than JopSdramTop
 * @param entitySuffix  follows the memory-type part: JopSdramWukongTop, and a
 *                      two-system build's JopDualWukongTop
 * @param useStackCache whether an SDR system gets the stack cache and its
 *                      8192-word spill region per core
 *                      (JopConfig.effectiveUseStackCache)
 */
case class JopBoardSettings(
  entityTag: String = "",
  entitySuffix: String = "",
  useStackCache: Boolean = false)

object JopBoardSettings {

  // Keyed by each board's name as its definition gives it, so a board renamed
  // there keeps its settings, and one whose definition goes away stops this
  // file compiling.
  private val byName: Map[String, JopBoardSettings] = {
    val entries = Seq(
      Board.CYC5000          -> JopBoardSettings(entityTag = "Cyc5000"),
      Board.MAX1000          -> JopBoardSettings(entityTag = "Max1000Sdram"),
      Board.GenericEP4CE6    -> JopBoardSettings(entityTag = "Ep4ce6Sdram"),
      Board.ColorlightI5     -> JopBoardSettings(entitySuffix = "I5"),
      Board.ColorlightI9Plus -> JopBoardSettings(entitySuffix = "I9Plus", useStackCache = true),
      Board.AE115FB          -> JopBoardSettings(entitySuffix = "Ae115fb"),
      Board.WukongXC7A100T   -> JopBoardSettings(entitySuffix = "Wukong", useStackCache = true))
    val names = entries.map(_._1.name)
    require(names.distinct.size == names.size, s"two boards share a name: ${names.diff(names.distinct)}")
    entries.map { case (b, s) => b.name -> s }.toMap
  }

  /** JOP's settings for `board`. */
  def of(board: Board): JopBoardSettings = byName.getOrElse(board.name, JopBoardSettings())
}
