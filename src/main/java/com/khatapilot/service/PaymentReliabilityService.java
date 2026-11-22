package com.khatapilot.service;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.Payment;
import com.khatapilot.model.Student;
import com.khatapilot.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@Service
public class PaymentReliabilityService {

    public enum Reliability {
        ON_TIME("On Time"),
        LATE_5_10("5\u201310 Days Late"),
        LATE_15_PLUS("15+ Days Late");

        private final String label;

        Reliability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Number of most recent paid invoices that drive the reliability rating. */
    private static final int WINDOW = 3;

    private final InvoiceRepository invoiceRepository;

    public PaymentReliabilityService(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /**
     * Rule:
     * <ol>
     *   <li>Take the student's invoices ordered by dueDate descending, keeping only those with at least one payment,
     *       and use the first 3 of them.</li>
     *   <li>For each, days late = (latest payment date on that invoice) minus dueDate, floored at 0.</li>
     *   <li>Average the days late (integer, rounded down).</li>
     *   <li>avg &gt;= 15 -> LATE_15_PLUS; avg &gt;= 5 -> LATE_5_10; otherwise ON_TIME.</li>
     *   <li>A student with no paid invoices is ON_TIME.</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public Reliability reliabilityOf(Student student) {
        List<Invoice> paid = invoiceRepository.findByStudentOrderByDueDateDesc(student).stream()
                .filter(inv -> !inv.getPayments().isEmpty())
                .limit(WINDOW)
                .toList();
        if (paid.isEmpty()) {
            return Reliability.ON_TIME;
        }

        long totalDaysLate = 0;
        for (Invoice invoice : paid) {
            LocalDate latestPayment = invoice.getPayments().stream()
                    .map(Payment::getPaymentDate)
                    .max(Comparator.naturalOrder())
                    .orElse(invoice.getDueDate());
            totalDaysLate += Math.max(0, ChronoUnit.DAYS.between(invoice.getDueDate(), latestPayment));
        }
        long avg = totalDaysLate / paid.size();

        if (avg >= 15) {
            return Reliability.LATE_15_PLUS;
        }
        if (avg >= 5) {
            return Reliability.LATE_5_10;
        }
        return Reliability.ON_TIME;
    }
}
