/*
 * Simplified NumberFormat for JOP. No Locale, no ResourceBundle, no sun.*,
 * no serialization. Factory methods return DecimalFormat with US patterns.
 * Based on OpenJDK 6.
 * Original: Copyright (c) 1996, 2005, Oracle and/or its affiliates.
 * Licensed under GPL v2 with Classpath exception.
 */
package java.text;

import java.math.RoundingMode;

public abstract class NumberFormat extends Format {

    public static final int INTEGER_FIELD = 0;
    public static final int FRACTION_FIELD = 1;

    protected NumberFormat() {
    }

    public StringBuffer format(Object number,
                               StringBuffer toAppendTo,
                               FieldPosition pos) {
        if (number instanceof Long ||
            number instanceof Integer ||
            number instanceof Short ||
            number instanceof Byte) {
            return format(((Number) number).longValue(), toAppendTo, pos);
        } else if (number instanceof Number) {
            return format(((Number) number).doubleValue(), toAppendTo, pos);
        } else {
            throw new IllegalArgumentException("Cannot format given Object as a Number");
        }
    }

    public Object parseObject(String source, ParsePosition pos) {
        return parse(source, pos);
    }

    public final String format(double number) {
        return format(number, new StringBuffer(),
                      DontCareFieldPosition.INSTANCE).toString();
    }

    public final String format(long number) {
        return format(number, new StringBuffer(),
                      DontCareFieldPosition.INSTANCE).toString();
    }

    public abstract StringBuffer format(double number,
                                        StringBuffer toAppendTo,
                                        FieldPosition pos);

    public abstract StringBuffer format(long number,
                                        StringBuffer toAppendTo,
                                        FieldPosition pos);

    public abstract Number parse(String source, ParsePosition parsePosition);

    public Number parse(String source) throws ParseException {
        ParsePosition parsePosition = new ParsePosition(0);
        Number result = parse(source, parsePosition);
        if (parsePosition.index == 0) {
            throw new ParseException("Unparseable number: \"" + source + "\"",
                                     parsePosition.errorIndex);
        }
        return result;
    }

    public boolean isGroupingUsed() {
        return groupingUsed;
    }

    public void setGroupingUsed(boolean newValue) {
        groupingUsed = newValue;
    }

    public int getMaximumIntegerDigits() {
        return maximumIntegerDigits;
    }

    public void setMaximumIntegerDigits(int newValue) {
        maximumIntegerDigits = Math.max(0, newValue);
        if (minimumIntegerDigits > maximumIntegerDigits) {
            minimumIntegerDigits = maximumIntegerDigits;
        }
    }

    public int getMinimumIntegerDigits() {
        return minimumIntegerDigits;
    }

    public void setMinimumIntegerDigits(int newValue) {
        minimumIntegerDigits = Math.max(0, newValue);
        if (minimumIntegerDigits > maximumIntegerDigits) {
            maximumIntegerDigits = minimumIntegerDigits;
        }
    }

    public int getMaximumFractionDigits() {
        return maximumFractionDigits;
    }

    public void setMaximumFractionDigits(int newValue) {
        maximumFractionDigits = Math.max(0, newValue);
        if (maximumFractionDigits < minimumFractionDigits) {
            minimumFractionDigits = maximumFractionDigits;
        }
    }

    public int getMinimumFractionDigits() {
        return minimumFractionDigits;
    }

    public void setMinimumFractionDigits(int newValue) {
        minimumFractionDigits = Math.max(0, newValue);
        if (maximumFractionDigits < minimumFractionDigits) {
            maximumFractionDigits = minimumFractionDigits;
        }
    }

    public boolean isParseIntegerOnly() {
        return parseIntegerOnly;
    }

    public void setParseIntegerOnly(boolean value) {
        parseIntegerOnly = value;
    }

    /**
     * Returns a general-purpose number format (US locale pattern "#,##0.###").
     */
    public static NumberFormat getInstance() {
        return getNumberInstance();
    }

    /**
     * Returns a general-purpose number format.
     */
    public static NumberFormat getNumberInstance() {
        return new DecimalFormat("#,##0.###");
    }

    /**
     * Returns an integer number format (no fraction digits).
     */
    public static NumberFormat getIntegerInstance() {
        DecimalFormat fmt = new DecimalFormat("#,##0");
        fmt.setParseIntegerOnly(true);
        fmt.setMaximumFractionDigits(0);
        return fmt;
    }

    /**
     * Returns a percent format.
     */
    public static NumberFormat getPercentInstance() {
        return new DecimalFormat("#,##0%");
    }

    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (this == obj) return true;
        if (!(obj instanceof NumberFormat)) return false;
        NumberFormat other = (NumberFormat) obj;
        return (maximumIntegerDigits == other.maximumIntegerDigits
            && minimumIntegerDigits == other.minimumIntegerDigits
            && maximumFractionDigits == other.maximumFractionDigits
            && minimumFractionDigits == other.minimumFractionDigits
            && groupingUsed == other.groupingUsed
            && parseIntegerOnly == other.parseIntegerOnly);
    }

    public int hashCode() {
        return maximumIntegerDigits * 37 + maximumFractionDigits;
    }

    // ---- Fields ----

    private boolean groupingUsed = true;
    private int maximumIntegerDigits = 40;
    private int minimumIntegerDigits = 1;
    private int maximumFractionDigits = 3;
    private int minimumFractionDigits = 0;
    private boolean parseIntegerOnly = false;

    /**
     * Field constants for FieldPosition.
     */
    public static class Field extends Format.Field {
        public static final Field INTEGER = new Field("integer");
        public static final Field FRACTION = new Field("fraction");
        public static final Field EXPONENT = new Field("exponent");
        public static final Field EXPONENT_SIGN = new Field("exponent sign");
        public static final Field EXPONENT_SYMBOL = new Field("exponent symbol");
        public static final Field DECIMAL_SEPARATOR = new Field("decimal separator");
        public static final Field SIGN = new Field("sign");
        public static final Field GROUPING_SEPARATOR = new Field("grouping separator");
        public static final Field PERCENT = new Field("percent");
        public static final Field PERMILLE = new Field("per mille");
        public static final Field CURRENCY = new Field("currency");

        protected Field(String name) {
            super(name);
        }
    }
}
