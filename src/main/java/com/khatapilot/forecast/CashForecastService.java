package com.khatapilot.forecast;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.RecurringBill;
import com.khatapilot.model.Student;
import com.khatapilot.repository.InvoiceRepository;
import com.khatapilot.repository.RecurringBillRepository;
import com.khatapilot.service.AppSettingService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Behavioral cash forecast over a fixed horizon. Open receivables are collected on their due date
 * plus a payment delay sampled from the student's history (a learned {@link PaymentDelayModel}).
 * Recurring bills are paid on their due date plus grace days. The starting balance comes from the
 * opening cash balance setting. Results come from a seeded Monte Carlo simulation, so the same
 * inputs and seed always produce the same forecast.
 */
@Service
public class CashForecastService {

    public static final int DEFAULT_ITERATIONS = 1000;
    private static final long SIMULATION_SEED = 42L;
    private static final DateTimeFormatter DELAY_LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final InvoiceRepository invoiceRepository;
    private final RecurringBillRepository recurringBillRepository;
    private final AppSettingService appSettingService;

    public CashForecastService(InvoiceRepository invoiceRepository,
                               RecurringBillRepository recurringBillRepository,
                               AppSettingService appSettingService) {
        this.invoiceRepository = invoiceRepository;
        this.recurringBillRepository = recurringBillRepository;
        this.appSettingService = appSettingService;
    }

    public CashForecastResult forecast(LocalDate startDate, int horizonDays, LocalDate targetDate, long seed) {
        return forecast(startDate, horizonDays, targetDate, DEFAULT_ITERATIONS, seed);
    }

    /**
     * Forecasts daily cash balances from {@code startDate} over {@code horizonDays} days.
     * Recurring bill outflows are dated at due date plus grace days; grace is assumed fully used,
     * so the outflow lands on that later date.
     */
    public CashForecastResult forecast(LocalDate startDate, int horizonDays, LocalDate targetDate,
                                       int iterations, long seed) {
        ForecastInputs inputs = inputs(startDate, horizonDays);
        return run(inputs, startDate, horizonDays, targetDate, iterations, seed);
    }

    /**
     * Immutable snapshot of everything the simulation needs apart from the run parameters:
     * opening balance, open receivables, dated bill outflows and the learned payment delay model.
     */
    public record ForecastInputs(BigDecimal opening, List<Receivable> receivables, List<BillOutflow> bills,
                                 PaymentDelayModel delayModel) {}

    /**
     * Builds the forecast inputs from the repositories and settings for the window starting at
     * {@code startDate} and lasting {@code horizonDays} days.
     */
    public ForecastInputs inputs(LocalDate startDate, int horizonDays) {
        Objects.requireNonNull(startDate, "startDate");
        LocalDate endDate = startDate.plusDays(Math.max(horizonDays, 1) - 1L);

        List<Receivable> receivables = new ArrayList<>();
        List<PaidInvoiceHistory> history = new ArrayList<>();
        for (Invoice invoice : invoiceRepository.findAll()) {
            if (invoice.getStudent() == null || invoice.getStudent().getId() == null || invoice.getId() == null) {
                continue;
            }
            long studentId = invoice.getStudent().getId();
            InvoiceStatus status = invoice.getStatus();
            if ((status == InvoiceStatus.DRAFT || status == InvoiceStatus.SENT || status == InvoiceStatus.PARTLY_PAID)
                    && invoice.balance().signum() > 0) {
                receivables.add(new Receivable(invoice.getId(), studentId, invoice.balance(), invoice.getDueDate()));
            } else if (status == InvoiceStatus.PAID && !invoice.getPayments().isEmpty()) {
                var last = invoice.getPayments().get(invoice.getPayments().size() - 1);
                history.add(new PaidInvoiceHistory(studentId, invoice.getDueDate(), last.getPaymentDate()));
            }
        }

        PaymentDelayModel delayModel = PaymentDelayModel.learn(history);

        List<BillOutflow> bills = new ArrayList<>();
        for (RecurringBill bill : recurringBillRepository.findAll()) {
            int grace = bill.getGraceDays();
            for (YearMonth ym = YearMonth.from(startDate.minusDays(grace + 31L));
                 !ym.isAfter(YearMonth.from(endDate));
                 ym = ym.plusMonths(1)) {
                LocalDate outflow = bill.dueDateFor(ym).plusDays(grace);
                if (!outflow.isBefore(startDate) && !outflow.isAfter(endDate)) {
                    bills.add(new BillOutflow(outflow, bill.getAmount(), grace));

                }
            }
        }

        BigDecimal opening = appSettingService.getOpeningCashBalance();
        return new ForecastInputs(opening, receivables, bills, delayModel);
    }

    /**
     * Runs the seeded Monte Carlo simulation over prebuilt inputs. Same inputs, seed and
     * parameters always give the same result.
     */
    public CashForecastResult run(ForecastInputs inputs, LocalDate startDate, int horizonDays,
                                  LocalDate targetDate, int iterations, long seed) {
        return MonteCarloCashSimulator.simulate(inputs.opening(), startDate, horizonDays, targetDate,
                inputs.receivables(), inputs.bills(), inputs.delayModel(), iterations, seed);
    }

    /**
     * Returns a copy of the receivables in which the given student's invoices are due {@code delayDays}
     * later. Other receivables are returned unchanged.
     */
    public static List<Receivable> delayStudent(List<Receivable> receivables, long studentId, int delayDays) {
        List<Receivable> out = new ArrayList<>(receivables.size());
        for (Receivable r : receivables) {
            if (r.studentId() == studentId) {
                out.add(new Receivable(r.invoiceId(), r.studentId(), r.balance(), r.dueDate().plusDays(delayDays)));
            } else {
                out.add(r);
            }
        }
        return out;
    }

    /** Distinct students (by id) with at least one open invoice carrying a positive balance, sorted by name. */
    public List<Student> studentsWithOpenInvoices() {
        Map<Long, Student> byId = new LinkedHashMap<>();
        for (Invoice invoice : invoiceRepository.findAll()) {
            Student student = invoice.getStudent();
            if (student == null || student.getId() == null) {
                continue;
            }
            InvoiceStatus status = invoice.getStatus();
            if ((status == InvoiceStatus.DRAFT || status == InvoiceStatus.SENT || status == InvoiceStatus.PARTLY_PAID)
                    && invoice.balance().signum() > 0) {
                byId.putIfAbsent(student.getId(), student);
            }
        }
        List<Student> students = new ArrayList<>(byId.values());
        students.sort(Comparator.comparing(Student::getName, Comparator.nullsLast(Comparator.naturalOrder())));
        return students;
    }

    /** Daily p10/p50/p90 balance series with "d MMM" labels. */
    public record DelaySeries(List<String> labels, List<Double> p10, List<Double> p50, List<Double> p90) {}

    /** Before/after comparison for a delay scenario, with change fields computed as after minus before. */
    public record DelayScenarioResult(DelaySeries before, DelaySeries after,
                                      double worstCaseBefore, double worstCaseAfter, double worstCaseChange,
                                      double shortfallBefore, double shortfallAfter, double shortfallChange,
                                      BigDecimal totalOwed) {}

    /**
     * Runs the forecast twice with the same seed: once as-is and once with one student's invoices pushed
     * back by {@code delayDays}. A null {@code studentId} leaves the receivables unchanged in the second run.
     */
    public DelayScenarioResult simulateDelay(LocalDate today, int horizonDays, LocalDate targetDate,
                                             Long studentId, int delayDays) {
        ForecastInputs in = inputs(today, horizonDays);
        LocalDate t = (targetDate != null && targetDate.isAfter(today.plusDays(horizonDays - 1L))) ? null : targetDate;

        CashForecastResult before = run(in, today, horizonDays, t, DEFAULT_ITERATIONS, SIMULATION_SEED);
        List<Receivable> shiftedReceivables = studentId == null
                ? in.receivables()
                : delayStudent(in.receivables(), studentId, delayDays);
        ForecastInputs shifted = new ForecastInputs(in.opening(), shiftedReceivables, in.bills(), in.delayModel());
        CashForecastResult after = run(shifted, today, horizonDays, t, DEFAULT_ITERATIONS, SIMULATION_SEED);

        double worstBefore = worstCase(before);
        double worstAfter = worstCase(after);
        double shortfallBefore = before.probabilityBelowZeroBeforeTarget();
        double shortfallAfter = after.probabilityBelowZeroBeforeTarget();

        BigDecimal totalOwed = BigDecimal.ZERO;
        for (Receivable r : in.receivables()) {
            totalOwed = totalOwed.add(r.balance());
        }
        totalOwed = totalOwed.setScale(2, RoundingMode.HALF_UP);

        return new DelayScenarioResult(
                toDelaySeries(before), toDelaySeries(after),
                worstBefore, worstAfter, worstAfter - worstBefore,
                shortfallBefore, shortfallAfter, shortfallAfter - shortfallBefore,
                totalOwed);
    }

    /** Lowest daily worst-case (p10) balance over the horizon, or 0.0 when there are no days. */
    private static double worstCase(CashForecastResult result) {
        if (result.days().isEmpty()) {
            return 0.0;
        }
        double min = Double.POSITIVE_INFINITY;
        for (DailyForecast day : result.days()) {
            min = Math.min(min, day.worstCase().doubleValue());
        }
        return min;
    }

    private static DelaySeries toDelaySeries(CashForecastResult result) {
        List<String> labels = new ArrayList<>();
        List<Double> p10 = new ArrayList<>();
        List<Double> p50 = new ArrayList<>();
        List<Double> p90 = new ArrayList<>();
        for (DailyForecast day : result.days()) {
            labels.add(day.date().format(DELAY_LABEL_FORMAT));
            p10.add(day.worstCase().doubleValue());
            p50.add(day.mostLikely().doubleValue());
            p90.add(day.bestCase().doubleValue());
        }
        return new DelaySeries(labels, p10, p50, p90);
    }
}
