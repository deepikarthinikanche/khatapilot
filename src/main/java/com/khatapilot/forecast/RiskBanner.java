package com.khatapilot.forecast;

import com.khatapilot.service.InrFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Plain-language warning shown on the home page when an upcoming bill may not be covered.
 *
 * @param visible whether the banner should be shown
 * @param message the banner text; empty when hidden
 */
public record RiskBanner(boolean visible, String message) {

    /** Shortfall probability (percent) that must be exceeded before the banner is shown. */
    public static final double THRESHOLD_PERCENT = 10.0;

    private static final InrFormatter INR = new InrFormatter();

    /**
     * Builds the banner for a bill.
     *
     * @param shortfallPercent chance (0-100) that the bill cannot be covered
     * @param billName         name of the bill
     * @param billAmount       amount of the bill; shown without decimals
     * @param dayOfMonth       day of month the bill is due
     */
    public static RiskBanner forProbability(double shortfallPercent, String billName,
                                            BigDecimal billAmount, int dayOfMonth) {
        if (!(shortfallPercent > THRESHOLD_PERCENT)) {
            return new RiskBanner(false, "");
        }
        long percent = Math.round(shortfallPercent);
        return new RiskBanner(true, percent + "% chance you can't cover " + billName
                + " (" + formatWholeRupees(billAmount) + ") on the " + ordinal(dayOfMonth));
    }

    static String ordinal(int day) {
        int mod100 = day % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return day + "th";
        }
        return switch (day % 10) {
            case 1 -> day + "st";
            case 2 -> day + "nd";
            case 3 -> day + "rd";
            default -> day + "th";
        };
    }

    private static String formatWholeRupees(BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.setScale(0, RoundingMode.HALF_UP);
        // InrFormatter always appends ".00" for a whole-number amount; drop it.
        String formatted = INR.format(value);
        return formatted.substring(0, formatted.length() - 3);
    }
}
