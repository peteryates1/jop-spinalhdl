package jop.memory

import spinal.core._
import spinal.lib._
import spinal.lib.memory.sdram.SdramLayout
import spinal.lib.memory.sdram.sdr._

import scala.math.BigDecimal.RoundingMode

/**
 * Local copy of SpinalHDL's SdramCtrl with CKE gating disabled.
 *
 * The library SdramCtrl has a power-saving CKE gating mechanism:
 *   sdramCkeNext = !(readHistory.orR && !io.bus.rsp.ready)
 * When io.bus.rsp.ready goes low while reads are in-flight, CKE gates off.
 * But remoteCke (which freezes the command pipeline) is 2 cycles delayed,
 * creating a window where commands are issued but the SDRAM ignores them.
 * This misaligns the context pipeline with actual responses, causing data
 * shifts (got[N] = expected[N+1]).
 *
 * In multi-core configs, rsp.ready can go low briefly when the BMB arbiter
 * services another core, triggering this bug.
 *
 * Fix: sdramCkeNext = True (CKE never gates off).
 */
case class SdramCtrlNoCke[T <: Data](l : SdramLayout, t : SdramTimings, CAS : Int, contextType : T, produceRspOnWrite : Boolean = false) extends Component{
  import SdramCtrlBackendTask._
  import SdramCtrlFrontendState._

  val io = new Bundle{
    val bus = slave(SdramCtrlBus(l,contextType))
    val sdram = master(SdramInterface(l))
  }

  assert(l.columnWidth < 11)

  val clkFrequancy = ClockDomain.current.frequency.getValue
  def timeToCycles(time : TimeNumber): BigInt = (clkFrequancy * time).setScale(0, RoundingMode.UP).toBigInt

  val refresh = new Area{
    val counter = CounterFreeRun(timeToCycles(t.tREF/(1 << l.rowWidth)))
    val pending = RegInit(False) setWhen(counter.willOverflow)
  }


  val powerup = new Area {
    val counter = Reg(UInt(log2Up(timeToCycles(t.tPOW)) bits)) init (0)
    val done = RegInit(False)
    when(!done) {
      counter := counter + 1
      when(counter === U(counter.range -> true)) {
        done := True
      }
    }
  }


  val frontend = new Area{
    val banks = Reg(Vec(SdramCtrlBank(l),l.bankCount))
    banks.foreach(_.active init(False))

    val address = new Bundle{
      val column = UInt(l.columnWidth bits)
      val bank   = UInt(l.bankWidth bits)
      val row    = UInt(l.rowWidth bits)
    }
    address.assignFromBits(io.bus.cmd.address.asBits)

    val rsp = Stream(SdramCtrlBackendCmd(l,contextType))
    rsp.valid := False
    rsp.task := REFRESH
    rsp.bank := address.bank
    rsp.rowColumn := address.row.resized
    rsp.data := io.bus.cmd.data
    rsp.mask := io.bus.cmd.mask
    rsp.context := io.bus.cmd.context

    io.bus.cmd.ready := False


    val state = RegInit(BOOT_PRECHARGE)
    val bootRefreshCounter = Counter(t.bootRefreshCount)
    switch(state) {
      is(BOOT_PRECHARGE) {
        rsp.task := PRECHARGE_ALL
        when(powerup.done) {
          rsp.valid := True
          when(rsp.ready) {
            state := BOOT_REFRESH
          }
        }
      }
      is(BOOT_REFRESH) {
        rsp.valid := True
        rsp.task := REFRESH
        when(rsp.ready) {
          bootRefreshCounter.increment()
          when(bootRefreshCounter.willOverflowIfInc) {
            state := BOOT_MODE
          }
        }
      }
      is(BOOT_MODE) {
        rsp.valid := True
        rsp.task := MODE
        when(rsp.ready) {
          state := RUN
        }
      }
      default { //RUN
        when(refresh.pending){
          rsp.valid := True
          when(banks.map(_.active).reduce(_ || _)){
            rsp.task := PRECHARGE_ALL
            when(rsp.ready){
              banks.foreach(_.active := False)
            }
          } otherwise {
            rsp.task := REFRESH
            when(rsp.ready){
              refresh.pending := False
            }
          }
        }elsewhen(io.bus.cmd.valid){
          rsp.valid := True
          val bank = banks(address.bank)
          when(bank.active && bank.row =/= address.row){
            rsp.task := PRECHARGE_SINGLE
            when(rsp.ready){
              banks(address.bank).active := False
            }
          } elsewhen (!banks(address.bank).active) {
            rsp.task := ACTIVE
            val bank = banks(address.bank)
            bank.row := address.row
            when(rsp.ready){
              bank.active := True
            }
          } otherwise {
            io.bus.cmd.ready := rsp.ready
            rsp.task := io.bus.cmd.write ? WRITE | READ
            rsp.rowColumn := address.column.resized
          }
        }
      }
    }
  }

  val bubbleInserter = new Area{
    val cmd = frontend.rsp.m2sPipe()
    val rsp = cloneOf(cmd)
    val insertBubble = False
    rsp << cmd.haltWhen(insertBubble) //From this point, bubble should not be collapsed because of sdram timings rules

    def cycleCounter(cycleMax : BigInt,assignCheck : Boolean = false) = new Area {
      val counter = Reg(UInt(log2Up(cycleMax) bits)) init(0)
      val busy = counter =/= 0
      if(cycleMax > 1) {
        when(busy && rsp.ready) {
          counter := counter - 1
        }
      }
      def setCycles(cycles : BigInt) = {
        assert(cycles <= cycleMax)
        if (!assignCheck)
          counter := cycles - 1
        else
          when(cycles - 1 >= counter) {
            counter := cycles - 1
          }
      }
      def setTime(time : TimeNumber) = setCycles(timeToCycles(time).max(1))
    }
    def timeCounter(timeMax : TimeNumber,assignCheck : Boolean = false) = cycleCounter(timeToCycles(timeMax),assignCheck)


    val timings = new Area{
      val read   = timeCounter(t.tRCD)
      val write  = cycleCounter(timeToCycles(t.tRCD).max(CAS + 1 + 1),true)

      val banks = (0 until l.bankCount).map(i =>  new Area{
        val precharge = timeCounter(Math.max(t.tRC.toDouble, t.cWR / clkFrequancy.toDouble + t.tWR.toDouble) sec,true)
        val active    = cycleCounter(timeToCycles(t.tRC.max(t.tRFC)).max(t.cMRD),true)
      })
    }

    when(cmd.valid){
      switch(cmd.task){
        is(MODE){
          insertBubble := timings.banks(0).active.busy
          when(cmd.ready) {
            timings.banks.foreach(_.active.setCycles(t.cMRD))
          }
        }
        is(PRECHARGE_ALL){
          insertBubble := timings.banks.map(_.precharge.busy).orR
          when(cmd.ready) {
            timings.banks(0).active.setTime(t.tRP) //Only banks 0, because next instruction will be a refresh
          }
        }
        is(PRECHARGE_SINGLE){
          insertBubble := timings.banks.map(_.precharge.busy).read(cmd.bank)
          when(cmd.ready) {
            timings.banks.apply(cmd.bank)(_.active.setTime(t.tRP))
          }
        }
        is(REFRESH){
          insertBubble := timings.banks.map(_.active.busy).orR
          when(cmd.ready) {
            timings.banks.foreach(_.active.setTime(t.tRFC))
          }
        }
        is(ACTIVE){
          insertBubble := timings.banks.map(_.active.busy).read(cmd.bank)
          when(cmd.ready) {
            timings.write.setTime(t.tRCD)
            timings.read.setTime(t.tRCD)
            timings.banks.apply(cmd.bank)(_.precharge.setTime(t.tRAS))
            timings.banks.apply(cmd.bank)(_.active.setTime(t.tRC))
          }
        }
        is(READ){
          insertBubble := timings.read.busy
          when(cmd.ready){
            timings.write.setCycles(CAS + 1 + 1)  // + 1 to avoid bus colision
          }
        }
        is(WRITE){
          insertBubble := timings.write.busy
          when(cmd.ready) {
            timings.banks.apply(cmd.bank)(_.precharge.setCycles(t.cWR + timeToCycles(t.tWR)))
          }
        }
      }
    }
  }

  val chip = new Area{
    val cmd = cloneOf(bubbleInserter.rsp)
    cmd << bubbleInserter.rsp

    val sdram = Reg(io.sdram)
    (io.sdram.flatten,  sdram.flatten).zipped.foreach((ext,int) => {
      if(ext.isOutput) ext := int
    })

    val remoteCke = Bool()
    val readHistory = History(
      that       = cmd.valid && (cmd.task === READ || (if(produceRspOnWrite) cmd.task === WRITE else False)),
      range      = 0 to CAS + 2,
      when       = remoteCke,
      init       = False
    )
    val contextDelayed = Delay(cmd.context,CAS + 2,when=remoteCke)

    // === CKE GATING DISABLED ===
    // Original: val sdramCkeNext = !(readHistory.orR && !io.bus.rsp.ready)
    // This caused data shifts in multi-core configs when rsp.ready briefly
    // goes low due to BMB arbiter backpressure.
    val sdramCkeNext = True
    val sdramCkeInternal = RegNext(sdramCkeNext) init(True)
    sdram.CKE    := sdramCkeNext
    remoteCke := RegNext(sdramCkeInternal) init(True)

    when(remoteCke){
      sdram.DQ.read := io.sdram.DQ.read
      sdram.CSn  := False
      sdram.RASn := True
      sdram.CASn := True
      sdram.WEn  := True
      sdram.DQ.write := cmd.data
      sdram.DQ.writeEnable := 0
      sdram.DQM := (sdram.DQM.range -> !readHistory(if(CAS == 3) 1 else 0))

      when(cmd.valid) {
        switch(cmd.task) {
          is(PRECHARGE_ALL) {
            sdram.ADDR(10) := True
            sdram.CSn := False
            sdram.RASn := False
            sdram.CASn := True
            sdram.WEn := False
          }
          is(REFRESH) {
            sdram.CSn := False
            sdram.RASn := False
            sdram.CASn := False
            sdram.WEn := True
          }
          is(MODE) {
            sdram.ADDR := 0
            sdram.ADDR(2 downto 0) := 0
            sdram.ADDR(3) := False
            sdram.ADDR(6 downto 4) := CAS
            sdram.ADDR(8 downto 7) := 0
            sdram.ADDR(9) := False
            sdram.BA := 0
            sdram.CSn := False
            sdram.RASn := False
            sdram.CASn := False
            sdram.WEn := False
          }
          is(ACTIVE) {
            sdram.ADDR := cmd.rowColumn.asBits
            sdram.BA := cmd.bank.asBits
            sdram.CSn := False
            sdram.RASn := False
            sdram.CASn := True
            sdram.WEn := True
          }
          is(WRITE) {
            sdram.ADDR := cmd.rowColumn.asBits
            sdram.ADDR(10) := False
            sdram.DQ.writeEnable.setAll()
            sdram.DQ.write := cmd.data
            sdram.DQM := ~cmd.mask
            sdram.BA := cmd.bank.asBits
            sdram.CSn := False
            sdram.RASn := True
            sdram.CASn := False
            sdram.WEn := False
          }
          is(READ) {
            //DQM is done outside this place
            sdram.ADDR := cmd.rowColumn.asBits
            sdram.ADDR(10) := False
            sdram.BA := cmd.bank.asBits
            sdram.CSn := False
            sdram.RASn := True
            sdram.CASn := False
            sdram.WEn := True
          }
          is(PRECHARGE_SINGLE) {
            sdram.BA := cmd.bank.asBits
            sdram.ADDR(10) := False
            sdram.CSn := False
            sdram.RASn := False
            sdram.CASn := True
            sdram.WEn := False
          }
        }
      }
    }

    val backupIn = cloneOf(io.bus.rsp)
    backupIn.valid := readHistory.last & remoteCke
    backupIn.data := sdram.DQ.read
    backupIn.context := contextDelayed

    io.bus.rsp << backupIn.queueLowLatency(size = 2, latency = 0)

    cmd.ready := remoteCke
  }
}
