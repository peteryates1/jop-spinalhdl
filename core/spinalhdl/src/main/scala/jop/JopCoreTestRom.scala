package jop

import spinal.core._
import spinal.lib._
import jop.pipeline._

/**
 * JOP Core Test ROM Configurations
 *
 * Provides custom ROM initializations for testing specific microcode instructions.
 * Each configuration loads the ROM with specific instruction sequences to test
 * particular functionality.
 *
 * Instruction Format (10 bits):
 * - ir[9:6]: Instruction class (0000=POP/ALU, 0010=PUSH, 0110=BZ, 0111=BNZ, 1xxx=JMP)
 * - ir[5:0]: Operand/offset depending on instruction
 *
 * Based on DecodeStage.scala instruction decoding.
 */
object TestRomPatterns {

  /**
   * ALU Operations Test ROM
   *
   * Tests basic ALU operations: add, sub, and, or, xor
   *
   * Instructions:
   * - Addr 0-1: NOP (initialization)
   * - Addr 2: 0x000 - POP, pass-through (ir[1:0]=00, selLog=00)
   * - Addr 3: 0x001 - POP, AND (ir[1:0]=01, selLog=01)
   * - Addr 4: 0x002 - POP, OR (ir[1:0]=10, selLog=10)
   * - Addr 5: 0x003 - POP, XOR (ir[1:0]=11, selLog=11)
   * - Addr 6: 0x004 - POP, ADD (ir=0000000100, selSub=0)
   * - Addr 7: 0x005 - POP, SUB (ir=0000000101, selSub=1)
   */
  def aluOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    // Initialization NOPs
    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)

    // ALU operations (all are POP class, ir[9:6]=0000)
    setRom(2, 0, 0, 0x000)  // pass-through (selLog=00)
    setRom(3, 0, 0, 0x001)  // AND (selLog=01)
    setRom(4, 0, 0, 0x002)  // OR (selLog=10)
    setRom(5, 0, 0, 0x003)  // XOR (selLog=11)
    setRom(6, 0, 0, 0x004)  // ADD (selSub=0, selAmux=0)
    setRom(7, 0, 0, 0x005)  // SUB (selSub=1, selAmux=0) - FIXED!

    // Fill rest with NOPs
    for (i <- 8 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Shift Operations Test ROM
   *
   * Tests shift operations: USHR, SHL, SHR
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x01C - USHR (ir[9:2]=00000111, ir[1:0]=00)
   * - Addr 3: 0x01D - SHL (ir[9:2]=00000111, ir[1:0]=01)
   * - Addr 4: 0x01E - SHR (ir[9:2]=00000111, ir[1:0]=10)
   */
  def shiftOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x01C)  // USHR
    setRom(3, 0, 0, 0x01D)  // SHL
    setRom(4, 0, 0, 0x01E)  // SHR

    for (i <- 5 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Load/Store Operations Test ROM
   *
   * Tests memory operations: ldm, stm, ldi
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x0A0 - ldm (ir[9:5]=00101, load from memory)
   * - Addr 3: 0x020 - stm (ir[9:5]=00001, store to memory)
   * - Addr 4: 0x0C0 - ldi (ir[9:5]=00110, load immediate)
   */
  def loadStoreRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x0A0)  // ldm
    setRom(3, 0, 0, 0x020)  // stm
    setRom(4, 0, 0, 0x0C0)  // ldi

    for (i <- 5 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Branch Operations Test ROM
   *
   * Tests conditional branches: BZ, BNZ
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x180 - BZ (ir[9:6]=0110, branch if zero)
   * - Addr 3: 0x1C0 - BNZ (ir[9:6]=0111, branch if not zero)
   */
  def branchOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x180)  // BZ
    setRom(3, 0, 0, 0x1C0)  // BNZ

    for (i <- 4 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Stack Operations Test ROM
   *
   * Tests stack manipulation operations: dup, nop, wait
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x0F8 - dup (ir=0011111000, copies A to B without changing A)
   * - Addr 3: 0x100 - nop (ir=0100000000, no operation)
   * - Addr 4: 0x101 - wait (ir=0100000001, stalls until mem_busy=0)
   */
  def stackOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x0F8)  // dup
    setRom(3, 0, 0, 0x100)  // nop
    setRom(4, 0, 0, 0x101)  // wait

    for (i <- 5 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Register Store Operations Test ROM
   *
   * Tests register store operations: stvp, stjpc, star, stsp
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x018 - stvp (store A to VP register)
   * - Addr 3: 0x019 - stjpc (store A to JPC register)
   * - Addr 4: 0x01A - star (store A to AR register)
   * - Addr 5: 0x01B - stsp (store A to SP register)
   */
  def regStoreOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x018)  // stvp
    setRom(3, 0, 0, 0x019)  // stjpc
    setRom(4, 0, 0, 0x01A)  // star
    setRom(5, 0, 0, 0x01B)  // stsp

    for (i <- 6 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Register Load Operations Test ROM
   *
   * Tests register load operations: ldsp, ldvp, ldjpc
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x0F0 - ldsp (load SP to A)
   * - Addr 3: 0x0F1 - ldvp (load VP to A)
   * - Addr 4: 0x0F2 - ldjpc (load JPC to A)
   */
  def regLoadOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x0F0)  // ldsp
    setRom(3, 0, 0, 0x0F1)  // ldvp
    setRom(4, 0, 0, 0x0F2)  // ldjpc

    for (i <- 5 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Store to Stack RAM Operations Test ROM
   *
   * Tests direct store operations: st0, st1, st2, st3, st, stmi
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x010 - st0 (store to RAM[0])
   * - Addr 3: 0x011 - st1 (store to RAM[1])
   * - Addr 4: 0x012 - st2 (store to RAM[2])
   * - Addr 5: 0x013 - st3 (store to RAM[3])
   * - Addr 6: 0x014 - st (store to RAM[VP+idx])
   * - Addr 7: 0x015 - stmi (store mem indirect)
   */
  def storeOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x010)  // st0
    setRom(3, 0, 0, 0x011)  // st1
    setRom(4, 0, 0, 0x012)  // st2
    setRom(5, 0, 0, 0x013)  // st3
    setRom(6, 0, 0, 0x014)  // st
    setRom(7, 0, 0, 0x015)  // stmi

    for (i <- 8 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Load from Stack RAM Operations Test ROM
   *
   * Tests direct load operations: ld0, ld1, ld2, ld3, ld, ldmi
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x0E8 - ld0 (load from RAM[0])
   * - Addr 3: 0x0E9 - ld1 (load from RAM[1])
   * - Addr 4: 0x0EA - ld2 (load from RAM[2])
   * - Addr 5: 0x0EB - ld3 (load from RAM[3])
   * - Addr 6: 0x0EC - ld (load from RAM[VP+idx])
   * - Addr 7: 0x0ED - ldmi (load mem indirect)
   */
  def loadOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x0E8)  // ld0
    setRom(3, 0, 0, 0x0E9)  // ld1
    setRom(4, 0, 0, 0x0EA)  // ld2
    setRom(5, 0, 0, 0x0EB)  // ld3
    setRom(6, 0, 0, 0x0EC)  // ld
    setRom(7, 0, 0, 0x0ED)  // ldmi

    for (i <- 8 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Load Operand Operations Test ROM
   *
   * Tests operand load operations: ld_opd_8u, ld_opd_8s, ld_opd_16u, ld_opd_16s
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x0F4 - ld_opd_8u (load 8-bit unsigned operand)
   * - Addr 3: 0x0F5 - ld_opd_8s (load 8-bit signed operand)
   * - Addr 4: 0x0F6 - ld_opd_16u (load 16-bit unsigned operand)
   * - Addr 5: 0x0F7 - ld_opd_16s (load 16-bit signed operand)
   */
  def loadOpdOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x0F4)  // ld_opd_8u
    setRom(3, 0, 0, 0x0F5)  // ld_opd_8s
    setRom(4, 0, 0, 0x0F6)  // ld_opd_16u
    setRom(5, 0, 0, 0x0F7)  // ld_opd_16s

    for (i <- 6 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * MMU Operations Test ROM
   *
   * Tests MMU/memory management operations: stmul, stmwa, stmra, stmwd
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x040 - stmul (store multiplier)
   * - Addr 3: 0x041 - stmwa (store memory write address)
   * - Addr 4: 0x042 - stmra (store memory read address)
   * - Addr 5: 0x043 - stmwd (store memory write data)
   */
  def mmuOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x040)  // stmul
    setRom(3, 0, 0, 0x041)  // stmwa
    setRom(4, 0, 0, 0x042)  // stmra
    setRom(5, 0, 0, 0x043)  // stmwd

    for (i <- 6 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Multiplier Operations Test ROM
   *
   * Tests the multiplier integration through stmul/ldmul operations.
   *
   * Multiplication sequence:
   * 1. Load operand 1 (16-bit unsigned immediate)
   * 2. Load operand 2 (16-bit unsigned immediate)
   * 3. Execute stmul - starts multiplication (ain=TOS, bin=NOS)
   * 4. Wait 17 cycles for bit-serial multiplier
   * 5. Execute ldmul - reads result
   *
   * Test cases (embedded in ROM as operand values):
   * - 5 × 7 = 35
   * - 12 × 8 = 96
   * - 100 × 200 = 20000
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2-4: Load operand 1 (ld_opd_16u takes 3 ROM addresses)
   * - Addr 5-7: Load operand 2
   * - Addr 8: stmul - Start multiplication
   * - Addr 9-25: NOP (wait 17 cycles for multiplier)
   * - Addr 26: ldmul - Read result
   */
  def multiplierOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)  // NOP
    setRom(1, 0, 0, 0x000)  // NOP

    // Test case 1: 5 × 7 = 35
    // Load first operand (5) - ld_opd_16u = 0x2C0 + high byte, low byte
    setRom(2, 0, 0, 0x2C0)  // ld_opd_16u instruction
    setRom(3, 0, 0, 0x000)  // high byte = 0
    setRom(4, 0, 0, 0x005)  // low byte = 5

    // Load second operand (7)
    setRom(5, 0, 0, 0x2C0)  // ld_opd_16u instruction
    setRom(6, 0, 0, 0x000)  // high byte = 0
    setRom(7, 0, 0, 0x007)  // low byte = 7

    // Start multiplication
    setRom(8, 0, 0, 0x040)  // stmul

    // Wait 17 cycles for multiplier (addr 9-25)
    for (i <- 9 to 25) {
      setRom(i, 0, 0, 0x100)  // NOP
    }

    // Read multiplication result
    setRom(26, 0, 0, 0x3C1)  // ldmul

    // Fill rest with NOPs
    for (i <- 27 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * Control Operations Test ROM
   *
   * Tests control flow operations: jbr
   *
   * Instructions:
   * - Addr 0-1: NOP
   * - Addr 2: 0x102 - jbr (jump to bytecode ROM address)
   */
  def controlOpsRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    setRom(0, 0, 0, 0x000)
    setRom(1, 0, 0, 0x000)
    setRom(2, 0, 0, 0x102)  // jbr

    for (i <- 3 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }

  /**
   * JVM Instruction Sequences Test ROM (Phase 3.1)
   *
   * Tests realistic JVM bytecode execution patterns using microcode sequences.
   * Each sequence represents a complete JVM operation: load → operate → store.
   *
   * Sequences implemented:
   * 1. IADD: iload_0; iload_1; iadd; istore_2 (addrs 10-15)
   * 2. ISUB: iload_0; iload_1; isub; istore_2 (addrs 20-25)
   * 3. IMUL: iload_0; iload_1; imul; istore_2 (addrs 30-50)
   * 4. IAND: iload_0; iload_1; iand; istore_2 (addrs 60-65)
   * 5. IOR: iload_0; iload_1; ior; istore_2 (addrs 70-75)
   * 6. IXOR: iload_0; iload_1; ixor; istore_2 (addrs 80-85)
   * 7. ISHL: iload_0; iload_1; ishl; istore_2 (addrs 90-95)
   * 8. ISHR: iload_0; iload_1; ishr; istore_2 (addrs 100-105)
   * 9. IUSHR: iload_0; iload_1; iushr; istore_2 (addrs 110-115)
   * 10. DUP_IADD: iload_0; dup; iadd; istore_1 (addrs 120-125)
   * 11. BIPUSH_STORE_LOAD_ADD: bipush 5; istore_0; iload_0; iload_0; iadd; istore_1 (addrs 130-145)
   * 12. COMPLEX_STACK: dup, swap, add sequence (addrs 150-160)
   *
   * Test setup (initialized in local variables before tests):
   * - var[0] = 10
   * - var[1] = 3
   * - var[2] = 0 (result)
   */
  def jvmSequencesRom(romDepth: Int, romWidth: Int): Seq[BigInt] = {
    val rom = Array.fill(romDepth)(BigInt(0))

    def setRom(addr: Int, jf: Int, jod: Int, instr: Int): Unit = {
      val value = (jf << (romWidth - 2)) | (jod << (romWidth - 3)) | instr
      rom(addr) = BigInt(value)
    }

    // ========================================================================
    // Initialization: Set up test variables (addrs 0-9)
    // ========================================================================
    setRom(0, 0, 0, 0x000)  // NOP
    setRom(1, 0, 0, 0x000)  // NOP

    // Load immediate 10 into var[0] (vp+0)
    setRom(2, 0, 0, 0x2C0)  // ld_opd_16u
    setRom(3, 0, 0, 0x000)  // high byte = 0
    setRom(4, 0, 0, 0x00A)  // low byte = 10
    setRom(5, 0, 0, 0x000)  // pop (remove from stack)
    setRom(6, 0, 0, 0x010)  // st0 (store to var[0])

    // Load immediate 3 into var[1] (vp+1)
    setRom(7, 0, 0, 0x2C0)  // ld_opd_16u
    setRom(8, 0, 0, 0x000)  // high byte = 0
    setRom(9, 0, 0, 0x003)  // low byte = 3
    setRom(10, 0, 0, 0x000)  // pop
    setRom(11, 0, 0, 0x011)  // st1 (store to var[1])

    // ========================================================================
    // Sequence 1: IADD - iload_0; iload_1; iadd; istore_2 (addrs 20-23)
    // Expected: var[2] = 10 + 3 = 13
    // ========================================================================
    setRom(20, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(21, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(22, 0, 0, 0x004)  // add (10 + 3 = 13)
    setRom(23, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 2: ISUB - iload_0; iload_1; isub; istore_2 (addrs 30-33)
    // Expected: var[2] = 10 - 3 = 7
    // ========================================================================
    setRom(30, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(31, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(32, 0, 0, 0x005)  // sub (10 - 3 = 7)
    setRom(33, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 3: IMUL - iload_0; iload_1; imul; istore_2 (addrs 40-61)
    // Expected: var[2] = 10 * 3 = 30
    // ========================================================================
    setRom(40, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(41, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(42, 0, 0, 0x040)  // stmul (start multiplication)

    // Wait 17 cycles for multiplier (addrs 43-59)
    for (i <- 43 to 59) {
      setRom(i, 0, 0, 0x100)  // NOP
    }

    setRom(60, 0, 0, 0x3C1)  // ldmul (read result = 30)
    setRom(61, 0, 0, 0x000)  // pop (remove NOS from stmul)
    setRom(62, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 4: IAND - iload_0; iload_1; iand; istore_2 (addrs 70-73)
    // Expected: var[2] = 10 & 3 = 2 (0b1010 & 0b0011 = 0b0010)
    // ========================================================================
    setRom(70, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(71, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(72, 0, 0, 0x001)  // and (10 & 3 = 2)
    setRom(73, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 5: IOR - iload_0; iload_1; ior; istore_2 (addrs 80-83)
    // Expected: var[2] = 10 | 3 = 11 (0b1010 | 0b0011 = 0b1011)
    // ========================================================================
    setRom(80, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(81, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(82, 0, 0, 0x002)  // or (10 | 3 = 11)
    setRom(83, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 6: IXOR - iload_0; iload_1; ixor; istore_2 (addrs 90-93)
    // Expected: var[2] = 10 ^ 3 = 9 (0b1010 ^ 0b0011 = 0b1001)
    // ========================================================================
    setRom(90, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(91, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(92, 0, 0, 0x003)  // xor (10 ^ 3 = 9)
    setRom(93, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 7: ISHL - iload_0; iload_1; ishl; istore_2 (addrs 100-103)
    // Expected: var[2] = 10 << 3 = 80 (shift left 3 positions)
    // ========================================================================
    setRom(100, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(101, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(102, 0, 0, 0x01D)  // shl (10 << 3 = 80)
    setRom(103, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 8: ISHR - iload_0; iload_1; ishr; istore_2 (addrs 110-113)
    // Expected: var[2] = 10 >> 3 = 1 (arithmetic shift right 3 positions)
    // ========================================================================
    setRom(110, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(111, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(112, 0, 0, 0x01E)  // shr (10 >> 3 = 1)
    setRom(113, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 9: IUSHR - iload_0; iload_1; iushr; istore_2 (addrs 120-123)
    // Expected: var[2] = 10 >>> 3 = 1 (logical shift right 3 positions)
    // ========================================================================
    setRom(120, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(121, 0, 0, 0x3A1)  // ld1 (load var[1] = 3)
    setRom(122, 0, 0, 0x01C)  // ushr (10 >>> 3 = 1)
    setRom(123, 0, 0, 0x012)  // st2 (store to var[2])

    // ========================================================================
    // Sequence 10: DUP_IADD - iload_0; dup; iadd; istore_1 (addrs 130-133)
    // Expected: var[1] = 10 + 10 = 20 (duplicate and add to self)
    // ========================================================================
    setRom(130, 0, 0, 0x3A0)  // ld0 (load var[0] = 10)
    setRom(131, 0, 0, 0x3F8)  // dup (duplicate TOS)
    setRom(132, 0, 0, 0x004)  // add (10 + 10 = 20)
    setRom(133, 0, 0, 0x011)  // st1 (store to var[1])

    // ========================================================================
    // Sequence 11: BIPUSH_SEQUENCE - bipush 5; istore_0; iload_0; iload_0; iadd; istore_1
    // (addrs 140-146)
    // Expected: Load 5, store to var[0], load twice, add, store to var[1]
    // var[0] = 5, var[1] = 5 + 5 = 10
    // ========================================================================
    setRom(140, 0, 0, 0x3F4)  // ld_opd_8u (load 8-bit unsigned immediate)
    setRom(141, 0, 0, 0x005)  // operand = 5
    setRom(142, 0, 0, 0x010)  // st0 (store 5 to var[0])
    setRom(143, 0, 0, 0x3A0)  // ld0 (load var[0] = 5)
    setRom(144, 0, 0, 0x3A0)  // ld0 (load var[0] = 5 again)
    setRom(145, 0, 0, 0x004)  // add (5 + 5 = 10)
    setRom(146, 0, 0, 0x011)  // st1 (store to var[1])

    // ========================================================================
    // Sequence 12: COMPLEX_STACK - Load 2 values, dup, swap, operations (addrs 150-158)
    // Tests: ld_opd_8u 7; ld_opd_8u 4; dup; (stack: 7, 4, 4)
    // Expected: Complex stack manipulation pattern
    // ========================================================================
    setRom(150, 0, 0, 0x3F4)  // ld_opd_8u
    setRom(151, 0, 0, 0x007)  // operand = 7
    setRom(152, 0, 0, 0x3F4)  // ld_opd_8u
    setRom(153, 0, 0, 0x004)  // operand = 4
    setRom(154, 0, 0, 0x3F8)  // dup (stack: 7, 4, 4)
    setRom(155, 0, 0, 0x004)  // add (stack: 7, 8)
    setRom(156, 0, 0, 0x004)  // add (stack: 15)
    setRom(157, 0, 0, 0x012)  // st2 (store to var[2])

    // Fill rest with NOPs
    for (i <- 158 until romDepth) {
      setRom(i, 0, 0, 0x000)
    }

    rom.toSeq
  }
}

/**
 * JOP Core with Custom Test ROM
 *
 * Allows specifying custom ROM contents for testing specific microcode instructions.
 */
class JopCoreTestRom(
  config: JopCoreConfig = JopCoreConfig(),
  romPattern: Seq[BigInt]
) extends Component {
  val io = JopCoreIO(config)

  // Create fetch stage with custom ROM
  val fetchStage = new FetchStage(
    config = config.fetchConfig,
    romInit = Some(romPattern)
  )

  val decodeStage = new DecodeStage(config.decodeConfig)
  val stackStage = new StackStage(config.stackConfig)
  val multiplier = jop.core.Mul(config.dataWidth)

  // Same connections as JopCore
  fetchStage.io.br := decodeStage.io.br
  fetchStage.io.jmp := decodeStage.io.jmp
  fetchStage.io.bsy := io.memBusy
  fetchStage.io.jpaddr := U(0, config.pcWidth bits)

  io.jfetch := fetchStage.io.nxt
  io.jopdfetch := fetchStage.io.opd

  decodeStage.io.instr := fetchStage.io.dout
  decodeStage.io.zf := stackStage.io.zf
  decodeStage.io.nf := stackStage.io.nf
  decodeStage.io.eq := stackStage.io.eq
  decodeStage.io.lt := stackStage.io.lt
  decodeStage.io.bcopd := io.operand

  stackStage.io.din := io.memDataIn
  stackStage.io.dirAddr := decodeStage.io.dirAddr.asUInt
  stackStage.io.opd := io.operand
  stackStage.io.jpc := io.jpc

  stackStage.io.selSub := decodeStage.io.selSub
  stackStage.io.selAmux := decodeStage.io.selAmux
  stackStage.io.enaA := decodeStage.io.enaA
  stackStage.io.selBmux := decodeStage.io.selBmux
  stackStage.io.selLog := decodeStage.io.selLog
  stackStage.io.selShf := decodeStage.io.selShf
  stackStage.io.selLmux := decodeStage.io.selLmux
  stackStage.io.selImux := decodeStage.io.selImux
  stackStage.io.selRmux := decodeStage.io.selRmux
  stackStage.io.selSmux := decodeStage.io.selSmux
  stackStage.io.selMmux := decodeStage.io.selMmux
  stackStage.io.selRda := decodeStage.io.selRda
  stackStage.io.selWra := decodeStage.io.selWra
  stackStage.io.wrEna := decodeStage.io.wrEna
  stackStage.io.enaB := decodeStage.io.enaB
  stackStage.io.enaVp := decodeStage.io.enaVp
  stackStage.io.enaAr := decodeStage.io.enaAr

  io.aout := stackStage.io.aout
  io.bout := stackStage.io.bout
  io.spOv := stackStage.io.spOv

  // Multiplier connections
  multiplier.io.ain := stackStage.io.aout.asUInt
  multiplier.io.bin := stackStage.io.bout.asUInt
  multiplier.io.wr := decodeStage.io.mulWr
  io.mulDout := multiplier.io.dout

  fetchStage.setName("fetch")
  decodeStage.setName("decode")
  stackStage.setName("stack")
  multiplier.setName("mul")
}

/**
 * Generate testbench with ALU operations ROM for CocoTB testing
 */
object JopCoreAluTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreAluTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.aluOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreAluTestTb)
  println("JopCoreAluTestTb VHDL generated in generated/JopCoreAluTestTb.vhd")
}

/**
 * Generate testbench with Shift operations ROM for CocoTB testing
 */
object JopCoreShiftTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreShiftTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.shiftOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreShiftTestTb)
  println("JopCoreShiftTestTb VHDL generated in generated/JopCoreShiftTestTb.vhd")
}

/**
 * Generate testbench with Load/Store operations ROM for CocoTB testing
 */
object JopCoreLoadStoreTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreLoadStoreTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.loadStoreRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreLoadStoreTestTb)
  println("JopCoreLoadStoreTestTb VHDL generated in generated/JopCoreLoadStoreTestTb.vhd")
}

/**
 * Generate testbench with Branch operations ROM for CocoTB testing
 */
object JopCoreBranchTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreBranchTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.branchOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreBranchTestTb)
  println("JopCoreBranchTestTb VHDL generated in generated/JopCoreBranchTestTb.vhd")
}

/**
 * Generate testbench with Stack operations ROM for CocoTB testing
 */
object JopCoreStackTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreStackTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.stackOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreStackTestTb)
  println("JopCoreStackTestTb VHDL generated in generated/JopCoreStackTestTb.vhd")
}

/**
 * Generate testbench with Register Store operations ROM for CocoTB testing
 */
object JopCoreRegStoreTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreRegStoreTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.regStoreOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreRegStoreTestTb)
  println("JopCoreRegStoreTestTb VHDL generated in generated/JopCoreRegStoreTestTb.vhd")
}

/**
 * Generate testbench with Register Load operations ROM for CocoTB testing
 */
object JopCoreRegLoadTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreRegLoadTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.regLoadOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreRegLoadTestTb)
  println("JopCoreRegLoadTestTb VHDL generated in generated/JopCoreRegLoadTestTb.vhd")
}

/**
 * Generate testbench with Store operations ROM for CocoTB testing
 */
object JopCoreStoreTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreStoreTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.storeOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreStoreTestTb)
  println("JopCoreStoreTestTb VHDL generated in generated/JopCoreStoreTestTb.vhd")
}

/**
 * Generate testbench with Load operations ROM for CocoTB testing
 */
object JopCoreLoadTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreLoadTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.loadOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreLoadTestTb)
  println("JopCoreLoadTestTb VHDL generated in generated/JopCoreLoadTestTb.vhd")
}

/**
 * Generate testbench with Load Operand operations ROM for CocoTB testing
 */
object JopCoreLoadOpdTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreLoadOpdTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.loadOpdOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreLoadOpdTestTb)
  println("JopCoreLoadOpdTestTb VHDL generated in generated/JopCoreLoadOpdTestTb.vhd")
}

/**
 * Generate testbench with MMU operations ROM for CocoTB testing
 */
object JopCoreMmuTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreMmuTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.mmuOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreMmuTestTb)
  println("JopCoreMmuTestTb VHDL generated in generated/JopCoreMmuTestTb.vhd")
}

/**
 * Generate testbench with Control operations ROM for CocoTB testing
 */
object JopCoreControlTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreControlTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.controlOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
    }
  }

  config.generateVhdl(new JopCoreControlTestTb)
  println("JopCoreControlTestTb VHDL generated in generated/JopCoreControlTestTb.vhd")
}

/**
 * JopCore Multiplier Test Testbench VHDL Generator
 *
 * Generates a VHDL testbench for testing multiplier integration through
 * stmul/ldmul microcode operations.
 *
 * Test sequence:
 * 1. Load two 16-bit operands using ld_opd_16u
 * 2. Execute stmul to start multiplication
 * 3. Wait 17 cycles for bit-serial multiplier
 * 4. Execute ldmul to read result
 *
 * Usage: sbt "runMain jop.JopCoreMultiplierTestTbVhdl"
 */
object JopCoreMultiplierTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreMultiplierTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()
    val mul_dout = out UInt(32 bits)

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.multiplierOpsRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
      mul_dout := core.io.mulDout
    }
  }

  config.generateVhdl(new JopCoreMultiplierTestTb)
  println("JopCoreMultiplierTestTb VHDL generated in generated/JopCoreMultiplierTestTb.vhd")
}

/**
 * JopCore JVM Sequences Test Testbench VHDL Generator (Phase 3.1)
 *
 * Generates a VHDL testbench for testing realistic JVM bytecode execution patterns
 * using microcode sequences. Each test sequence represents a complete JVM operation:
 * load → operate → store.
 *
 * Test sequences:
 * 1. IADD: iload_0; iload_1; iadd; istore_2
 * 2. ISUB: iload_0; iload_1; isub; istore_2
 * 3. IMUL: iload_0; iload_1; imul; istore_2
 * 4. IAND: iload_0; iload_1; iand; istore_2
 * 5. IOR: iload_0; iload_1; ior; istore_2
 * 6. IXOR: iload_0; iload_1; ixor; istore_2
 * 7. ISHL: iload_0; iload_1; ishl; istore_2
 * 8. ISHR: iload_0; iload_1; ishr; istore_2
 * 9. IUSHR: iload_0; iload_1; iushr; istore_2
 * 10. DUP_IADD: iload_0; dup; iadd; istore_1
 * 11. BIPUSH_SEQUENCE: bipush 5; istore_0; iload_0; iload_0; iadd; istore_1
 * 12. COMPLEX_STACK: Multi-value stack manipulation with dup and add
 *
 * This tests multi-instruction pipeline interactions, stack management,
 * register state transitions, and complex control flow.
 *
 * Usage: sbt "runMain jop.JopCoreJvmSequencesTestTbVhdl"
 */
object JopCoreJvmSequencesTestTbVhdl extends App {
  val config = SpinalConfig(
    targetDirectory = "generated",
    defaultClockDomainFrequency = FixedFrequency(50 MHz)
  )

  class JopCoreJvmSequencesTestTb extends Component {
    noIoPrefix()

    val clk = in Bool()
    val reset = in Bool()
    val mem_data_in = in Bits(32 bits)
    val mem_busy = in Bool()
    val operand = in Bits(16 bits)
    val jpc = in UInt(11 bits)
    val aout = out Bits(32 bits)
    val bout = out Bits(32 bits)
    val sp_ov = out Bool()
    val jfetch = out Bool()
    val jopdfetch = out Bool()
    val mul_dout = out UInt(32 bits)

    val coreClockDomain = ClockDomain(
      clock = clk,
      reset = reset,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = ASYNC,
        resetActiveLevel = HIGH
      )
    )

    val coreArea = new ClockingArea(coreClockDomain) {
      val core = new JopCoreTestRom(
        config = JopCoreConfig(),
        romPattern = TestRomPatterns.jvmSequencesRom(2048, 12)
      )

      core.io.memDataIn := mem_data_in
      core.io.memBusy := mem_busy
      core.io.operand := operand
      core.io.jpc := jpc

      aout := core.io.aout
      bout := core.io.bout
      sp_ov := core.io.spOv
      jfetch := core.io.jfetch
      jopdfetch := core.io.jopdfetch
      mul_dout := core.io.mulDout
    }
  }

  config.generateVhdl(new JopCoreJvmSequencesTestTb)
  println("JopCoreJvmSequencesTestTb VHDL generated in generated/JopCoreJvmSequencesTestTb.vhd")
}
