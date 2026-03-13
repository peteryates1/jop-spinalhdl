/*
 * Simplified RoundingMode for JOP.
 * Original was an enum, but JOP enum clinit creates objects before GC is ready.
 * Replaced with a plain class using lazy-initialized singleton instances.
 */
package java.math;

/**
 * Specifies a rounding behavior for numerical operations.
 * JOP version: plain class instead of enum to avoid clinit object allocation.
 */
public final class RoundingMode {

    // Ordinal value (matches original enum ordinal)
    private final int ordinal;
    // Corresponding BigDecimal rounding constant
    final int oldMode;

    private RoundingMode(int ordinal, int oldMode) {
        this.ordinal = ordinal;
        this.oldMode = oldMode;
    }

    public int ordinal() { return ordinal; }

    public String toString() {
        switch (ordinal) {
        case 0: return "UP";
        case 1: return "DOWN";
        case 2: return "CEILING";
        case 3: return "FLOOR";
        case 4: return "HALF_UP";
        case 5: return "HALF_DOWN";
        case 6: return "HALF_EVEN";
        case 7: return "UNNECESSARY";
        default: return "UNKNOWN";
        }
    }

    // Lazy-initialized instances (JOP: clinit runs before GC)
    public static RoundingMode UP;
    public static RoundingMode DOWN;
    public static RoundingMode CEILING;
    public static RoundingMode FLOOR;
    public static RoundingMode HALF_UP;
    public static RoundingMode HALF_DOWN;
    public static RoundingMode HALF_EVEN;
    public static RoundingMode UNNECESSARY;

    public static void ensureInstances() {
        if (UP == null) {
            UP          = new RoundingMode(0, 0);
            DOWN        = new RoundingMode(1, 1);
            CEILING     = new RoundingMode(2, 2);
            FLOOR       = new RoundingMode(3, 3);
            HALF_UP     = new RoundingMode(4, 4);
            HALF_DOWN   = new RoundingMode(5, 5);
            HALF_EVEN   = new RoundingMode(6, 6);
            UNNECESSARY = new RoundingMode(7, 7);
        }
    }

    /**
     * Returns the RoundingMode with the given name.
     */
    public static RoundingMode valueOf(String name) {
        ensureInstances();
        if ("UP".equals(name)) return UP;
        if ("DOWN".equals(name)) return DOWN;
        if ("CEILING".equals(name)) return CEILING;
        if ("FLOOR".equals(name)) return FLOOR;
        if ("HALF_UP".equals(name)) return HALF_UP;
        if ("HALF_DOWN".equals(name)) return HALF_DOWN;
        if ("HALF_EVEN".equals(name)) return HALF_EVEN;
        if ("UNNECESSARY".equals(name)) return UNNECESSARY;
        throw new IllegalArgumentException("No enum const RoundingMode." + name);
    }

    /**
     * Returns the RoundingMode corresponding to a legacy integer constant.
     */
    public static RoundingMode valueOf(int rm) {
        switch(rm) {
        case 0: return UP;
        case 1: return DOWN;
        case 2: return CEILING;
        case 3: return FLOOR;
        case 4: return HALF_UP;
        case 5: return HALF_DOWN;
        case 6: return HALF_EVEN;
        case 7: return UNNECESSARY;
        default:
            throw new IllegalArgumentException("argument out of range");
        }
    }
}
