version         =  20250529

stack_init      =  64

io_count        = -128
io_us_count     = -127
io_sw_int       = -126
io_wd           = -125
io_uart_status  = -112
io_uart_data    = -111
io_led          = -64
io_ss           = -48

uart_rx_data_full   = 2
uart_tx_data_empty  = 1


sim_delay       =  4
delay           =  200000

address         ?
next            ?

start:
        nop
        nop

initialize_stack:
        ldi stack_init
        nop
        stsp

initialize_address:
        ldi 0
        stm address

initialize_next:
        ldi io_us_count
        stmra
        wait
        wait
        ldmrd
        ldi delay
        add
        stm next

write_address_to_ss:
        ldi io_ss
        stmwa
        ldm address
        stmwd

read_memory_write_to_led:
        ldi io_led
        stmwa
        ldm address
        stmra
        wait
        wait
        ldmrd
        stmwd

increment_address:
        ldm address
        ldi 1
        add
        stm address

check_next:
        ldi io_us_count
        stmra
        wait
        wait
        ldmrd
        ldm next
        sub
        nop
        bz initialize_next_0
        nop
        nop
        jmp check_next
        nop
        nop

initialize_next_0:
        jmp initialize_next
        nop
        nop
