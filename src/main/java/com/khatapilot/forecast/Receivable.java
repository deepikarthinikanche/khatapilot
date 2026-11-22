package com.khatapilot.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;

/** An open invoice still to be collected; {@code balance} is net of any partial payments. */
public record Receivable(long invoiceId, long studentId, BigDecimal balance, LocalDate dueDate) {
}

