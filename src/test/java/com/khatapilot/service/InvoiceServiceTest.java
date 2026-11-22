package com.khatapilot.service;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.Payment;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.Student;
import com.khatapilot.repository.InvoiceRepository;
import com.khatapilot.repository.StudentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class InvoiceServiceTest {

    private static final LocalDate ISSUE = LocalDate.of(2001, 1, 1);
    private static final LocalDate DUE = LocalDate.of(2001, 1, 5);
    private static final String BILLING_MONTH = "2001-01";
    private static final BigDecimal FEE = new BigDecimal("2500.00");

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private StudentRepository studentRepository;

    private Student newStudent() {
        return studentRepository.save(new Student("Test Student " + System.nanoTime(), FEE, null));
    }

    private Invoice newInvoice(Student student) {
        return invoiceService.createInvoice(student, FEE, ISSUE, DUE, BILLING_MONTH);
    }

    @Test
    void createInvoice_startsAsDraft() {
        Invoice invoice = newInvoice(newStudent());

        assertEquals(InvoiceStatus.DRAFT, invoice.getStatus());
        assertTrue(invoice.getId() != null);
    }

    @Test
    void markSent_movesDraftToSent() {
        Invoice invoice = newInvoice(newStudent());

        Invoice sent = invoiceService.markSent(invoice.getId());

        assertEquals(InvoiceStatus.SENT, sent.getStatus());
    }

    @Test
    void partialThenRemainderPayment_movesSentToPartlyPaidThenPaid() {
        Invoice invoice = newInvoice(newStudent());
        invoiceService.markSent(invoice.getId());

        invoiceService.recordPayment(invoice.getId(), new BigDecimal("1000.00"), ISSUE.plusDays(1), PaymentMethod.UPI);
        assertEquals(InvoiceStatus.PARTLY_PAID, invoiceRepository.findById(invoice.getId()).orElseThrow().getStatus());

        invoiceService.recordPayment(invoice.getId(), new BigDecimal("1500.00"), ISSUE.plusDays(2), PaymentMethod.CASH);
        Invoice paid = invoiceRepository.findById(invoice.getId()).orElseThrow();
        assertEquals(InvoiceStatus.PAID, paid.getStatus());
        assertEquals(0, paid.balance().signum());
    }

    @Test
    void statusOn_sentInvoiceIsOverdueAfterDueDateButNotOnDueDate() {
        Invoice invoice = newInvoice(newStudent());
        invoiceService.markSent(invoice.getId());

        assertEquals(InvoiceStatus.SENT, invoiceService.statusOn(invoice.getId(), DUE));
        assertEquals(InvoiceStatus.OVERDUE, invoiceService.statusOn(invoice.getId(), DUE.plusDays(1)));
    }

    @Test
    void statusOn_partlyPaidInvoiceIsOverdueAfterDueDateButNotOnDueDate() {
        Invoice invoice = newInvoice(newStudent());
        invoiceService.markSent(invoice.getId());
        invoiceService.recordPayment(invoice.getId(), new BigDecimal("1000.00"), ISSUE.plusDays(1), PaymentMethod.BANK_TRANSFER);

        assertEquals(InvoiceStatus.PARTLY_PAID, invoiceService.statusOn(invoice.getId(), DUE));
        assertEquals(InvoiceStatus.OVERDUE, invoiceService.statusOn(invoice.getId(), DUE.plusDays(1)));
    }

    @Test
    void statusOn_fullyPaidInvoiceIsPaidEvenAfterDueDate() {
        Invoice invoice = newInvoice(newStudent());
        invoiceService.markSent(invoice.getId());
        invoiceService.recordPayment(invoice.getId(), FEE, ISSUE.plusDays(10), PaymentMethod.UPI);

        assertEquals(InvoiceStatus.PAID, invoiceService.statusOn(invoice.getId(), DUE.plusDays(30)));
    }

    @Test
    void recordPayment_rejectsOverpayment() {
        Invoice invoice = newInvoice(newStudent());
        invoiceService.markSent(invoice.getId());

        assertThrows(IllegalArgumentException.class, () ->
                invoiceService.recordPayment(invoice.getId(), FEE.add(BigDecimal.ONE), ISSUE, PaymentMethod.CASH));
    }

    @Test
    void recordPayment_rejectsZeroAmount() {
        Invoice invoice = newInvoice(newStudent());
        invoiceService.markSent(invoice.getId());

        assertThrows(IllegalArgumentException.class, () ->
                invoiceService.recordPayment(invoice.getId(), BigDecimal.ZERO, ISSUE, PaymentMethod.CASH));
    }

    @Test
    void recordPayment_onDraftInvoiceThrowsIllegalState() {
        Invoice invoice = newInvoice(newStudent());

        assertThrows(IllegalStateException.class, () ->
                invoiceService.recordPayment(invoice.getId(), new BigDecimal("100.00"), ISSUE, PaymentMethod.UPI));
    }

    @Test
    void paymentHistory_returnsPaymentsInDateOrder() {
        Invoice invoice = newInvoice(newStudent());
        invoiceService.markSent(invoice.getId());
        invoiceService.recordPayment(invoice.getId(), new BigDecimal("300.00"), ISSUE.plusDays(4), PaymentMethod.UPI);
        invoiceService.recordPayment(invoice.getId(), new BigDecimal("200.00"), ISSUE.plusDays(2), PaymentMethod.CASH);

        List<Payment> history = invoiceService.paymentHistory(invoice.getId());

        assertEquals(2, history.size());
        assertEquals(ISSUE.plusDays(2), history.get(0).getPaymentDate());
        assertEquals(ISSUE.plusDays(4), history.get(1).getPaymentDate());
    }

    @Test
    void generateMonthlyInvoices_createsOneDraftPerStudentAndIsIdempotent() {
        Student student = newStudent();
        YearMonth month = YearMonth.of(1999, 7);
        LocalDate issue = month.atDay(1);
        LocalDate due = month.atDay(5);

        List<Invoice> first = invoiceService.generateMonthlyInvoices(month, issue, due);

        List<Invoice> firstForStudent = first.stream()
                .filter(inv -> inv.getStudent().getId().equals(student.getId()))
                .toList();
        assertEquals(1, firstForStudent.size());
        assertEquals(InvoiceStatus.DRAFT, firstForStudent.get(0).getStatus());
        assertEquals(0, FEE.compareTo(firstForStudent.get(0).getAmount()));
        assertEquals("1999-07", firstForStudent.get(0).getBillingMonth());

        List<Invoice> second = invoiceService.generateMonthlyInvoices(month, issue, due);

        boolean secondCreatedForStudent = second.stream()
                .anyMatch(inv -> inv.getStudent().getId().equals(student.getId()));
        assertTrue(!secondCreatedForStudent);
        assertEquals(1, invoiceRepository.findByStudentAndBillingMonth(student, "1999-07").size());
    }
}
