/*
 * Simplified DecimalFormat for JOP. Locale-independent, no Currency,
 * no sun.*, no serialization. Supports common patterns: #,##0.###,
 * 0.00, #.##E0, #,##0%, etc.
 * Based on OpenJDK 6 (3,276 lines reduced to ~700).
 * Original: Copyright (c) 1996, 2006, Oracle and/or its affiliates.
 * Licensed under GPL v2 with Classpath exception.
 */
package java.text;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public class DecimalFormat extends NumberFormat {

    private transient DigitList digitList = new DigitList();
    private DecimalFormatSymbols symbols;

    private String positivePrefix = "";
    private String positiveSuffix = "";
    private String negativePrefix = "-";
    private String negativeSuffix = "";

    private int multiplier = 1;
    private byte groupingSize = 3;
    private boolean decimalSeparatorAlwaysShown = false;
    private boolean useExponentialNotation = false;
    private int minExponentDigits;

    private RoundingMode roundingMode = RoundingMode.HALF_EVEN;

    // Transient pattern state
    private transient String pattern;

    /**
     * Creates a DecimalFormat with default pattern "#,##0.###".
     */
    public DecimalFormat() {
        this("#,##0.###", new DecimalFormatSymbols());
    }

    /**
     * Creates a DecimalFormat with the given pattern and default symbols.
     */
    public DecimalFormat(String pattern) {
        this(pattern, new DecimalFormatSymbols());
    }

    /**
     * Creates a DecimalFormat with the given pattern and symbols.
     */
    public DecimalFormat(String pattern, DecimalFormatSymbols symbols) {
        this.symbols = symbols;
        applyPattern(pattern);
    }

    // ---- Formatting ----

    public StringBuffer format(double number, StringBuffer result,
                               FieldPosition fieldPosition) {
        fieldPosition.setBeginIndex(0);
        fieldPosition.setEndIndex(0);

        if (Double.isNaN(number)) {
            result.append(symbols.getNaN());
            return result;
        }

        boolean isNegative = (number < 0.0) ||
            (number == 0.0 && 1.0 / number < 0.0); // detect -0.0
        if (isNegative) {
            number = -number;
        }

        if (Double.isInfinite(number)) {
            result.append(isNegative ? negativePrefix : positivePrefix);
            result.append(symbols.getInfinity());
            result.append(isNegative ? negativeSuffix : positiveSuffix);
            return result;
        }

        if (multiplier != 1) {
            number *= multiplier;
        }

        if (useExponentialNotation) {
            formatExponential(number, isNegative, result, fieldPosition);
        } else {
            formatFixed(number, isNegative, result, fieldPosition);
        }

        return result;
    }

    public StringBuffer format(long number, StringBuffer result,
                               FieldPosition fieldPosition) {
        fieldPosition.setBeginIndex(0);
        fieldPosition.setEndIndex(0);

        boolean isNegative = (number < 0);
        if (isNegative) {
            number = -number;
        }

        if (multiplier != 1) {
            // Use double for multiplied values to avoid overflow
            double d = (double) (isNegative ? -number : number);
            if (isNegative) d = -d;
            d *= multiplier;
            return format(d, result, fieldPosition);
        }

        digitList.set(isNegative, number, getMaximumIntegerDigits());
        return formatDigitList(isNegative, result, fieldPosition);
    }

    private void formatFixed(double number, boolean isNegative,
                             StringBuffer result, FieldPosition fieldPosition) {
        digitList.set(isNegative, number, getMaximumFractionDigits());
        formatDigitList(isNegative, result, fieldPosition);
    }

    private void formatExponential(double number, boolean isNegative,
                                   StringBuffer result, FieldPosition fieldPosition) {
        digitList.set(isNegative, number,
                      getMaximumIntegerDigits() + getMaximumFractionDigits(), false);
        formatExpDigitList(isNegative, result, fieldPosition);
    }

    private StringBuffer formatDigitList(boolean isNegative, StringBuffer result,
                                         FieldPosition fieldPosition) {
        result.append(isNegative ? negativePrefix : positivePrefix);

        int intBegin = result.length();

        int intDigits = digitList.isZero() ? getMinimumIntegerDigits()
                : digitList.decimalAt;
        int totalDigits = intDigits > getMinimumIntegerDigits()
                ? intDigits : getMinimumIntegerDigits();

        // Format integer part
        for (int i = totalDigits - 1; i >= 0; --i) {
            if (i < digitList.decimalAt && digitList.decimalAt - i - 1 < digitList.count) {
                result.append(digitList.digits[digitList.decimalAt - i - 1]);
            } else {
                result.append(symbols.getZeroDigit());
            }

            // Grouping separator
            if (isGroupingUsed() && groupingSize > 0 && i > 0 && (i % groupingSize) == 0) {
                result.append(symbols.getGroupingSeparator());
            }
        }

        if (fieldPosition.getField() == INTEGER_FIELD) {
            fieldPosition.setBeginIndex(intBegin);
            fieldPosition.setEndIndex(result.length());
        }

        // Fraction part
        boolean fractionPresent = (getMinimumFractionDigits() > 0) ||
            (!digitList.isZero() && digitList.count > digitList.decimalAt);

        if (fractionPresent || decimalSeparatorAlwaysShown) {
            result.append(symbols.getDecimalSeparator());
        }

        int fracBegin = result.length();

        for (int i = 0; i < getMaximumFractionDigits(); ++i) {
            int idx = digitList.decimalAt + i;
            if (idx >= 0 && idx < digitList.count) {
                result.append(digitList.digits[idx]);
            } else if (i < getMinimumFractionDigits()) {
                result.append(symbols.getZeroDigit());
            } else {
                break;
            }
        }

        if (fieldPosition.getField() == FRACTION_FIELD) {
            fieldPosition.setBeginIndex(fracBegin);
            fieldPosition.setEndIndex(result.length());
        }

        result.append(isNegative ? negativeSuffix : positiveSuffix);
        return result;
    }

    private void formatExpDigitList(boolean isNegative, StringBuffer result,
                                    FieldPosition fieldPosition) {
        result.append(isNegative ? negativePrefix : positivePrefix);

        int intBegin = result.length();
        int exponent = digitList.isZero() ? 0 : digitList.decimalAt - 1;
        int intPartDigits = getMinimumIntegerDigits() < 1 ? 1 : getMinimumIntegerDigits();

        // Integer part of mantissa
        for (int i = 0; i < intPartDigits; i++) {
            result.append(i < digitList.count ? digitList.digits[i]
                    : symbols.getZeroDigit());
        }

        if (fieldPosition.getField() == INTEGER_FIELD) {
            fieldPosition.setBeginIndex(intBegin);
            fieldPosition.setEndIndex(result.length());
        }

        // Fraction part of mantissa
        boolean hasFrac = getMaximumFractionDigits() > 0;
        if (hasFrac || decimalSeparatorAlwaysShown) {
            result.append(symbols.getDecimalSeparator());
        }

        int fracBegin = result.length();
        for (int i = intPartDigits; i < intPartDigits + getMaximumFractionDigits(); i++) {
            if (i < digitList.count) {
                result.append(digitList.digits[i]);
            } else if (i - intPartDigits < getMinimumFractionDigits()) {
                result.append(symbols.getZeroDigit());
            } else {
                break;
            }
        }

        if (fieldPosition.getField() == FRACTION_FIELD) {
            fieldPosition.setBeginIndex(fracBegin);
            fieldPosition.setEndIndex(result.length());
        }

        // Exponent
        result.append(symbols.getExponentSeparator());
        if (exponent < 0) {
            result.append(symbols.getMinusSign());
            exponent = -exponent;
        }
        String expStr = Integer.toString(exponent);
        for (int i = expStr.length(); i < minExponentDigits; i++) {
            result.append(symbols.getZeroDigit());
        }
        result.append(expStr);

        result.append(isNegative ? negativeSuffix : positiveSuffix);
    }

    // ---- Parsing ----

    public Number parse(String text, ParsePosition pos) {
        int start = pos.getIndex();
        int len = text.length();

        if (start >= len) {
            pos.setErrorIndex(start);
            return null;
        }

        // Try positive then negative prefix
        boolean isNegative = false;
        boolean gotPrefix = false;

        if (negativePrefix.length() > 0 && text.startsWith(negativePrefix, start)) {
            isNegative = true;
            start += negativePrefix.length();
            gotPrefix = true;
        } else if (positivePrefix.length() > 0 && text.startsWith(positivePrefix, start)) {
            start += positivePrefix.length();
            gotPrefix = true;
        } else if (negativePrefix.length() == 0) {
            // Check for minus sign
            if (start < len && text.charAt(start) == symbols.getMinusSign()) {
                isNegative = true;
                start++;
            }
        }

        // Parse digits
        digitList.clear();
        boolean sawDecimal = false;
        boolean sawDigit = false;
        int digitCount = 0;
        int exponent = 0;

        char zero = symbols.getZeroDigit();
        char decimal = symbols.getDecimalSeparator();
        char groupSep = symbols.getGroupingSeparator();

        for (int i = start; i < len; i++) {
            char c = text.charAt(i);

            if (c >= zero && c <= (char)(zero + 9)) {
                sawDigit = true;
                char digit = (char)('0' + (c - zero));
                if (digit != '0' || digitCount > 0) {
                    digitList.append(digit);
                    digitCount++;
                } else if (sawDecimal) {
                    digitList.decimalAt--;
                }
                if (!sawDecimal) {
                    digitList.decimalAt++;
                }
            } else if (c == decimal && !sawDecimal) {
                sawDecimal = true;
            } else if (c == groupSep && isGroupingUsed() && !sawDecimal) {
                // Skip grouping separators
            } else if ((c == 'E' || c == 'e') && sawDigit && useExponentialNotation) {
                i++;
                boolean negExp = false;
                if (i < len && text.charAt(i) == symbols.getMinusSign()) {
                    negExp = true;
                    i++;
                } else if (i < len && text.charAt(i) == '+') {
                    i++;
                }
                int expVal = 0;
                boolean sawExpDigit = false;
                for (; i < len; i++) {
                    c = text.charAt(i);
                    if (c >= zero && c <= (char)(zero + 9)) {
                        expVal = expVal * 10 + (c - zero);
                        sawExpDigit = true;
                    } else {
                        break;
                    }
                }
                if (sawExpDigit) {
                    exponent = negExp ? -expVal : expVal;
                }
                pos.setIndex(i);
                break;
            } else {
                pos.setIndex(i);
                break;
            }
            pos.setIndex(i + 1);
        }

        if (!sawDigit) {
            pos.setIndex(start > 0 ? start - (gotPrefix ? (isNegative ? negativePrefix.length() : positivePrefix.length()) : 0) : 0);
            pos.setErrorIndex(start);
            return null;
        }

        // Check suffix
        int currentPos = pos.getIndex();
        String suffix = isNegative ? negativeSuffix : positiveSuffix;
        if (suffix.length() > 0 && text.startsWith(suffix, currentPos)) {
            pos.setIndex(currentPos + suffix.length());
        }

        digitList.decimalAt += exponent;

        if (multiplier != 1) {
            // Parse value and divide by multiplier
            double val = digitList.getDouble();
            if (isNegative) val = -val;
            val /= multiplier;
            return new Double(val);
        }

        if (digitList.fitsIntoLong(!isNegative, isParseIntegerOnly())) {
            long l = digitList.getLong();
            if (isNegative) l = -l;
            return new Long(l);
        } else {
            double d = digitList.getDouble();
            if (isNegative) d = -d;
            return new Double(d);
        }
    }

    // ---- Pattern parsing ----

    public void applyPattern(String pattern) {
        this.pattern = pattern;

        positivePrefix = "";
        positiveSuffix = "";
        negativePrefix = "-";
        negativeSuffix = "";
        multiplier = 1;
        useExponentialNotation = false;
        decimalSeparatorAlwaysShown = false;

        int maxInt = 40;
        int minInt = 1;
        int maxFrac = 3;
        int minFrac = 0;

        char zeroDigit = symbols.getZeroDigit();
        char digit = symbols.getDigit();
        char decSep = symbols.getDecimalSeparator();
        char grpSep = symbols.getGroupingSeparator();
        char percent = symbols.getPercent();
        char perMill = symbols.getPerMill();
        char minus = symbols.getMinusSign();
        char patSep = symbols.getPatternSeparator();

        int pos = 0;
        int len = pattern.length();
        boolean inPrefix = true;
        boolean inSuffix = false;
        boolean gotDecimal = false;
        boolean gotDigit = false;
        boolean isNegPart = false;
        int groupSize = 0;
        int lastGroupPos = -1;
        StringBuffer prefix = new StringBuffer();
        StringBuffer suffix = new StringBuffer();

        minInt = 0;
        maxInt = 0;
        minFrac = 0;
        maxFrac = 0;

        while (pos < len) {
            char c = pattern.charAt(pos);

            if (inPrefix) {
                if (c == digit || c == zeroDigit || c == decSep || c == grpSep) {
                    inPrefix = false;
                    // Don't consume — fall through
                } else if (c == percent) {
                    multiplier = 100;
                    if (!isNegPart) positivePrefix = prefix.toString();
                    prefix.setLength(0);
                    pos++;
                    continue;
                } else if (c == perMill) {
                    multiplier = 1000;
                    if (!isNegPart) positivePrefix = prefix.toString();
                    prefix.setLength(0);
                    pos++;
                    continue;
                } else if (c == minus) {
                    prefix.append(c);
                    pos++;
                    continue;
                } else if (c == patSep) {
                    if (!isNegPart) {
                        positivePrefix = prefix.toString();
                    }
                    prefix.setLength(0);
                    isNegPart = true;
                    inPrefix = true;
                    inSuffix = false;
                    gotDecimal = false;
                    gotDigit = false;
                    pos++;
                    continue;
                } else if (c == '\'') {
                    // Quoted literal
                    pos++;
                    while (pos < len) {
                        c = pattern.charAt(pos);
                        if (c == '\'') {
                            pos++;
                            break;
                        }
                        prefix.append(c);
                        pos++;
                    }
                    continue;
                } else {
                    prefix.append(c);
                    pos++;
                    continue;
                }
            }

            if (inSuffix) {
                if (c == patSep && !isNegPart) {
                    positiveSuffix = suffix.toString();
                    suffix.setLength(0);
                    isNegPart = true;
                    inPrefix = true;
                    inSuffix = false;
                    gotDecimal = false;
                    gotDigit = false;
                    prefix.setLength(0);
                    pos++;
                    continue;
                } else if (c == percent) {
                    multiplier = 100;
                    suffix.append(c);
                    pos++;
                    continue;
                } else if (c == perMill) {
                    multiplier = 1000;
                    suffix.append(c);
                    pos++;
                    continue;
                } else if (c == '\'') {
                    pos++;
                    while (pos < len) {
                        c = pattern.charAt(pos);
                        if (c == '\'') {
                            pos++;
                            break;
                        }
                        suffix.append(c);
                        pos++;
                    }
                    continue;
                } else {
                    suffix.append(c);
                    pos++;
                    continue;
                }
            }

            // Number part
            if (c == digit) {
                gotDigit = true;
                if (!gotDecimal) {
                    maxInt++;
                    if (lastGroupPos >= 0) groupSize++;
                } else {
                    maxFrac++;
                }
                pos++;
            } else if (c == zeroDigit) {
                gotDigit = true;
                if (!gotDecimal) {
                    minInt++;
                    maxInt++;
                    if (lastGroupPos >= 0) groupSize++;
                } else {
                    minFrac++;
                    maxFrac++;
                }
                pos++;
            } else if (c == decSep) {
                gotDecimal = true;
                pos++;
            } else if (c == grpSep) {
                lastGroupPos = pos;
                groupSize = 0;
                pos++;
            } else if (c == 'E' && gotDigit) {
                useExponentialNotation = true;
                pos++;
                minExponentDigits = 0;
                while (pos < len && pattern.charAt(pos) == zeroDigit) {
                    minExponentDigits++;
                    pos++;
                }
                if (minExponentDigits == 0) minExponentDigits = 1;
            } else if (c == percent) {
                multiplier = 100;
                inSuffix = true;
                suffix.append(c);
                pos++;
            } else if (c == perMill) {
                multiplier = 1000;
                inSuffix = true;
                suffix.append(c);
                pos++;
            } else {
                // Start of suffix
                inSuffix = true;
                // Don't consume — re-enter loop
            }
        }

        // Finalize
        if (inPrefix) {
            if (isNegPart) {
                negativePrefix = prefix.toString();
            } else {
                positivePrefix = prefix.toString();
            }
        }
        if (inSuffix || gotDigit) {
            if (isNegPart) {
                negativeSuffix = suffix.toString();
                negativePrefix = prefix.toString();
            } else {
                positiveSuffix = suffix.toString();
                positivePrefix = prefix.toString();
            }
        }

        if (lastGroupPos >= 0) {
            groupingSize = (byte) groupSize;
        }

        if (minInt == 0 && !gotDecimal) {
            minInt = 1;
        }

        // If decimal separator is in the pattern but no fraction digits, show it
        if (gotDecimal && maxFrac == 0 && minFrac == 0) {
            decimalSeparatorAlwaysShown = true;
        }

        setMinimumIntegerDigits(minInt);
        setMaximumIntegerDigits(maxInt);
        setMinimumFractionDigits(minFrac);
        setMaximumFractionDigits(maxFrac);

        // For negative part, if we didn't parse one, default to "-" + positive
        if (!isNegPart) {
            negativePrefix = String.valueOf(symbols.getMinusSign()) + positivePrefix;
            negativeSuffix = positiveSuffix;
        }
    }

    public String toPattern() {
        return pattern != null ? pattern : "";
    }

    // ---- Getters and Setters ----

    public DecimalFormatSymbols getDecimalFormatSymbols() {
        return symbols;
    }

    public void setDecimalFormatSymbols(DecimalFormatSymbols newSymbols) {
        symbols = newSymbols;
    }

    public String getPositivePrefix() {
        return positivePrefix;
    }

    public void setPositivePrefix(String newValue) {
        positivePrefix = newValue;
    }

    public String getNegativePrefix() {
        return negativePrefix;
    }

    public void setNegativePrefix(String newValue) {
        negativePrefix = newValue;
    }

    public String getPositiveSuffix() {
        return positiveSuffix;
    }

    public void setPositiveSuffix(String newValue) {
        positiveSuffix = newValue;
    }

    public String getNegativeSuffix() {
        return negativeSuffix;
    }

    public void setNegativeSuffix(String newValue) {
        negativeSuffix = newValue;
    }

    public int getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(int newValue) {
        multiplier = newValue;
    }

    public int getGroupingSize() {
        return groupingSize;
    }

    public void setGroupingSize(int newValue) {
        groupingSize = (byte) newValue;
    }

    public boolean isDecimalSeparatorAlwaysShown() {
        return decimalSeparatorAlwaysShown;
    }

    public void setDecimalSeparatorAlwaysShown(boolean newValue) {
        decimalSeparatorAlwaysShown = newValue;
    }

    public RoundingMode getRoundingMode() {
        return roundingMode;
    }

    public void setRoundingMode(RoundingMode roundingMode) {
        if (roundingMode == null) {
            throw new NullPointerException();
        }
        this.roundingMode = roundingMode;
        digitList.setRoundingMode(roundingMode);
    }

    public boolean equals(Object obj) {
        if (!super.equals(obj)) return false;
        DecimalFormat other = (DecimalFormat) obj;
        return (positivePrefix.equals(other.positivePrefix)
            && positiveSuffix.equals(other.positiveSuffix)
            && negativePrefix.equals(other.negativePrefix)
            && negativeSuffix.equals(other.negativeSuffix)
            && multiplier == other.multiplier
            && groupingSize == other.groupingSize
            && decimalSeparatorAlwaysShown == other.decimalSeparatorAlwaysShown
            && useExponentialNotation == other.useExponentialNotation
            && symbols.equals(other.symbols));
    }

    public int hashCode() {
        return super.hashCode() * 37 + positivePrefix.hashCode();
    }

    public String toString() {
        return "DecimalFormat[" + toPattern() + "]";
    }
}
