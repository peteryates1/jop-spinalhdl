/*
 * Simplified DecimalFormatSymbols for JOP. Locale-independent (US English
 * defaults). No ResourceBundle, no Currency, no sun.* dependencies.
 * Based on OpenJDK 6.
 * Original: Copyright (c) 1996, 2006, Oracle and/or its affiliates.
 * Licensed under GPL v2 with Classpath exception.
 */
package java.text;

public class DecimalFormatSymbols {

    private char zeroDigit = '0';
    private char groupingSeparator = ',';
    private char decimalSeparator = '.';
    private char perMill = '\u2030';
    private char percent = '%';
    private char digit = '#';
    private char patternSeparator = ';';
    private String infinity = "\u221E";
    private String NaN = "NaN";
    private char minusSign = '-';
    private String currencySymbol = "$";
    private String intlCurrencySymbol = "USD";
    private char monetarySeparator = '.';
    private char exponential = 'E';
    private String exponentialSeparator = "E";

    public DecimalFormatSymbols() {
    }

    public char getZeroDigit() {
        return zeroDigit;
    }

    public void setZeroDigit(char zeroDigit) {
        this.zeroDigit = zeroDigit;
    }

    public char getGroupingSeparator() {
        return groupingSeparator;
    }

    public void setGroupingSeparator(char groupingSeparator) {
        this.groupingSeparator = groupingSeparator;
    }

    public char getDecimalSeparator() {
        return decimalSeparator;
    }

    public void setDecimalSeparator(char decimalSeparator) {
        this.decimalSeparator = decimalSeparator;
    }

    public char getPerMill() {
        return perMill;
    }

    public void setPerMill(char perMill) {
        this.perMill = perMill;
    }

    public char getPercent() {
        return percent;
    }

    public void setPercent(char percent) {
        this.percent = percent;
    }

    public char getDigit() {
        return digit;
    }

    public void setDigit(char digit) {
        this.digit = digit;
    }

    public char getPatternSeparator() {
        return patternSeparator;
    }

    public void setPatternSeparator(char patternSeparator) {
        this.patternSeparator = patternSeparator;
    }

    public String getInfinity() {
        return infinity;
    }

    public void setInfinity(String infinity) {
        this.infinity = infinity;
    }

    public String getNaN() {
        return NaN;
    }

    public void setNaN(String NaN) {
        this.NaN = NaN;
    }

    public char getMinusSign() {
        return minusSign;
    }

    public void setMinusSign(char minusSign) {
        this.minusSign = minusSign;
    }

    public String getCurrencySymbol() {
        return currencySymbol;
    }

    public void setCurrencySymbol(String currency) {
        currencySymbol = currency;
    }

    public String getInternationalCurrencySymbol() {
        return intlCurrencySymbol;
    }

    public void setInternationalCurrencySymbol(String currencyCode) {
        intlCurrencySymbol = currencyCode;
    }

    public char getMonetaryDecimalSeparator() {
        return monetarySeparator;
    }

    public void setMonetaryDecimalSeparator(char sep) {
        monetarySeparator = sep;
    }

    char getExponentialSymbol() {
        return exponential;
    }

    public String getExponentSeparator() {
        return exponentialSeparator;
    }

    void setExponentialSymbol(char exp) {
        exponential = exp;
    }

    public void setExponentSeparator(String exp) {
        if (exp == null) {
            throw new NullPointerException();
        }
        exponentialSeparator = exp;
    }

    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (this == obj) return true;
        if (!(obj instanceof DecimalFormatSymbols)) return false;
        DecimalFormatSymbols other = (DecimalFormatSymbols) obj;
        return (zeroDigit == other.zeroDigit &&
        groupingSeparator == other.groupingSeparator &&
        decimalSeparator == other.decimalSeparator &&
        percent == other.percent &&
        perMill == other.perMill &&
        digit == other.digit &&
        minusSign == other.minusSign &&
        patternSeparator == other.patternSeparator &&
        infinity.equals(other.infinity) &&
        NaN.equals(other.NaN) &&
        currencySymbol.equals(other.currencySymbol) &&
        intlCurrencySymbol.equals(other.intlCurrencySymbol) &&
        monetarySeparator == other.monetarySeparator &&
        exponentialSeparator.equals(other.exponentialSeparator));
    }

    public int hashCode() {
        int result = zeroDigit;
        result = result * 37 + groupingSeparator;
        result = result * 37 + decimalSeparator;
        return result;
    }
}
