package test;

/**
 * Test integer multiplication (imul bytecode) on hardware.
 * Verifies correctness of DSP multiply path.
 */
public class ImulTest {

    // JOP I/O addresses (from Const.java)
    static final int IO_BASE = -128;        // 0xFFFFFF80
    static final int IO_STATUS = IO_BASE + 0x10;  // UART status
    static final int IO_UART = IO_BASE + 0x11;    // UART data

    public static void main(String[] args) {

        printStr("=== imul test ===\r\n");

        int pass = 0;
        int fail = 0;

        // Basic multiply tests
        if (check(3, 7, 21)) pass++; else fail++;
        if (check(0, 12345, 0)) pass++; else fail++;
        if (check(1, 99999, 99999)) pass++; else fail++;
        if (check(-1, 42, -42)) pass++; else fail++;
        if (check(-1, -1, 1)) pass++; else fail++;
        if (check(100, 200, 20000)) pass++; else fail++;
        if (check(256, 256, 65536)) pass++; else fail++;
        if (check(0x7FFF, 0x7FFF, 0x3FFF0001)) pass++; else fail++;
        if (check(-2, 0x40000000, -0x80000000)) pass++; else fail++;
        if (check(0x10000, 0x10000, 0)) pass++; else fail++;  // overflow to 0
        if (check(12345, 6789, 83810205)) pass++; else fail++;
        if (check(-12345, 6789, -83810205)) pass++; else fail++;
        if (check(0x7FFFFFFF, 2, -2)) pass++; else fail++;  // MAX_VALUE * 2
        if (check(0x80000000, -1, 0x80000000)) pass++; else fail++;  // MIN_VALUE * -1 = MIN_VALUE (overflow)
        if (check(1000000, 1000, 1000000000)) pass++; else fail++;

        printStr("pass=");
        printInt(pass);
        printStr(" fail=");
        printInt(fail);
        printStr("\r\n");

        if (fail == 0) {
            printStr("ALL PASS\r\n");
        } else {
            printStr("FAIL!\r\n");
        }

        // Busy loop — toggle watchdog
        for (;;) {
            int wd = com.jopdesign.sys.Native.rd(IO_BASE + 3);
            com.jopdesign.sys.Native.wr(wd + 1, IO_BASE + 3);
        }
    }

    static boolean check(int a, int b, int expected) {
        int result = a * b;
        if (result == expected) {
            return true;
        } else {
            printStr("FAIL: ");
            printInt(a);
            printStr("*");
            printInt(b);
            printStr("=");
            printInt(result);
            printStr(" exp=");
            printInt(expected);
            printStr("\r\n");
            return false;
        }
    }

    static void printStr(String s) {
        for (int i = 0; i < s.length(); i++) {
            while ((com.jopdesign.sys.Native.rd(IO_STATUS) & 1) == 0) {
                ; // wait for TX empty (TDRE, bit 0)
            }
            com.jopdesign.sys.Native.wr(s.charAt(i), IO_UART);
        }
    }

    static void printInt(int val) {
        // Simple decimal print without StringBuilder (JOP-safe)
        if (val < 0) {
            printChar('-');
            if (val == 0x80000000) {
                // Special case: MIN_VALUE
                printStr("2147483648");
                return;
            }
            val = -val;
        }
        if (val == 0) {
            printChar('0');
            return;
        }
        // Count digits
        int tmp = val;
        int digits = 0;
        while (tmp > 0) {
            digits++;
            tmp = tmp / 10;
        }
        // Print from most significant
        int divisor = 1;
        for (int i = 1; i < digits; i++) {
            divisor = divisor * 10;
        }
        while (divisor > 0) {
            int d = val / divisor;
            printChar((char)('0' + d));
            val = val - d * divisor;
            divisor = divisor / 10;
        }
    }

    static void printChar(char c) {
        while ((com.jopdesign.sys.Native.rd(IO_STATUS) & 1) == 0) {
            ; // wait for TX empty (TDRE, bit 0)
        }
        com.jopdesign.sys.Native.wr(c, IO_UART);
    }
}
