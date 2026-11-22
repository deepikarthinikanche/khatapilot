package com.khatapilot.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Projected end-of-day cash balance for one day at three confidence levels. */
public record DailyForecast(LocalDate date, BigDecimal worstCase, BigDecimal mostLikely, BigDecimal bestCase) {
}

