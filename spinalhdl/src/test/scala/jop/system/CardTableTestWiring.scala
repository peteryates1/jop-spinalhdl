package jop.system

import jop.config._
import spinal.core._
import spinal.lib.bus.bmb._

/**
 * The card table a single-core test harness has to provide itself.
 *
 * The table is a CLUSTER-level resource (see JopCluster — a per-core one is
 * item 1's bug), so a harness that instantiates JopCore directly has to build
 * it. Tying the port off instead would be quietly unsound rather than merely
 * wrong: the core answers CARD_SHIFT/CARD_COUNT from its own config, so GC.init
 * would see a card table, enable generational mode, and then mark into nothing —
 * every remembered-set entry lost, in exactly the sims that exist to exercise
 * generational GC.
 *
 * ONE COPY, FOR EVERY HARNESS. JopCoreTestHarness wired this inline and
 * JopCoreWithCacheTestHarness not at all: it took `hasCard = true`, exposed the
 * card port and drove nothing, so JopCardMarkSim — the end-to-end card-marking
 * check through the cache — could not elaborate, and nothing noticed because
 * nothing ran it (status item 139). SpinalHDL reports NO DRIVER only at
 * ELABORATION, so a port added to CardCtrlPort breaks a harness in the sims and
 * never in `sbt compile`; with one copy it is fixed for both or for neither.
 *
 * The wiring is JopCluster's for one core: marks come from snooping the core's
 * own BMB port (the arbiter output on SMP), and control writes are registered
 * once before they reach the table.
 *
 * Call it from the harness constructor, after the JopCore; a no-op when the
 * config has no card table.
 */
object CardTableTestWiring {
  def apply(jopCore: JopCore, config: JopCoreConfig): Unit =
    if (config.memConfig.hasCardTable) new Area {
      val mc = config.memConfig
      val ct = new jop.memory.CardTable(mc.cardCount, mc.cardShift, mc.addressWidth)
      val port = jopCore.io.card.get
      val idxW = ct.idxWidth

      val cmdIsWrite = jopCore.io.bmb.cmd.fragment.opcode === Bmb.Cmd.Opcode.WRITE
      ct.io.markValid := jopCore.io.bmb.cmd.fire && cmdIsWrite
      ct.io.markAddr  := (jopCore.io.bmb.cmd.fragment.address >> 2).resize(mc.addressWidth)

      val wrValid = RegNext(port.wr)     init (False)
      val wrSel   = RegNext(port.sel)    init (0)
      val wrData  = RegNext(port.wrData) init (0)

      val cardLo    = Reg(UInt(mc.addressWidth bits)) init (0)
      val cardHi    = Reg(UInt(mc.addressWidth bits)) init (0)
      val cardRdIdx = Reg(UInt(idxW bits)) init (0)
      when(wrValid) {
        switch(wrSel) {
          is(0) { cardLo    := wrData(mc.addressWidth - 1 downto 0).asUInt }
          is(1) { cardHi    := wrData(mc.addressWidth - 1 downto 0).asUInt }
          is(2) { cardRdIdx := wrData(idxW - 1 downto 0).asUInt }
        }
      }
      ct.io.baseWord := cardLo
      ct.io.topWord  := cardHi
      ct.io.rdIdx    := cardRdIdx

      val clrWr   = wrValid && (wrSel === U(6, 3 bits))
      val clrAllV = wrData.andR
      ct.io.clrEn  := clrWr && !clrAllV
      ct.io.clrAll := clrWr && clrAllV
      ct.io.clrIdx := wrData(idxW - 1 downto 0).asUInt

      port.rdData := ct.io.rdData
      // The clear-all sweep stalls the core until the table is clear (status
      // item 131).
      port.busy := ct.io.clrBusy
    }.setName("cardTable")
}
