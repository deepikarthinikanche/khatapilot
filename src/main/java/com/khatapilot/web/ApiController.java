package com.khatapilot.web;

import com.khatapilot.backtest.BacktestService;
import com.khatapilot.backtest.BacktestService.BacktestResult;
import com.khatapilot.config.DemoLock;
import com.khatapilot.config.SampleDataLoader;
import com.khatapilot.forecast.CashForecastResult;
import com.khatapilot.forecast.CashForecastService;
import com.khatapilot.forecast.CashForecastService.DelayScenarioResult;
import com.khatapilot.forecast.DailyForecast;
import com.khatapilot.forecast.RescuePlanService;
import com.khatapilot.forecast.RescuePlanService.Action;
import com.khatapilot.forecast.RescuePlanService.RescuePlan;
import com.khatapilot.model.Invoice;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.RecurringBill;
import com.khatapilot.model.Student;
import com.khatapilot.repository.RecurringBillRepository;
import com.khatapilot.repository.StudentRepository;
import com.khatapilot.service.DashboardService;
import com.khatapilot.service.DashboardService.Summary;
import com.khatapilot.service.InvoiceService;
import com.khatapilot.service.RecurringBillService;
import com.khatapilot.service.ReminderService;
import com.khatapilot.service.StudentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JSON API for the single-page dashboard. Percent values from the services (0-100) are exposed as
 * fractions (0-1) under keys named {@code *Prob}. Money values are passed through unchanged.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private static final long FORECAST_SEED = 42L;
    private static final int DEFAULT_HORIZON = 30;
    private static final int MAX_HORIZON = 365;
    private static final int DEFAULT_DELAY_DAYS = 15;
    private static final int MAX_DELAY_DAYS = 365;
    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    /** Payment request body. Null {@code date} defaults to today, null {@code method} defaults to UPI. */
    public record PaymentRequest(BigDecimal amount, LocalDate date, PaymentMethod method) {}

    /** Invoice creation body. */
    public record InvoiceRequest(Long studentId, BigDecimal amount, LocalDate issueDate, LocalDate dueDate,
                                 String billingMonth) {}

    /** Student creation body. */
    public record StudentRequest(String name, BigDecimal monthlyFee, String notes) {}

    /** Recurring bill creation body. */
    public record BillRequest(String name, BigDecimal amount, int dayOfMonth, int graceDays) {}

    private final CashForecastService cashForecastService;
    private final RescuePlanService rescuePlanService;
    private final BacktestService backtestService;
    private final DashboardService dashboardService;
    private final DemoLock demoLock;
    private final SampleDataLoader sampleDataLoader;
    private final InvoiceService invoiceService;
    private final StudentService studentService;
    private final StudentRepository studentRepository;
    private final RecurringBillService recurringBillService;
    private final RecurringBillRepository recurringBillRepository;

    public ApiController(CashForecastService cashForecastService,
                         RescuePlanService rescuePlanService,
                         BacktestService backtestService,
                         DashboardService dashboardService,
                         DemoLock demoLock,
                         SampleDataLoader sampleDataLoader,
                         InvoiceService invoiceService,
                         StudentService studentService,
                         StudentRepository studentRepository,
                         RecurringBillService recurringBillService,
                         RecurringBillRepository recurringBillRepository) {
        this.cashForecastService = cashForecastService;
        this.rescuePlanService = rescuePlanService;
        this.backtestService = backtestService;
        this.dashboardService = dashboardService;
        this.demoLock = demoLock;
        this.sampleDataLoader = sampleDataLoader;
        this.invoiceService = invoiceService;
        this.studentService = studentService;
        this.studentRepository = studentRepository;
        this.recurringBillService = recurringBillService;
        this.recurringBillRepository = recurringBillRepository;
    }

    // ---------------------------------------------------------------- dashboard

    /**
     * Dashboard payload. Keys: base, baseline, preview, openingCash, riskBannerText, rescueCards, chase, kpis, monthly.
     * {@code preview} is null unless {@code whatIfStudentId} or {@code whatIfDelayDays} is supplied.
     * {@code activeCardIds} marks matching rescue cards with {@code active: true}; it does not change any math.
     */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(defaultValue = "30") int horizon,
                                         @RequestParam(required = false) Long whatIfStudentId,
                                         @RequestParam(required = false) Integer whatIfDelayDays,
                                         @RequestParam(required = false) String activeCardIds) {
        if (horizon < 1 || horizon > MAX_HORIZON) {
            throw new IllegalArgumentException("horizon must be between 1 and " + MAX_HORIZON);
        }
        LocalDate today = LocalDate.now();
        LocalDate target = rescueTarget(today);
        // The simulator rejects a target outside its horizon, so pass it only when it falls inside.
        LocalDate horizonTarget = target != null && !target.isAfter(today.plusDays(horizon - 1L)) ? target : null;

        CashForecastResult forecast = cashForecastService.forecast(today, horizon, horizonTarget, FORECAST_SEED);
        RescuePlan plan = rescuePlanService.plan(today, horizonTarget, horizon);
        Summary summary = dashboardService.summary(today);

        Map<String, Object> base = new LinkedHashMap<>();
        List<String> labels = new ArrayList<>();
        List<Double> p10 = new ArrayList<>();
        List<Double> p50 = new ArrayList<>();
        List<Double> p90 = new ArrayList<>();
        for (DailyForecast day : forecast.days()) {
            labels.add(day.date().format(LABEL_FORMAT));
            p10.add(round2(day.worstCase().doubleValue()));
            p50.add(round2(day.mostLikely().doubleValue()));
            p90.add(round2(day.bestCase().doubleValue()));
        }
        base.put("labels", labels);
        base.put("p10", p10);
        base.put("p50", p50);
        base.put("p90", p90);
        // Forecast probability is 0-100; exposed as a fraction.
        base.put("shortfallProb", forecast.probabilityBelowZeroBeforeTarget() / 100.0);

        Map<String, Object> baseline = new LinkedHashMap<>();
        // RescuePlan.baselineShortfallPercent is 0-100; exposed as a fraction.
        baseline.put("shortfallProb", plan.baselineShortfallPercent() / 100.0);

        Object preview = null;
        if (whatIfStudentId != null || whatIfDelayDays != null) {
            int delay = clamp(whatIfDelayDays == null ? DEFAULT_DELAY_DAYS : whatIfDelayDays, 0, MAX_DELAY_DAYS);
            DelayScenarioResult scenario =
                    cashForecastService.simulateDelay(today, horizon, target, whatIfStudentId, delay);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("labels", scenario.after().labels());
            p.put("p50", roundAll(scenario.after().p50()));
            // shortfallAfter is 0-100 from CashForecastService; exposed as a fraction.
            p.put("shortfallProb", scenario.shortfallAfter() / 100.0);
            preview = p;
        }

        Set<String> active = new HashSet<>();
        if (activeCardIds != null) {
            for (String id : activeCardIds.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    active.add(trimmed);
                }
            }
        }
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Action> actions = plan.cards();
        for (int i = 0; i < actions.size(); i++) {
            Action action = actions.get(i);
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("id", action.id());
            card.put("type", action.type());
            card.put("title", action.title());
            card.put("studentId", action.studentId());
            card.put("invoiceId", action.invoiceId());
            card.put("expectedCashRecovered", action.expectedCashRecovered());
            card.put("cost", action.cost());
            card.put("reason", action.reason());
            card.put("estimate", action.estimate());
            card.put("beforeRisk", plan.baselineShortfallPercent() / 100.0);
            // riskAfterAloneByCard is aligned with cards and is 0-100; exposed as a fraction.
            card.put("afterRisk", i < plan.riskAfterAloneByCard().size()
                    ? plan.riskAfterAloneByCard().get(i) / 100.0
                    : null);
            card.put("active", active.contains(action.id()));
            cards.add(card);
        }

        List<Map<String, Object>> chase = new ArrayList<>();
        for (var suggestion : summary.chaseSuggestions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", suggestion.studentName());
            row.put("amount", suggestion.overdueAmount());
            row.put("daysLate", suggestion.daysOverdue());
            row.put("whatsappUrl", ReminderService.whatsappUrl(suggestion.message()));
            chase.add(row);
        }

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("expectedMonthlyFees", summary.expectedMonthlyFees());
        kpis.put("collectedThisMonth", summary.collectedThisMonth());
        kpis.put("pendingDues", summary.pendingDues());
        kpis.put("overdueDues", summary.overdueDues());
        kpis.put("upcomingBillsTotal", summary.upcomingBillsTotal());
        kpis.put("expectedCoverage", summary.expectedCoverage());
        kpis.put("shortfallRisk", summary.shortfallRisk());
        kpis.put("shortfallAmount", summary.shortfallAmount());

        List<Map<String, Object>> monthly = new ArrayList<>();
        for (DashboardService.MonthPoint point : summary.trend()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", point.month());
            row.put("billed", point.billed());
            row.put("collected", point.collected());
            monthly.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("base", base);
        out.put("baseline", baseline);
        out.put("preview", preview);
        out.put("openingCash", forecast.openingBalance());
        out.put("riskBannerText", plan.healthy() ? plan.healthyMessage() : plan.notice());
        out.put("rescueCards", cards);
        out.put("chase", chase);
        out.put("kpis", kpis);
        out.put("monthly", monthly);
        return out;
    }

    // ---------------------------------------------------------------- demo

    /** Replaces data with the bad-day crisis, then returns the default dashboard. 409 if the demo is busy. */
    @PostMapping("/demo/bad-day")
    public Map<String, Object> loadBadDayDemo() {
        return runDemo(sampleDataLoader::loadBadDayDemo);
    }

    /** Restores the sample data, then returns the default dashboard. 409 if the demo is busy. */
    @PostMapping("/demo/reset")
    public Map<String, Object> resetDemo() {
        return runDemo(sampleDataLoader::resetToSampleData);
    }

    private Map<String, Object> runDemo(Runnable action) {
        if (!demoLock.tryRun(action)) {
            throw new IllegalStateException("Demo is busy");
        }
        return dashboard(DEFAULT_HORIZON, null, null, null);
    }

    // ---------------------------------------------------------------- invoices

    @GetMapping("/invoices")
    public List<Map<String, Object>> listInvoices() {
        LocalDate today = LocalDate.now();
        return invoiceService.listAll().stream().map(inv -> invoiceMap(inv, today)).toList();
    }

    @PostMapping("/invoices")
    public ResponseEntity<Map<String, Object>> createInvoice(@RequestBody InvoiceRequest request) {
        if (request == null || request.studentId() == null) {
            throw new IllegalArgumentException("studentId is required");
        }
        Student student = studentRepository.findById(request.studentId())
                .orElseThrow(() -> new IllegalArgumentException("Student not found: " + request.studentId()));
        Invoice invoice = invoiceService.createInvoice(student, request.amount(), request.issueDate(),
                request.dueDate(), request.billingMonth());
        return ResponseEntity.status(HttpStatus.CREATED).body(invoiceMap(invoice, LocalDate.now()));
    }

    /** Records a partial or full payment; the service sets PARTLY_PAID or PAID. Returns the updated invoice. */
    @PostMapping("/invoices/{id}/pay")
    public Map<String, Object> payInvoice(@PathVariable Long id, @RequestBody PaymentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Payment body is required");
        }
        LocalDate date = request.date() == null ? LocalDate.now() : request.date();
        PaymentMethod method = request.method() == null ? PaymentMethod.UPI : request.method();
        invoiceService.recordPayment(id, request.amount(), date, method);
        return invoiceMap(invoiceService.getInvoice(id), LocalDate.now());
    }

    private static Map<String, Object> invoiceMap(Invoice invoice, LocalDate today) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", invoice.getId());
        map.put("studentId", invoice.getStudent() == null ? null : invoice.getStudent().getId());
        map.put("studentName", invoice.getStudent() == null ? null : invoice.getStudent().getName());
        map.put("amount", invoice.getAmount());
        map.put("paid", invoice.amountPaid());
        map.put("dueDate", invoice.getDueDate());
        map.put("status", invoice.effectiveStatus(today).name());
        map.put("billingMonth", invoice.getBillingMonth());
        return map;
    }

    // ---------------------------------------------------------------- students

    @GetMapping("/students")
    public List<Map<String, Object>> listStudents() {
        return studentService.listAll().stream().map(ApiController::studentMap).toList();
    }

    @PostMapping("/students")
    public ResponseEntity<Map<String, Object>> createStudent(@RequestBody StudentRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Student name is required");
        }
        if (request.monthlyFee() == null || request.monthlyFee().signum() < 0) {
            throw new IllegalArgumentException("Monthly fee must be zero or positive");
        }
        Student student = studentService.create(request.name(), request.monthlyFee(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(studentMap(student));
    }

    private static Map<String, Object> studentMap(Student student) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", student.getId());
        map.put("name", student.getName());
        map.put("fee", student.getMonthlyFee());
        map.put("notes", student.getNotes());
        return map;
    }

    // ---------------------------------------------------------------- bills

    @GetMapping("/bills")
    public List<Map<String, Object>> listBills() {
        return recurringBillService.listAll().stream().map(ApiController::billMap).toList();
    }

    @PostMapping("/bills")
    public ResponseEntity<Map<String, Object>> createBill(@RequestBody BillRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Bill body is required");
        }
        if (request.dayOfMonth() < 1 || request.dayOfMonth() > 31) {
            throw new IllegalArgumentException("Day of month must be between 1 and 31");
        }
        if (request.graceDays() < 0) {
            throw new IllegalArgumentException("Grace days cannot be negative");
        }
        RecurringBill bill = recurringBillService.create(request.name(), request.amount(),
                request.dayOfMonth(), request.graceDays());
        return ResponseEntity.status(HttpStatus.CREATED).body(billMap(bill));
    }

    private static Map<String, Object> billMap(RecurringBill bill) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", bill.getId());
        map.put("name", bill.getName());
        map.put("amount", bill.getAmount());
        map.put("dueDay", bill.getDayOfMonth());
        map.put("grace", bill.getGraceDays());
        return map;
    }

    // ---------------------------------------------------------------- backtest

    /**
     * Backtest output. Checkpoints: month, prob (predicted shortfall, 0-1), observed (actual shortfall, boolean),
     * checkpointDate. Buckets: label, checkpoints, meanPredictedProb (0-1), observedShortfallProb (0-1).
     */
    @GetMapping("/backtest")
    public Map<String, Object> backtest() {
        BacktestResult result = backtestService.runBacktest();

        List<Map<String, Object>> checkpoints = new ArrayList<>();
        for (BacktestService.Checkpoint c : result.checkpoints()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("checkpointDate", c.checkpointDate());
            row.put("month", c.month());
            row.put("prob", c.predictedShortfallPercent() / 100.0);
            row.put("observed", c.actualShortfall());
            checkpoints.add(row);
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (BacktestService.BucketRow b : result.buckets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", b.label());
            row.put("checkpoints", b.checkpoints());
            row.put("meanPredictedProb", b.meanPredictedPercent() / 100.0);
            row.put("observedShortfallProb", b.observedShortfallPercent() / 100.0);
            buckets.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkpoints", checkpoints);
        out.put("buckets", buckets);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** Target date for the rescue plan: the next big bill's due date plus its grace days, or null if no bills. */
    private LocalDate rescueTarget(LocalDate today) {
        RecurringBill bill = nextBigBill(today);
        return bill == null ? null : nextDueDate(bill, today).plusDays(bill.getGraceDays());
    }

    /** The bill whose next due date is soonest (ties go to the larger amount), or null if there are no bills. */
    private RecurringBill nextBigBill(LocalDate today) {
        RecurringBill next = null;
        LocalDate nextDue = null;
        for (RecurringBill bill : recurringBillRepository.findAll()) {
            LocalDate due = nextDueDate(bill, today);
            if (next == null || due.isBefore(nextDue)
                    || (due.isEqual(nextDue) && bill.getAmount().compareTo(next.getAmount()) > 0)) {
                next = bill;
                nextDue = due;
            }
        }
        return next;
    }

    /** Next due date on or after today (grace excluded). */
    private static LocalDate nextDueDate(RecurringBill bill, LocalDate today) {
        LocalDate due = bill.dueDateFor(YearMonth.from(today));
        if (due.isBefore(today)) {
            due = bill.dueDateFor(YearMonth.from(today).plusMonths(1));
        }
        return due;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static List<Double> roundAll(List<Double> values) {
        List<Double> out = new ArrayList<>(values.size());
        for (Double v : values) {
            out.add(round2(v));
        }
        return out;
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
