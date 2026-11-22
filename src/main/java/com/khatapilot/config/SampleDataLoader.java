package com.khatapilot.config;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.Student;
import com.khatapilot.repository.InvoiceRepository;
import com.khatapilot.repository.PaymentRepository;
import com.khatapilot.repository.RecurringBillRepository;
import com.khatapilot.repository.StudentRepository;
import com.khatapilot.service.AppSettingService;
import com.khatapilot.service.InvoiceService;
import com.khatapilot.service.RecurringBillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds fictional sample data on first startup only (guarded by an empty student table).
 */
@Component
public class SampleDataLoader implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleDataLoader.class);
    private static final int MONTHS = 12;

    private record SeedStudent(String name, int monthlyFee, String notes) {
    }

    private record SeedBill(String name, int amount, int dayOfMonth, int graceDays) {
    }

    private record Installment(LocalDate date, BigDecimal amount) {
    }

    private static final List<SeedStudent> SEED_STUDENTS = List.of(
            new SeedStudent("Ishita Rao", 3000, "Class 10 Math, CBSE, Mon/Wed 6-7pm"),
            new SeedStudent("Kabir Menon", 3500, "Class 12 Physics, Tue/Thu 7-8pm"),
            new SeedStudent("Meera Iyer", 2000, "Spoken English, Sat 10-11am"),
            new SeedStudent("Dhruv Patil", 2500, "Class 8 Science"),
            new SeedStudent("Tara Kulkarni", 2200, "Class 5 EVS"),
            new SeedStudent("Rohan Bhatt", 4000, "Class 12 Chemistry, Mon/Fri 7-8pm"),
            new SeedStudent("Anika Desai", 2800, "Class 9 Maths, CBSE, Tue/Thu 5-6pm"),
            new SeedStudent("Vihaan Nair", 3200, "Class 11 Biology, Wed/Sat 6-7pm"),
            new SeedStudent("Saanvi Joshi", 2400, "Class 6 English, Sat 9-10am"),
            new SeedStudent("Arjun Pillai", 4500, "Class 12 Maths, Mon/Wed/Fri 6-7pm"),
            new SeedStudent("Diya Ghosh", 2100, "Class 4 EVS, Sun 11am-12pm"),
            new SeedStudent("Reyansh Saxena", 3300, "Class 10 Science, CBSE, Tue/Thu 6-7pm"),
            new SeedStudent("Myra Chauhan", 2600, "Spoken English, Mon/Wed 5-6pm"),
            new SeedStudent("Aditya Kapoor", 3800, "Class 11 Physics, Sat 2-3pm"),
            new SeedStudent("Kiara Shetty", 2000, "Class 3 Math, Sun 10-11am"),
            new SeedStudent("Ayaan Mehta", 3600, "Class 9 Science, CBSE, Mon/Thu 7-8pm"),
            new SeedStudent("Nisha Trivedi", 2900, "Class 7 Maths, Tue/Sat 4-5pm"),
            new SeedStudent("Krish Bansal", 4200, "Class 12 Economics, Wed/Fri 7-8pm")
    );

    private static final List<SeedBill> SEED_BILLS = List.of(
            new SeedBill("Rent", 12000, 5, 0),
            new SeedBill("Internet", 999, 10, 3),
            new SeedBill("Electricity", 1800, 15, 5),
            new SeedBill("Supplies", 500, 20, 7)
    );

    /** Bad-day demo: opening cash, and the fee of each of the five overdue students. */
    private static final BigDecimal BAD_DAY_OPENING_CASH = new BigDecimal("9200.00");
    private static final List<Integer> BAD_DAY_OVERDUE_FEES = List.of(3000, 3500, 2500, 2000, 2800);
    private static final int BAD_DAY_RENT = 12000;
    private static final int BAD_DAY_RENT_DAYS_AHEAD = 4;
    /**
     * Bad-day demo: per overdue student, the days late of three PAID past invoices. Overdue invoices were due 15 days
     * ago, so a delay of 20+ days lands after the Rent date (4 days out). Students 1, 2 and 5 always pay after Rent
     * and student 3 always pays before it, so the shortfall happens only when student 4 (one of three delays past
     * Rent) also pays late: about one run in three.
     */
    private static final List<List<Integer>> BAD_DAY_PAID_HISTORY_DELAYS = List.of(
            List.of(20, 22, 25),
            List.of(21, 23, 20),
            List.of(6, 12, 18),
            List.of(9, 15, 20),
            List.of(20, 22, 24));

    private final StudentRepository studentRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final RecurringBillRepository recurringBillRepository;
    private final InvoiceService invoiceService;
    private final RecurringBillService recurringBillService;
    private final AppSettingService appSettingService;

    public SampleDataLoader(StudentRepository studentRepository,
                            InvoiceRepository invoiceRepository,
                            PaymentRepository paymentRepository,
                            RecurringBillRepository recurringBillRepository,
                            InvoiceService invoiceService,
                            RecurringBillService recurringBillService,
                            AppSettingService appSettingService) {
        this.studentRepository = studentRepository;
        this.invoiceRepository = invoiceRepository;
        this.paymentRepository = paymentRepository;
        this.recurringBillRepository = recurringBillRepository;
        this.invoiceService = invoiceService;
        this.recurringBillService = recurringBillService;
        this.appSettingService = appSettingService;
    }

    /**
     * Demo: replaces all data with a deterministic cash crisis. Opening cash is 9200, five students
     * are overdue on this month's invoices, and the only recurring bill is Rent of 12000 due in 4 days.
     */
    @Transactional
    public void loadBadDayDemo() {
        clearAllData();
        appSettingService.setOpeningCashBalance(BAD_DAY_OPENING_CASH);

        LocalDate today = LocalDate.now();
        YearMonth current = YearMonth.from(today);
        for (int i = 0; i < BAD_DAY_OVERDUE_FEES.size(); i++) {
            Student student = studentRepository.save(new Student("Demo Overdue " + (i + 1),
                    BigDecimal.valueOf(BAD_DAY_OVERDUE_FEES.get(i)), "Bad-day demo"));
            seedBadDayPaidHistory(student, i, current);
            Invoice invoice = invoiceService.createInvoice(student, student.getMonthlyFee(),
                    today.minusDays(30), today.minusDays(15), current.toString());
            invoiceService.markSent(invoice.getId());
        }

        // Day-of-month of (today + 4) is always the next occurrence 4 days out, even across a month end.
        recurringBillService.create("Rent", BigDecimal.valueOf(BAD_DAY_RENT),
                today.plusDays(BAD_DAY_RENT_DAYS_AHEAD).getDayOfMonth(), 0);
        log.info("Loaded bad-day demo: opening cash {}, {} overdue students, Rent {} due in {} days",
                BAD_DAY_OPENING_CASH, BAD_DAY_OVERDUE_FEES.size(), BAD_DAY_RENT, BAD_DAY_RENT_DAYS_AHEAD);
    }

    /**
     * Seeds three PAID invoices from the previous months for a bad-day demo student, each paid several days
     * after its due date, so the payment delay model has a varied, non-empty delay history for that student.
     */
    private void seedBadDayPaidHistory(Student student, int index, YearMonth current) {
        List<Integer> delays = BAD_DAY_PAID_HISTORY_DELAYS.get(index);
        for (int back = 0; back < delays.size(); back++) {
            YearMonth month = current.minusMonths(back + 1L);
            LocalDate dueDate = month.atDay(5);
            BigDecimal fee = student.getMonthlyFee();
            Invoice invoice = invoiceService.createInvoice(student, fee, month.atDay(1), dueDate, month.toString());
            invoiceService.markSent(invoice.getId());
            PaymentMethod method = PaymentMethod.values()[(index + back) % PaymentMethod.values().length];
            invoiceService.recordPayment(invoice.getId(), fee, dueDate.plusDays(delays.get(back)), method);
        }
    }

    /** Demo reset: replaces all data with the normal sample dataset and restores the default opening cash. */
    @Transactional
    public void resetToSampleData() {
        clearAllData();
        appSettingService.setOpeningCashBalance(AppSettingService.DEFAULT_OPENING_CASH_BALANCE);
        seed();
    }

    /** Payments and invoices first, then bills and students, so foreign keys are removed in order. */
    private void clearAllData() {
        paymentRepository.deleteAll();
        invoiceRepository.deleteAll();
        recurringBillRepository.deleteAll();
        studentRepository.deleteAll();
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (studentRepository.count() > 0) {
            return;
        }
        seed();
    }

    private void seed() {
        List<Student> students = new ArrayList<>();
        for (SeedStudent seed : SEED_STUDENTS) {
            students.add(studentRepository.save(
                    new Student(seed.name(), BigDecimal.valueOf(seed.monthlyFee()), seed.notes())));
        }

        for (SeedBill bill : SEED_BILLS) {
            recurringBillService.create(bill.name(), BigDecimal.valueOf(bill.amount()),
                    bill.dayOfMonth(), bill.graceDays());
        }

        LocalDate today = LocalDate.now();
        YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
        int paymentsRecorded = 0;

        for (int back = MONTHS - 1; back >= 0; back--) {
            YearMonth month = lastMonth.minusMonths(back);
            LocalDate dueDate = month.atDay(5);
            invoiceService.generateMonthlyInvoices(month, month.atDay(1), dueDate);

            for (int index = 0; index < students.size(); index++) {
                Student student = students.get(index);
                List<Invoice> invoices = invoiceRepository.findByStudentAndBillingMonth(student, month.toString());
                if (invoices.isEmpty()) {
                    continue;
                }
                Invoice invoice = invoices.get(0);
                invoiceService.markSent(invoice.getId());

                List<Installment> plan = planPayments(index, back, dueDate, invoice.getAmount());
                // If any installment would fall in the future, skip the whole plan: the invoice stays SENT.
                if (plan.stream().anyMatch(installment -> installment.date().isAfter(today))) {
                    continue;
                }
                for (int step = 0; step < plan.size(); step++) {
                    Installment installment = plan.get(step);
                    PaymentMethod method = PaymentMethod.values()[(index + back + step) % PaymentMethod.values().length];
                    invoiceService.recordPayment(invoice.getId(), installment.amount(), installment.date(), method);
                    paymentsRecorded++;
                }
            }
        }

        paymentsRecorded += seedCurrentMonth(students);

        log.info("Seeded sample data: {} students, {} months of invoices, {} payments, {} recurring bills",
                students.size(), MONTHS + 1, paymentsRecorded, SEED_BILLS.size());
    }

    /**
     * Seeds the current billing month for every student with a realistic status mix:
     * 0-8 PAID on time; 9-10 SENT and 11-12 PARTLY_PAID, due 5-10 days ago;
     * 13-14 SENT and 15 PARTLY_PAID, due 15+ days ago (OVERDUE); 16-17 left as DRAFT.
     * Due dates are clamped to no earlier than the 1st of the month, and no payment is dated after today.
     * Returns the number of payments recorded.
     */
    private int seedCurrentMonth(List<Student> students) {
        LocalDate today = LocalDate.now();
        YearMonth current = YearMonth.from(today);
        LocalDate issueDate = current.atDay(1);
        int paymentsRecorded = 0;

        for (int index = 0; index < students.size(); index++) {
            Student student = students.get(index);
            if (!invoiceRepository.findByStudentAndBillingMonth(student, current.toString()).isEmpty()) {
                continue;
            }

            LocalDate dueDate;
            if (index < 9) {
                dueDate = current.atDay(10).isBefore(today) ? current.atDay(10) : today;
            } else if (index < 13) {
                LocalDate lateDue = today.minusDays(7);
                dueDate = lateDue.isBefore(issueDate) ? issueDate : lateDue;
            } else if (index < 16) {
                LocalDate overdueDue = today.minusDays(18);
                dueDate = overdueDue.isBefore(issueDate) ? issueDate : overdueDue;
            } else {
                dueDate = current.atDay(10).isBefore(today) ? current.atDay(10) : today;
            }

            BigDecimal fee = student.getMonthlyFee();
            Invoice invoice = invoiceService.createInvoice(student, fee, issueDate, dueDate, current.toString());
            if (index >= 16) {
                continue; // DRAFT, untouched
            }
            invoiceService.markSent(invoice.getId());

            PaymentMethod method = PaymentMethod.values()[index % PaymentMethod.values().length];
            if (index < 9) {
                LocalDate paymentDate = dueDate.isAfter(today) ? today : dueDate;
                invoiceService.recordPayment(invoice.getId(), fee, paymentDate, method);
                paymentsRecorded++;
            } else if (index == 11 || index == 12) {
                BigDecimal partial = fee.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN);
                invoiceService.recordPayment(invoice.getId(), partial, dueDate, method);
                paymentsRecorded++;
            } else if (index == 15) {
                BigDecimal partial = fee.divide(BigDecimal.valueOf(4), 0, RoundingMode.DOWN);
                invoiceService.recordPayment(invoice.getId(), partial, dueDate, method);
                paymentsRecorded++;
            }
            // indices 9, 10, 13, 14 stay SENT with no payment
        }
        return paymentsRecorded;
    }

    /**
     * Payment behaviour by student index:
     * 0-7 on time (0-2 days early), 8-13 five to ten days late,
     * 14-17 fifteen to twenty days late; indices 16-17 pay in two installments every third month.
     */
    private List<Installment> planPayments(int index, int offset, LocalDate dueDate, BigDecimal fee) {
        if (index < 8) {
            return List.of(new Installment(dueDate.minusDays(index % 3), fee));
        }
        if (index < 14) {
            return List.of(new Installment(dueDate.plusDays(5 + offset % 6), fee));
        }
        LocalDate date = dueDate.plusDays(15 + offset % 6);
        if (index >= 16 && offset % 3 == 0) {
            BigDecimal first = fee.divide(BigDecimal.valueOf(2), 0, RoundingMode.DOWN);
            return List.of(
                    new Installment(date, first),
                    new Installment(date.plusDays(3), fee.subtract(first)));
        }
        return List.of(new Installment(date, fee));
    }
}
