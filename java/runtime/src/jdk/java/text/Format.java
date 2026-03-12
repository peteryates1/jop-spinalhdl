/*
 * Simplified Format for JOP. No AttributedCharacterIterator, no Locale,
 * no serialization. Adapted from OpenJDK 6.
 * Original: Copyright (c) 1996, 2005, Oracle and/or its affiliates.
 * Licensed under GPL v2 with Classpath exception.
 */
package java.text;

public abstract class Format {

    protected Format() {
    }

    public final String format(Object obj) {
        return format(obj, new StringBuffer(), new FieldPosition(0)).toString();
    }

    public abstract StringBuffer format(Object obj,
                    StringBuffer toAppendTo,
                    FieldPosition pos);

    public abstract Object parseObject(String source, ParsePosition pos);

    public Object parseObject(String source) throws ParseException {
        ParsePosition pos = new ParsePosition(0);
        Object result = parseObject(source, pos);
        if (pos.index == 0) {
            throw new ParseException("Format.parseObject(String) failed",
                pos.errorIndex);
        }
        return result;
    }

    /**
     * Defines constants used as field identifiers in FieldPosition.
     */
    public static class Field {
        private String name;

        protected Field(String name) {
            this.name = name;
        }

        public String toString() {
            return "Format.Field(" + name + ")";
        }
    }

    /**
     * FieldDelegate is notified by Format implementations as they format.
     */
    interface FieldDelegate {
        public void formatted(Format.Field attr, Object value, int start,
                              int end, StringBuffer buffer);

        public void formatted(int fieldID, Format.Field attr, Object value,
                              int start, int end, StringBuffer buffer);
    }
}
