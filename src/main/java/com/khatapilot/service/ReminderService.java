package com.khatapilot.service;

import com.khatapilot.model.Invoice;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * Generates personalised payment reminder drafts for open or overdue invoices,
 * in three tones, plus WhatsApp share links.
 */
@Service
public class ReminderService {

    /** Reminder tone. Declared in this order: {@link #drafts(Invoice, LocalDate)} returns GENTLE, FIRM, FINAL. */
    public enum Tone {
        GENTLE,
        FIRM,
        FINAL
    }

    /** One drafted reminder with its WhatsApp share link. */
    public record ReminderDraft(Tone tone, String message, String whatsappUrl) {
    }

    private static final String WHATSAPP_PREFIX = "https://wa.me/?text=";
    private static final DateTimeFormatter DUE_FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter BATCH_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final int FIRM_DEADLINE_DAYS = 3;

    private final InrFormatter inrFormatter;

    public ReminderService(InrFormatter inrFormatter) {
        this.inrFormatter = inrFormatter;
    }

    /**
     * Drafts a reminder in the given tone for the invoice's outstanding balance.
     *
     * @throws IllegalArgumentException if nothing is outstanding on the invoice
     */
    public String draft(Invoice invoice, Tone tone, LocalDate today) {
        BigDecimal balance = invoice.balance();
        if (balance.signum() <= 0) {
            throw new IllegalArgumentException("Invoice has no outstanding balance");
        }
        String name = invoice.getStudent().getName();
        String balanceText = inrFormatter.format(balance);
        String dueText = DUE_FORMAT.format(invoice.getDueDate());
        long daysLate = Math.max(0, ChronoUnit.DAYS.between(invoice.getDueDate(), today));
        String dueClause = "due on " + dueText + (daysLate > 0
                ? " (" + daysLate + (daysLate == 1 ? " day" : " days") + " late)"
                : " (not yet late)");
        String feeFor = batchNote(invoice.getBillingMonth());

        return switch (tone) {
            case GENTLE -> "Hi " + name + ", this is a friendly reminder that " + feeFor
                    + " has a balance of " + balanceText + ", " + dueClause + ". "
                    + "If you have already paid, please ignore this message. Thank you!";
            case FIRM -> "Hi " + name + ", this is a firm reminder that " + feeFor
                    + " has a balance of " + balanceText + ", " + dueClause + ". "
                    + "Please clear the full amount by " + DUE_FORMAT.format(today.plusDays(FIRM_DEADLINE_DAYS))
                    + ". Thank you.";
            case FINAL -> "LAST NOTICE - Hi " + name + ", " + feeFor + " remains unpaid: balance "
                    + balanceText + ", " + dueClause + ". "
                    + "Please pay in full immediately to avoid escalation of this matter.";
        };
    }

    /** Drafts all three tones for the invoice, each with its WhatsApp link. */
    public List<ReminderDraft> drafts(Invoice invoice, LocalDate today) {
        return java.util.Arrays.stream(Tone.values())
                .map(tone -> {
                    String message = draft(invoice, tone, today);
                    return new ReminderDraft(tone, message, whatsappUrl(message));
                })
                .toList();
    }

    /** Builds a wa.me link with the message percent-encoded (spaces as %20, not +). */
    public static String whatsappUrl(String message) {
        return WHATSAPP_PREFIX + URLEncoder.encode(message, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String batchNote(String billingMonth) {
        if (billingMonth == null || billingMonth.isBlank()) {
            return "your fee";
        }
        String month = YearMonth.parse(billingMonth).format(BATCH_FORMAT);
        return "the fee for the " + month + " batch";
    }
}
