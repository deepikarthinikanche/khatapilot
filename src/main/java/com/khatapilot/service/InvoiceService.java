package com.khatapilot.service;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.Payment;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.Student;
import com.khatapilot.repository.InvoiceRepository;
import com.khatapilot.repository.PaymentRepository;
import com.khatapilot.repository.StudentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final StudentRepository studentRepository;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          PaymentRepository paymentRepository,
                          StudentRepository studentRepository) {
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.studentRepository = studentRepository;
    }

    @Transactional
    public Invoice createInvoice(Student student, BigDecimal amount, LocalDate issueDate,
                                 LocalDate dueDate, String billingMonth) {
        if (student == null) {
            throw new IllegalArgumentException("Student is required");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Invoice amount must be zero or positive");
        }
        if (issueDate == null || dueDate == null) {
            throw new IllegalArgumentException("Issue date and due date are required");
        }
        if (dueDate.isBefore(issueDate)) {
            throw new IllegalArgumentException("Due date cannot be before issue date");
        }
        return invoiceRepository.save(new Invoice(student, amount, issueDate, dueDate, billingMonth));
    }

    @Transactional
    public Invoice markSent(Long invoiceId) {
        Invoice invoice = findInvoice(invoiceId);
        InvoiceStatus status = invoice.getStatus();
        if (status == InvoiceStatus.DRAFT || status == InvoiceStatus.SENT) {
            invoice.setStatus(InvoiceStatus.SENT);
            return invoiceRepository.save(invoice);
        }
        throw new IllegalStateException("Cannot mark invoice " + invoiceId + " as sent from status " + status);
    }

    @Transactional
    public Payment recordPayment(Long invoiceId, BigDecimal amount, LocalDate paymentDate, PaymentMethod method) {
        Invoice invoice = findInvoice(invoiceId);
        if (invoice.getStatus() == InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Cannot record payment on a DRAFT invoice " + invoiceId);
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero");
        }
        if (amount.compareTo(invoice.balance()) > 0) {
            throw new IllegalArgumentException("Payment amount exceeds outstanding balance");
        }
        if (paymentDate == null || method == null) {
            throw new IllegalArgumentException("Payment date and method are required");
        }

        Payment payment = new Payment(amount, paymentDate, method);
        invoice.addPayment(payment);

        if (invoice.balance().signum() == 0) {
            invoice.setStatus(InvoiceStatus.PAID);
        } else if (invoice.amountPaid().signum() > 0) {
            invoice.setStatus(InvoiceStatus.PARTLY_PAID);
        }
        invoiceRepository.save(invoice);
        return payment;
    }

    @Transactional(readOnly = true)
    public List<Payment> paymentHistory(Long invoiceId) {
        return paymentRepository.findByInvoiceOrderByPaymentDateAscIdAsc(findInvoice(invoiceId));
    }

    @Transactional(readOnly = true)
    public InvoiceStatus statusOn(Long invoiceId, LocalDate today) {
        return findInvoice(invoiceId).effectiveStatus(today);
    }

    /**
     * Creates a DRAFT invoice for every student that has no invoice yet for the given month.
     * Idempotent: returns only the invoices created by this call.
     */
    @Transactional
    public List<Invoice> generateMonthlyInvoices(YearMonth month, LocalDate issueDate, LocalDate dueDate) {
        String billingMonth = month.toString();
        List<Invoice> created = new ArrayList<>();
        for (Student student : studentRepository.findAll()) {
            if (invoiceRepository.findByStudentAndBillingMonth(student, billingMonth).isEmpty()) {
                created.add(createInvoice(student, student.getMonthlyFee(), issueDate, dueDate, billingMonth));
            }
        }
        return created;
    }

    /** All invoices, newest due date first. */
    @Transactional(readOnly = true)
    public List<Invoice> listAll() {
        return invoiceRepository.findAll(Sort.by(Sort.Direction.DESC, "dueDate"));
    }

    /**
     * Filters invoices by effective status on {@code today} and/or exact billing month ("yyyy-MM").
     * Null or blank filters are ignored. OVERDUE matches effectiveStatus == OVERDUE; other statuses
     * match effectiveStatus(today) == status.
     */
    @Transactional(readOnly = true)
    public List<Invoice> search(InvoiceStatus status, String billingMonth, LocalDate today) {
        String month = (billingMonth == null || billingMonth.isBlank()) ? null : billingMonth.trim();
        LocalDate asOf = today == null ? LocalDate.now() : today;
        return invoiceRepository.findAll(Sort.by(Sort.Direction.DESC, "dueDate")).stream()
                .filter(inv -> status == null || inv.effectiveStatus(asOf) == status)
                .filter(inv -> month == null || month.equals(inv.getBillingMonth()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Invoice getInvoice(Long id) {
        return findInvoice(id);
    }

    private Invoice findInvoice(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));
    }
}
