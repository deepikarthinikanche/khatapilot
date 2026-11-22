package com.khatapilot.forecast;

import java.time.LocalDate;

/** A previously settled invoice, used to learn how late a student typically pays. */
public record PaidInvoiceHistory(long studentId, LocalDate dueDate, LocalDate lastPaymentDate) {
}

