package com.khatapilot.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A scheduled recurring bill paid out of cash on a given date. {@code graceDays} is the bill's configured
 * grace period, used when the outflow is shifted later within its grace window.
 */
public record BillOutflow(LocalDate date, BigDecimal amount, int graceDays) {

    public BillOutflow(LocalDate date, BigDecimal amount) {
        this(date, amount, 0);
    }
}


