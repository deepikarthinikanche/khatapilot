package com.khatapilot.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Formats amounts as Indian-grouped rupee strings, e.g. ₹1,23,456.00.
 * Grouping is done manually (last 3 digits, then groups of 2) so no JVM locale data is needed.
 */
@Component("inr")
public class InrFormatter {

    private static final String SYMBOL = "\u20B9";

    public String format(BigDecimal amount) {
        if (amount == null) {
            return SYMBOL + "0.00";
        }
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        String plain = rounded.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String fraction = plain.substring(dot + 1);
        return (negative ? "-" : "") + SYMBOL + groupIndian(whole) + "." + fraction;
    }

    private static String groupIndian(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rest.length(); i++) {
            // place a separator before every pair of digits counted from the right
            if (i > 0 && (rest.length() - i) % 2 == 0) {
                sb.append(',');
            }
            sb.append(rest.charAt(i));
        }
        return sb.append(',').append(last3).toString();
    }
}
