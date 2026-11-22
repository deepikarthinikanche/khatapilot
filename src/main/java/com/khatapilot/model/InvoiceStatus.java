package com.khatapilot.model;

/**
 * Invoice status.
 * <p>
 * DRAFT, SENT, PARTLY_PAID and PAID are the stored states. OVERDUE is display-only:
 * it is derived from the due date and outstanding balance and is never persisted.
 */
public enum InvoiceStatus {
    DRAFT,
    SENT,
    PARTLY_PAID,
    PAID,
    OVERDUE
}
