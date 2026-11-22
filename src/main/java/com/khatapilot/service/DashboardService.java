package com.khatapilot.service;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.Payment;
import com.khatapilot.model.RecurringBill;
import com.khatapilot.model.Student;
import com.khatapilot.repository.InvoiceRepository;
import com.khatapilot.repository.PaymentRepository;
import com.khatapilot.repository.RecurringBillRepository;
import com.khatapilot.repository.StudentRepository;
import com.khatapilot.service.ChaseRankingService.ChaseSuggestion;
import com.khatapilot.service.PaymentReliabilityService.Reliability;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class DashboardService {

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final int TREND_MONTHS = 12;
    private static final String BUCKET_1_TO_7 = "1–7 days";
    private static final String BUCKET_8_TO_30 = "8–30 days";
    private static final String BUCKET_31_PLUS = "31+ days";

    public record Summary(BigDecimal expectedMonthlyFees,
                          BigDecimal collectedThisMonth,
                          BigDecimal pendingDues,
                          BigDecimal overdueDues,
                          BigDecimal upcomingBillsTotal,
                          BigDecimal expectedCoverage,
                          boolean shortfallRisk,
                          BigDecimal shortfallAmount,
                          List<LateStudent> late5to10,
                          List<LateStudent> late15Plus,
                          List<BillLine> bills,
                          List<MonthPoint> trend,
                          List<StatusCount> statusBreakdown,
                          List<UrgencyBucket> urgencyBuckets,
                          List<ReminderRow> reminderRows,
                          List<ChaseSuggestion> chaseSuggestions) {
    }

    /** Overdue balance grouped by days overdue; label is one of the BUCKET_* labels. */
    public record UrgencyBucket(String label, BigDecimal amount, int count) {
    }

    /** One overdue invoice with its three-tone reminder drafts and WhatsApp links. */
    public record ReminderRow(String studentName, String billingMonth, LocalDate dueDate, int daysLate,
                              BigDecimal balance,
                              String gentleText, String firmText, String finalText,
                              String gentleWaUrl, String firmWaUrl, String finalWaUrl) {
    }

    public record LateStudent(String name, String reliabilityLabel) {
    }

    public record BillLine(String name, BigDecimal amount, int dayOfMonth, LocalDate dueDate) {
    }

    public record MonthPoint(String month, BigDecimal billed, BigDecimal collected) {
    }

    public record StatusCount(String status, long count) {
    }

    private final StudentRepository studentRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RecurringBillRepository recurringBillRepository;
    private final PaymentReliabilityService paymentReliabilityService;
    private final ReminderService reminderService;
    private final ChaseRankingService chaseRankingService;

    public DashboardService(StudentRepository studentRepository,
                            InvoiceRepository invoiceRepository,
                            PaymentRepository paymentRepository,
                            RecurringBillRepository recurringBillRepository,
                            PaymentReliabilityService paymentReliabilityService,
                            ReminderService reminderService,
                            ChaseRankingService chaseRankingService) {
        this.studentRepository = studentRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.recurringBillRepository = recurringBillRepository;
        this.paymentReliabilityService = paymentReliabilityService;
        this.reminderService = reminderService;
        this.chaseRankingService = chaseRankingService;
    }

    /**
     * Builds the home dashboard figures as of {@code today}.
     * <p>
     * Cash Co-Pilot rule: expectedCoverage = collectedThisMonth + pendingDues.
     * shortfallRisk = (collectedThisMonth &lt; upcomingBillsTotal) OR (expectedCoverage &lt; upcomingBillsTotal).
     * shortfallAmount = max(0, upcomingBillsTotal - expectedCoverage).
     */
    @Transactional(readOnly = true)
    public Summary summary(LocalDate today) {
        YearMonth thisMonth = YearMonth.from(today);
        List<Student> students = studentRepository.findAll();
        List<Invoice> invoices = invoiceRepository.findAll();
        List<Payment> payments = paymentRepository.findAll();
        List<RecurringBill> bills = recurringBillRepository.findAll();

        BigDecimal expectedMonthlyFees = sum(students.stream().map(Student::getMonthlyFee));

        BigDecimal collectedThisMonth = sum(payments.stream()
                .filter(p -> thisMonth.equals(YearMonth.from(p.getPaymentDate())))
                .map(Payment::getAmount));

        BigDecimal pendingDues = sum(invoices.stream()
                .filter(inv -> isPending(inv.effectiveStatus(today)))
                .map(Invoice::balance));

        BigDecimal overdueDues = sum(invoices.stream()
                .filter(inv -> inv.effectiveStatus(today) == InvoiceStatus.OVERDUE)
                .map(Invoice::balance));

        BigDecimal upcomingBillsTotal = sum(bills.stream().map(RecurringBill::getAmount));

        BigDecimal expectedCoverage = collectedThisMonth.add(pendingDues);
        boolean shortfallRisk = collectedThisMonth.compareTo(upcomingBillsTotal) < 0
                || expectedCoverage.compareTo(upcomingBillsTotal) < 0;
        BigDecimal shortfallAmount = upcomingBillsTotal.subtract(expectedCoverage).max(BigDecimal.ZERO);

        List<LateStudent> late5to10 = new ArrayList<>();
        List<LateStudent> late15Plus = new ArrayList<>();
        for (Student student : students.stream()
                .sorted(Comparator.comparing(Student::getName, String.CASE_INSENSITIVE_ORDER))
                .toList()) {
            Reliability reliability = paymentReliabilityService.reliabilityOf(student);
            if (reliability == Reliability.LATE_5_10) {
                late5to10.add(new LateStudent(student.getName(), reliability.label()));
            } else if (reliability == Reliability.LATE_15_PLUS) {
                late15Plus.add(new LateStudent(student.getName(), reliability.label()));
            }
        }

        List<BillLine> billLines = bills.stream()
                .map(b -> new BillLine(b.getName(), b.getAmount(), b.getDayOfMonth(), b.dueDateFor(thisMonth)))
                .sorted(Comparator.comparing(BillLine::dueDate).thenComparing(BillLine::name))
                .toList();

        List<MonthPoint> trend = new ArrayList<>();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            YearMonth month = thisMonth.minusMonths(i);
            String key = month.toString();
            BigDecimal billed = sum(invoices.stream()
                    .filter(inv -> key.equals(inv.getBillingMonth()))
                    .map(Invoice::getAmount));
            BigDecimal collected = sum(payments.stream()
                    .filter(p -> month.equals(YearMonth.from(p.getPaymentDate())))
                    .map(Payment::getAmount));
            trend.add(new MonthPoint(month.format(MONTH_LABEL), billed, collected));
        }

        Map<InvoiceStatus, Long> counts = new EnumMap<>(InvoiceStatus.class);
        for (Invoice inv : invoices) {
            counts.merge(inv.effectiveStatus(today), 1L, Long::sum);
        }
        List<StatusCount> statusBreakdown = List.of(
                new StatusCount("Paid", counts.getOrDefault(InvoiceStatus.PAID, 0L)),
                new StatusCount("Partly Paid", counts.getOrDefault(InvoiceStatus.PARTLY_PAID, 0L)),
                new StatusCount("Sent", counts.getOrDefault(InvoiceStatus.SENT, 0L)),
                new StatusCount("Overdue", counts.getOrDefault(InvoiceStatus.OVERDUE, 0L)),
                new StatusCount("Draft", counts.getOrDefault(InvoiceStatus.DRAFT, 0L)));

        List<Invoice> overdueInvoices = invoices.stream()
                .filter(inv -> inv.effectiveStatus(today) == InvoiceStatus.OVERDUE && inv.balance().signum() > 0)
                .toList();
        List<UrgencyBucket> urgencyBuckets = buildUrgencyBuckets(overdueInvoices, today);
        List<ReminderRow> reminderRows = overdueInvoices.stream()
                .sorted(Comparator.comparingLong((Invoice inv) -> daysLate(inv, today)).reversed()
                        .thenComparing((Invoice inv) -> inv.getStudent().getName(), String.CASE_INSENSITIVE_ORDER))
                .map(inv -> reminderRow(inv, today))
                .toList();
        List<ChaseSuggestion> chaseSuggestions = chaseRankingService.topChases(invoices, today, 3);

        return new Summary(
                expectedMonthlyFees,
                collectedThisMonth,
                pendingDues,
                overdueDues,
                upcomingBillsTotal,
                expectedCoverage,
                shortfallRisk,
                shortfallAmount,
                late5to10,
                late15Plus,
                billLines,
                trend,
                statusBreakdown,
                urgencyBuckets,
                reminderRows,
                chaseSuggestions);
    }

    private ReminderRow reminderRow(Invoice inv, LocalDate today) {
        String gentle = reminderService.draft(inv, ReminderService.Tone.GENTLE, today);
        String firm = reminderService.draft(inv, ReminderService.Tone.FIRM, today);
        String finalText = reminderService.draft(inv, ReminderService.Tone.FINAL, today);
        String billingMonth = inv.getBillingMonth() == null || inv.getBillingMonth().isBlank()
                ? ""
                : YearMonth.parse(inv.getBillingMonth()).format(MONTH_LABEL);
        return new ReminderRow(
                inv.getStudent().getName(),
                billingMonth,
                inv.getDueDate(),
                (int) daysLate(inv, today),
                inv.balance(),
                gentle,
                firm,
                finalText,
                ReminderService.whatsappUrl(gentle),
                ReminderService.whatsappUrl(firm),
                ReminderService.whatsappUrl(finalText));
    }

    private static List<UrgencyBucket> buildUrgencyBuckets(List<Invoice> overdueInvoices, LocalDate today) {
        BigDecimal amount1to7 = BigDecimal.ZERO;
        BigDecimal amount8to30 = BigDecimal.ZERO;
        BigDecimal amount31Plus = BigDecimal.ZERO;
        int count1to7 = 0;
        int count8to30 = 0;
        int count31Plus = 0;
        for (Invoice inv : overdueInvoices) {
            long days = daysLate(inv, today);
            if (days < 1) {
                continue;
            }
            if (days <= 7) {
                amount1to7 = amount1to7.add(inv.balance());
                count1to7++;
            } else if (days <= 30) {
                amount8to30 = amount8to30.add(inv.balance());
                count8to30++;
            } else {
                amount31Plus = amount31Plus.add(inv.balance());
                count31Plus++;
            }
        }
        return List.of(
                new UrgencyBucket(BUCKET_1_TO_7, amount1to7, count1to7),
                new UrgencyBucket(BUCKET_8_TO_30, amount8to30, count8to30),
                new UrgencyBucket(BUCKET_31_PLUS, amount31Plus, count31Plus));
    }

    private static long daysLate(Invoice inv, LocalDate today) {
        return ChronoUnit.DAYS.between(inv.getDueDate(), today);
    }

    private static boolean isPending(InvoiceStatus status) {
        return status == InvoiceStatus.SENT
                || status == InvoiceStatus.PARTLY_PAID
                || status == InvoiceStatus.OVERDUE;
    }

    private static BigDecimal sum(Stream<BigDecimal> values) {
        return values.reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
