/*
 * Adapted from OpenJDK 6 for JOP.
 * Original: Copyright (c) 1996, 2002, Oracle and/or its affiliates.
 * Licensed under GPL v2 with Classpath exception.
 */
package java.text;

public class ParsePosition {

    int index = 0;
    int errorIndex = -1;

    public ParsePosition(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public void setErrorIndex(int ei) {
        errorIndex = ei;
    }

    public int getErrorIndex() {
        return errorIndex;
    }

    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (!(obj instanceof ParsePosition))
            return false;
        ParsePosition other = (ParsePosition) obj;
        return (index == other.index && errorIndex == other.errorIndex);
    }

    public int hashCode() {
        return (errorIndex << 16) | index;
    }

    public String toString() {
        return "ParsePosition[index=" + index +
            ",errorIndex=" + errorIndex + ']';
    }
}
