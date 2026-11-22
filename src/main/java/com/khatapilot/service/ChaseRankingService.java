package com.khatapilot.service;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.Payment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks students with overdue balances by who is most worth chasing today.
 *
 * <p>Score formula:
 * <pre>
 *   expectedRecovery = sum of balances on the student's OVERDUE invoices
 *   avgDaysLate      = mean over the student's fully paid invoices of max(0, lastPaymentDate - dueDate)
 *                      (0.5 likelihood when the student has no paid history)
 *   likelihood       = 1 / (1 + avgDaysLate / 10.0)     (0 days -> 1.0, 10 days -> 0.5, 20 days -> 1/3)
 *   score            = expectedRecovery * likelihood
 * </pre>
 * Results are sorted by score descending, ties broken by student name, so output is deterministic for the same inputs.
 */
@Service
public class ChaseRankingService {

    /** One ranked chase target with its chase message. */
    public record ChaseSuggestion(String studentName, BigDecimal overdueAmount, int daysOverdue,
                                  double likelihood, double score, String message) {
    }

    private static final double NO_HISTORY_LIKELIHOOD = 0.5;
    private static final double DELAY_HALF_LIFE_DAYS = 10.0;

    private final InrFormatter inrFormatter;

    public ChaseRankingService(InrFormatter inrFormatter) {
        this.inrFormatter = inrFormatter;
    }

    /**
     * Returns up to {@code limit} students with overdue balances, highest score first.
     * Students with no OVERDUE invoice (as of {@code today}) are excluded.
     */
    public List<ChaseSuggestion> topChases(List<Invoice> invoices, LocalDate today, int limit) {
        Map<String, List<Invoice>> byStudent = new LinkedHashMap<>();
        for (Invoice invoice : invoices) {
            byStudent.computeIfAbsent(invoice.getStudent().getName(), k -> new ArrayList<>()).add(invoice);
        }

        List<ChaseSuggestion> suggestions = new ArrayList<>();
        for (Map.Entry<String, List<Invoice>> entry : byStudent.entrySet()) {
            ChaseSuggestion suggestion = suggestionFor(entry.getKey(), entry.getValue(), today);
            if (suggestion != null) {
                suggestions.add(suggestion);
            }
        }

        return suggestions.stream()
                .sorted(Comparator.comparingDouble(ChaseSuggestion::score).reversed()
                        .thenComparing(ChaseSuggestion::studentName))
                .limit(Math.max(0, limit))
                .toList();
    }

    /** Likelihood of paying soon given the average days late on past invoices. */
    public static double likelihoodForAverageDelay(double avgDaysLate) {
        return 1.0 / (1.0 + avgDaysLate / DELAY_HALF_LIFE_DAYS);
    }

    private ChaseSuggestion suggestionFor(String studentName, List<Invoice> studentInvoices, LocalDate today) {
        BigDecimal overdueTotal = BigDecimal.ZERO;
        int maxDaysOverdue = 0;
        boolean anyOverdue = false;
        for (Invoice invoice : studentInvoices) {
            if (invoice.effectiveStatus(today) == InvoiceStatus.OVERDUE) {
                anyOverdue = true;
                overdueTotal = overdueTotal.add(invoice.balance());
                int days = (int) ChronoUnit.DAYS.between(invoice.getDueDate(), today);
                maxDaysOverdue = Math.max(maxDaysOverdue, days);
            }
        }
        if (!anyOverdue) {
            return null;
        }

        double likelihood = likelihoodFromHistory(studentInvoices);
        double score = overdueTotal.doubleValue() * likelihood;
        String message = "Chase " + studentName + " today: " + inrFormatter.format(overdueTotal)
                + " overdue " + maxDaysOverdue + " days";
        return new ChaseSuggestion(studentName, overdueTotal, maxDaysOverdue, likelihood, score, message);
    }

    private static double likelihoodFromHistory(List<Invoice> studentInvoices) {
        long totalDaysLate = 0;
        int paidCount = 0;
        for (Invoice invoice : studentInvoices) {
            if (invoice.amountPaid().signum() > 0 && invoice.balance().signum() <= 0) {
                LocalDate lastPaid = invoice.getPayments().stream()
                        .map(Payment::getPaymentDate)
                        .max(Comparator.naturalOrder())
                        .orElse(invoice.getDueDate());
                totalDaysLate += Math.max(0, ChronoUnit.DAYS.between(invoice.getDueDate(), lastPaid));
                paidCount++;
            }
        }
        if (paidCount == 0) {
            return NO_HISTORY_LIKELIHOOD;
        }
        return likelihoodForAverageDelay((double) totalDaysLate / paidCount);
    }
}
