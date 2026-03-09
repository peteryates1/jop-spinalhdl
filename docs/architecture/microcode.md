# Microcode Operations

Reference originally extracted from [JOP Reference Handbook](https://www.jopdesign.com/doc/handbook.pdf) and [Instruction.java](../../java/tools/src/com/jopdesign/tools/Instruction.java). Opcodes and dataflow use ASCII; arrows are written as `->`.

| Microcode | Operation | Opcode | Dataflow | JVM equivalent | Description |
| --- | --- | --- | --- | --- | --- |
| add | Add int | `0000000100` | `A+B -> A`<br>`stack[sp] -> B`<br>`sp-1 -> sp` | iadd | Add the two top elements from the stack and push back the result onto the operand stack. |
| and | Boolean AND int | `0000000001` | `A & B -> A`<br>`stack[sp] -> B`<br>`sp-1 -> sp` | iand | Build the bitwise AND (conjunction) of the two top elements of the stack and push back the result onto the operand stack. |
| atmend | End atomic arbiter operation | `0100010011` | `-` | -- | end atomic arbiter operation |
| atmstart | Start atomic arbiter operation | `0100010010` | `-` | -- | start atomic arbiter operation |
| bnz | Branch if value is not zero | `0111nnnnnn` | `if A != 0 then pc + sign_ext(nnnnnn) + 1 -> pc`<br>`B->A`<br>`stack[sp] -> B`<br>`sp - 1 -> sp` | -- | If the top value from the operand stack is not zero a microcode branch is taken. The value is popped from the operand stack. Due to a pipeline delay the zero flag is delayed one cycle i.e. the value from the last but one instruction is taken. The branch is followed by two branch delay slots. The branch offset is the signed 6-bit immediate. |
| bz | Branch if value is zero | `0110nnnnnn` | `if A = 0 then pc + sign_ext(nnnnnn) + 1 -> pc`<br>`B->A`<br>`stack[sp] -> B`<br>`sp - 1 -> sp` | -- | If the top value from the operand stack is zero a microcode branch is taken. The value is popped from the operand stack. Due to a pipeline delay the zero flag is delayed one cycle i.e. the value from the last but one instruction is taken. The branch is followed by two branch delay slots. The branch offset is the signed 6-bit immediate. |
| cinval | Invalidate data cache | `0100010001` | `-` | -- | invalidate data cache |
| dup | Duplicate the top operand stack value | `0011111000` | `A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | dup | Duplicate the top value on the operand stack and push it onto the operand stack. |
| jbr | Conditional bytecode branch and goto | `0100000010` | `-` | ifnull, Ifnonnull, Ifeq, Ifne, Iflt, Ifge, Ifgt, Ifle, if_acmpeq, if_acmpne, if_icmpeq, if_icmpne, if_icmplt, if_icmpge, if_icmpgt, if_icmple, goto | Execute a bytecode branch or goto. The branch condition and offset are calculated in the bytecode fetch unit. Arguments must be removed with ```pop``` instructions in the following microcode instructions. |
| jmp | Unconditional jump | `1nnnnnnnnn` | `pc + sign_ext(nnnnnnnnn) + 1 -> pc` | -- | Microcode branch. The branch offset is the signed 9-bit immediate. |
| ld | Load 32-bit word from local variable | `0011101100` | `stack[vp+opd] to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | aload, Iload, fload | The local variable at position $opd$ is pushed onto the operand stack. $opd$ is taken from the bytecode instruction stream. |
| ld[n] | Load 32-bit word from local variable | `00111010nn` | `stack[vp+n] to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | aload_n, iload_n, fload_n | The local variable at position $n$ is pushed onto the operand stack. |
| ld_opd_16s | Load 16-bit bytecode operand signed | `0011110111` | `opd_{16} to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | (sipush) | A 16-bit word from the bytecode stream is sign-extended to an ```int``` and pushed onto the operand stack. |
| ld_opd_16u | Load 16-bit bytecode operand unsigned | `0011110110` | `opd_{16} to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | -- | A 16-bit word from the bytecode stream is pushed as ```int``` onto the operand stack. |
| ld_opd_8s | Load 8-bit bytecode operand signed | `0011110101` | `opd to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | (bipush) | A single byte from the bytecode stream is sign-extended to an ```int``` and pushed onto the operand stack. |
| ld_opd_8u | Load 8-bit bytecode operand unsigned | `0011110100` | `opd to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | -- | A single byte from the bytecode stream is pushed as ```int``` onto the operand stack. |
| ldbcstart | Load method start | `0011100010` | `bcstart to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`sp+1 to sp` | -- | The method start address in the method cache is pushed onto the operand stack. |
| ldi | Load from local memory | `00110nnnnn` | `stack[n+32] to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`sp+1 to sp` | -- | The value from the local memory (stack) at position $n+32$ is pushed onto the operand stack. These 32 memory destinations represent microcode constants. |
| ldjpc | Load Java program counter | `0011110010` | `jpc to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | -- | The Java program counter is pushed onto the operand stack. |
| ldm | Load from local memory | `00101nnnnn` | `stack[n] to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | -- | The value from the local memory (stack) at position $n$ is pushed onto the operand stack. These 32 memory destinations represent microcode local variables. |
| ldmi | Load from local memory indirect | `0011101101` | `stack[ar] to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | -- | The value from the local memory (stack) at position ar is pushed onto the operand stack. |
| ldmrd | Load memory read data | `0011100000` | `memrdd to A`<br>`A to B`<br>`B to stack`<br>`[sp+1]+1 to sp` | -- | The value from the memory system after a memory read is pushed onto the operand stack. This operation is usually preceded by two ```wait``` instructions. |
| ldop | Load operand from compute unit | `0011100001` | `cu.dout to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`sp+1 to sp` | -- | Pops the top value from the compute unit's internal result stack and pushes it onto the JOP operand stack. For multi-word results (64-bit long/double), use multiple `ldop` instructions — the CU pushes hi word first, lo word second, so `ldop` returns hi then lo. Replaces the former `ldmul`/`ldmulh` instructions. Din mux sel=01. Encoding `0x0E1`. |
| ldsp | Load stack pointer | `0011110000` | `sp to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | -- | The stack pointer is pushed onto the operand stack. |
| ldvp | Load variable pointer | `0011110001` | `vp to A`<br>`A to B`<br>`B to stack[sp+1]`<br>`Sp+1 to sp` | -- | The variable pointer is pushed onto the operand stack. |
| nop | Do nothing | `0100000000` | `-` | nop | The famous no operation instruction. |
| or | Boolean OR int | `0000000010` | `A \| B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | ior | Build the bitwise inclusive OR (disjunction) of the two top elements of the stack and push back the result onto the operand stack. |
| pop | Pop the top operand stack value | `0000000000` | `B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | pop | Pop the top value from the operand stack. |
| shl | Shift left int | `0000011101` | `B << A to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | ishl | The values are popped from the operand stack. An ```int``` result is calculated by shifting the TOS-1 value left by $s$ position where $s$ is the value of the low 5 bits of the TOS. The result is pushed onto the operand stack. |
| shr | Arithmetic shift rigth int | `0000011110` | `B >> A to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | ishr | The values are popped from the operand stack. An ```int``` result is calculated by shifting the TOS-1 value rigth by $s$ position with sign extension where $s$ is the value of the low 5 bits of the TOS. The result is pushed onto the operand stack. |
| st | Store 32-bit word into local variable | `0000010100` | `A to stack[vp+opd]`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | astore, istore, fstore | The value on the top of the operand stack is popped and stored in the local variable at position $opd$. $opd$ is taken from the bytecode instruction stream. |
| st[n] | Store 32-bit word into local variable | `00000100nn` | `A to stack[vp+n]`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | astore_n, istore_n, fstore_n | The value on the top of the operand stack is popped and stored in the local variable at position $n$. |
| stald | Start array load | `0001000100` | `A to memidx`<br>`B to A`<br>`B to memptr`<br>`stack[sp] to B`<br>`sp-1 to sp` | xaload | The top value from the stack is stored as array index the next as reference in the memory subsystem. This operation starts the concurrent array load. The processor can continue with other operations. The ```wait``` instruction stalls the processor till the read access is finished. A null pointer or out of bounds exception is generated by the memory subsystem and thrown at the next bytecode fetch. |
| star | Store adress register | `0000011010` | `A to ar`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The value on the top of the operand stack is popped and stored in the address register (```ar```). Due to a pipeline delay the register is valid on cycle later for usage by ```ldmi``` and ```stmi```. |
| stast | Start array store | `0001000101` | `A to memval`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp`<br>`next cycle`<br>`A to memidx`<br>`B to A`<br>`B to memptr`<br>`stack[sp] to B`<br>`sp-1 to sp` | xastore | In the first cycle the top value from the stack is stored as value into the memory subsystem. A microcode ```pop``` has to follow. In the second cycle the top value from the stack is stored as array index the next as reference in the memory subsystem. This operation starts the concurrent array store. The processor can continue with other operations. The ```wait``` instruction stalls the processor till the write access is finished. A null pointer or out of bounds exception is generated by the memory subsystem and thrown at the next bytecode fetch. |
| stbcrd | Start bytecode read | `0001001001` | `A to membcr`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the stack is stored as address and length of a method in the memory subsystem. This operation starts the memory transfer from the main memory to the bytecode cache (DMA). The processor can continue with other operations. The ```wait``` instruction stalls the processor till the transfer has finished. No other memory accesses are allowed during the bytecode read. |
| stcp | Start copy step | `0001001000` | `A to memidx`<br>`B to memsrc`<br>`stack[sp] to B`<br>`sp-1 to sp`<br>`next cycle`<br>`B to memdest`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the stack is stored as index for a ```stgf``` and ```stgf```. One of those two instructions has to follow. |
| stgf | Start getfield | `0001000110` | `A to memptr`<br>`B to A`<br>`opd_{16} to memidx`<br>`stack[sp] to B`<br>`sp-1 to sp` | getfield | The top value from the stack is stored as reference in the memory subsystem. This operation starts the concurrent getfield. The processor can continue with other operations. The ```wait``` instruction stalls the processor till the read access is finished. A null pointer exception is generated by the memory subsystem and thrown at the next bytecode fetch. |
| stgs | Start getstatic | `0100010000` | `opd_{16} to memptr` | getstatic | The static field address is in the bytecode operand (index). |
| sthw | Start hardware compute unit | `0101nnnnnn` | `cu.start(nnnnnn)` | -- | Starts the compute unit with a 6-bit operation code from the instruction operand `ir(5 downto 0)`. Bits [5:4] select the unit, bits [3:0] select the operation. The CU begins using operands previously loaded via `stop`. The CU asserts busy, which stalls the pipeline via `bsy` until the result is ready. Results are read with `ldop`. NOP class (no stack change). Encoding `0x140`–`0x17F`. Replaces the former `stmul`/old-`sthw` at `0x040`. See CU operation codes table below. |
| stidx | Store index for native field access | `0001001010` | `A to memidx`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the stack is stored as field index for native field access (```Native.get/putfield```) |
| stjpc | Store Java program counter | `0000011001` | `A to jpc`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The value on the top of the operand stack is popped and stored in the Java program counter (```jpc```). |
| stm | Store in local memory | `00001nnnnn` | `A to stack[n]`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the operand stack is stored in the local memory (stack) at position n. These 32 memory destinations represent microcode local variables. |
| stmi | Store in local memory indirect | `0000010101` | `A to stack[ar]`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the operand stack is stored in the local memory (stack) at position ar. |
| stmra | Store memory read address | `0001000010` | `A to memrda`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the stack is stored as read address in the memory subsystem. This operation starts the concurrent memory read. The processor can continue with other operations. When the datum is needed a ```wait``` instruction stalls the processor till the read access is finished. The value is read with ```ldmrd```. |
| stmrac | Start memory constant read | `0001001100` | `A to memrda`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | stmrac	load a constant |
| stmraf | Start memory read through full assoc. cache | `0001001101` | `A to memrda`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | load through fully assoc. cache |
| stmwa | Store memory write address | `0001000001` | `A to memwra`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the stack is stored as write address in the memory subsystem for a following ```stmwd```. |
| stmwd | Store memory write data | `0001000011` | `A to memwrd`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The top value from the stack is stored as write data in the memory subsystem. This operation starts the concurrent memory write The processor can continue with other operations. The ```wait``` instruction stalls the processor till the write access is finished. |
| stmwdf | Start memory write through full assoc. cache | `0001001110` | `A to memwrd`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | store through fully assoc. cache |
| stop | Store operand to compute unit | `0000011111` | `A to cu.opStack`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | Pops TOS (A register) and pushes the value onto the compute unit's internal 4-deep operand stack. Multiple `stop` instructions load all operands before `sthw` starts the operation. For 32-bit ops: 2 `stop` (value2, value1). For 64-bit ops: 4 `stop` (b_lo, b_hi, a_lo, a_hi). For long shifts: 3 `stop` (amount, val_lo, val_hi). Encoding `0x01F`, POP class. |
| stpf | Start putfield | `0001000111` | `A to memval`<br>`B to A`<br>`B to memptr`<br>`opd_{16} to memidx`<br>`stack[sp] to B`<br>`sp-1 to sp` | putfield | In the first cycle the top value from the stack is stored as value into the memory subsystem. A microcode ```pop``` has to follow. This operation starts the concurrent putfield. The processor can continue with other operations. The ```wait``` instruction stalls the processor till the write access is finished. A null pointer exception is generated by the memory subsystem and thrown at the next bytecode fetch. |
| stpfr | Start putfield (reference check) | `0001001111` | `A to memval`<br>`B to A`<br>`B to memptr`<br>`opd_{16} to memidx`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | start putfield index is taken from the BC operand |
| stps | Start putstatic | `0001001011` | `A to memval`<br>`B to A`<br>`opd_{16} to memptr`<br>`stack[sp] to B` | putstatic | The top value from the stack is stored into the static field. The static field address is in the bytecode operand (index). |
| stsp | Store stack pointer | `0000011011` | `A to sp`<br>`B to A`<br>`stack[sp] to B` | -- | The value on the top of the operand stack is popped and stored in the stack pointer (```sp```). |
| stvp | Store variable pointer | `0000011000` | `A to vp`<br>`B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | -- | The value on the top of the operand stack is popped and stored in the variable pointer (```vp```). |
| sub | Subtract int | `0000000101` | `A-B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | isub | Subtract the two top elements from the stack and push back the result onto the operand stack. |
| ushr | Logical shift rigth int | `0000011100` | `B >>> A to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | iushr | The values are popped from the operand stack. An ```int``` result is calculated by shifting the TOS-1 value rigth by $s$ position with zero extension where $s$ is the value of the low 5 bits of the TOS. The result is pushed onto the operand stack. |
| wait | Wait for memory/CU completion | `0100000001` | `-` | -- | This instruction stalls the processor until a pending memory instruction (```stmra``` ```stmwd``` or ```stbcrd```) or compute unit operation has completed. The `bsy` signal (which includes memory busy, write delay, and CU busy) causes the fetch stage to re-fetch the `wait` instruction until the condition clears. Two consecutive ```wait``` instructions are necessary for a correct stall of the decode and execute stage. For CU operations: `sthw` starts the unit, then `wait`/`wait` stalls until the result is ready for `ldop`. |
| xor | Boolean XOR int | `0000000011` | `A ^ B to A`<br>`stack[sp] to B`<br>`sp-1 to sp` | ixor | Build the bitwise exclusive OR (negation of equivalence) of the two top elements of the stack and push back the result onto the operand stack. |

## `sthw` Operation Codes

The 6-bit operand encodes both unit selection and operation: bits [5:4] select the unit, bits [3:0] select the operation within that unit.

### ICU — Integer Compute Unit (00_xxxx)

| Code | Constant | Operands | Results | JVM | Description |
|-----:|----------|:--------:|:-------:|-----|-------------|
| 0 | CU_IMUL | 2 | 1 | imul | 32x32->32 multiply |
| 1 | CU_IDIV | 2 | 1 | idiv | signed divide |
| 2 | CU_IREM | 2 | 1 | irem | signed remainder |

### FCU — Float Compute Unit (01_xxxx)

| Code | Constant | Operands | Results | JVM | Description |
|-----:|----------|:--------:|:-------:|-----|-------------|
| 16 | CU_FADD | 2 | 1 | fadd | float add |
| 17 | CU_FSUB | 2 | 1 | fsub | float subtract |
| 18 | CU_FMUL | 2 | 1 | fmul | float multiply |
| 19 | CU_FDIV | 2 | 1 | fdiv | float divide |
| 20 | CU_FCMPL | 2 | 1 | fcmpl | float compare (-1 on NaN) |
| 21 | CU_FCMPG | 2 | 1 | fcmpg | float compare (+1 on NaN) |
| 22 | CU_I2F | 1 | 1 | i2f | int -> float |
| 23 | CU_F2I | 1 | 1 | f2i | float -> int (truncate) |

### LCU — Long Compute Unit (10_xxxx)

| Code | Constant | Operands | Results | JVM | Description |
|-----:|----------|:--------:|:-------:|-----|-------------|
| 32 | CU_LADD | 4 | 2 | ladd | long add |
| 33 | CU_LSUB | 4 | 2 | lsub | long subtract |
| 34 | CU_LMUL | 4 | 2 | lmul | long multiply |
| 35 | CU_LDIV | 4 | 2 | ldiv | long signed divide |
| 36 | CU_LREM | 4 | 2 | lrem | long signed remainder |
| 37 | CU_LCMP | 4 | 1 | lcmp | long compare |
| 38 | CU_LSHL | 3 | 2 | lshl | long shift left |
| 39 | CU_LSHR | 3 | 2 | lshr | long arithmetic shift right |
| 40 | CU_LUSHR | 3 | 2 | lushr | long logical shift right |

### DCU — Double Compute Unit (11_xxxx)

| Code | Constant | Operands | Results | JVM | Description |
|-----:|----------|:--------:|:-------:|-----|-------------|
| 48 | CU_DADD | 4 | 2 | dadd | double add |
| 49 | CU_DSUB | 4 | 2 | dsub | double subtract |
| 50 | CU_DMUL | 4 | 2 | dmul | double multiply |
| 51 | CU_DDIV | 4 | 2 | ddiv | double divide |
| 52 | CU_DCMPL | 4 | 1 | dcmpl | double compare (-1 on NaN) |
| 53 | CU_DCMPG | 4 | 1 | dcmpg | double compare (+1 on NaN) |
| 54 | CU_F2D | 1 | 2 | f2d | float -> double |
| 55 | CU_D2F | 2 | 1 | d2f | double -> float |
| 56 | CU_I2D | 1 | 2 | i2d | int -> double |
| 57 | CU_D2I | 2 | 1 | d2i | double -> int (truncate) |
| 58 | CU_L2D | 2 | 2 | l2d | long -> double |
| 59 | CU_D2L | 2 | 2 | d2l | double -> long (truncate) |
