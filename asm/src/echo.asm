//
//  echo.asm - Minimal UART echo test for JOP
//
//  Polls UART status for RDRF, reads byte, echoes it back.
//  No SDRAM access, no Java, just UART I/O.
//

version     = 20260216

stack_init  = 64

// I/O addresses (negative = high address, I/O space)
io_status   = -112
io_uart     = -111
io_cpu_id   = -122

// Status register bit masks
ua_rdrf     = 2
ua_tdre     = 1

// Variables
a       ?
b       ?
c       ?
d       ?

//
// Entry point
//
jvm_start:
            nop
            nop

            ldi stack_init
            nop
            stsp

// Send startup marker 'E' = 0x45
            ldi io_uart
            stmwa
            ldi 69           // 'E' = 69 decimal
            stmwd
            wait
            wait

// Main echo loop
echo_loop:
            // Poll UART status for RDRF (bit 1)
            ldi io_status
            stmra
            ldi ua_rdrf
            wait
            wait
            ldmrd
            and
            nop
            bz echo_loop
            nop
            nop

            // Read byte from UART
            ldi io_uart
            stmra
            wait
            wait
            ldmrd

            // Echo byte to UART
            ldi io_uart
            stmwa
            dup
            stmwd
            wait
            wait

            // Discard the byte copy
            pop

            // Loop
            jmp echo_loop
            nop
            nop
