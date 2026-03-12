/*
 * Adapted from OpenJDK 6 for JOP. Removed serialization.
 * Original: Copyright (c) 1996, 1998, Oracle and/or its affiliates.
 * Licensed under GPL v2 with Classpath exception.
 */
package java.text;

public class ParseException extends Exception {

    private int errorOffset;

    public ParseException(String s, int errorOffset) {
        super(s);
        this.errorOffset = errorOffset;
    }

    public int getErrorOffset() {
        return errorOffset;
    }
}
