package com.khatapilot.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Output of a cash forecast run.
 *
 * @param probabilityBelowZeroBeforeTarget percentage (0-100) of simulated paths that dip below zero on or before the target date
 */
public record CashForecastResult(
        LocalDate startDate,
        int horizonDays,
        LocalDate targetDate,
        BigDecimal openingBalance,
        long seed,
        int iterations,
        List<DailyForecast> days,
        double probabilityBelowZeroBeforeTarget) {

    public CashForecastResult {
        days = List.copyOf(days);
    }
}

