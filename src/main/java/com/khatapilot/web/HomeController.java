package com.khatapilot.web;

import com.khatapilot.forecast.CashForecastResult;
import com.khatapilot.forecast.CashForecastService;
import com.khatapilot.forecast.DailyForecast;
import com.khatapilot.forecast.RescuePlanService;
import com.khatapilot.forecast.RiskBanner;
import com.khatapilot.config.SampleDataLoader;
import com.khatapilot.model.RecurringBill;
import com.khatapilot.repository.RecurringBillRepository;
import com.khatapilot.service.DashboardService;
import com.khatapilot.service.DashboardService.MonthPoint;
import com.khatapilot.service.DashboardService.StatusCount;
import com.khatapilot.service.DashboardService.Summary;
import com.khatapilot.service.ValueRoiService;
import com.khatapilot.service.ValueRoiService.RoiStrip;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
public class HomeController {

    private static final long FORECAST_SEED = 42L;
    private static final int[] FORECAST_HORIZONS = {30, 60, 90};
    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final DashboardService dashboardService;
    private final CashForecastService cashForecastService;
    private final RecurringBillRepository recurringBillRepository;
    private final RescuePlanService rescuePlanService;
    private final ValueRoiService valueRoiService;
    private final SampleDataLoader sampleDataLoader;

    public HomeController(DashboardService dashboardService,
                          CashForecastService cashForecastService,
                          RecurringBillRepository recurringBillRepository,
                          RescuePlanService rescuePlanService,
                          ValueRoiService valueRoiService,
                          SampleDataLoader sampleDataLoader) {
        this.dashboardService = dashboardService;
        this.cashForecastService = cashForecastService;
        this.recurringBillRepository = recurringBillRepository;
        this.rescuePlanService = rescuePlanService;
        this.valueRoiService = valueRoiService;
        this.sampleDataLoader = sampleDataLoader;
    }

    /** Demo: replaces data with the deterministic bad-day crisis (opening cash 9200, Rent 12000 in 4 days). */
    @PostMapping("/demo/bad-day")
    public String loadBadDayDemo() {
        sampleDataLoader.loadBadDayDemo();
        return "redirect:/";
    }

    /** Demo: restores the normal sample data and the default opening cash. */
    @PostMapping("/demo/reset")
    public String resetDemo() {
        sampleDataLoader.resetToSampleData();
        return "redirect:/";
    }

    @GetMapping("/")
    public String home(Model model) {
        LocalDate today = LocalDate.now();
        Summary summary = dashboardService.summary(today);

        List<String> trendLabels = summary.trend().stream().map(MonthPoint::month).toList();
        List<BigDecimal> trendBilled = summary.trend().stream().map(MonthPoint::billed).toList();
        List<BigDecimal> trendCollected = summary.trend().stream().map(MonthPoint::collected).toList();
        List<String> statusLabels = summary.statusBreakdown().stream().map(StatusCount::status).toList();
        List<Long> statusCounts = summary.statusBreakdown().stream().map(StatusCount::count).toList();

        model.addAttribute("summary", summary);
        model.addAttribute("trendLabels", trendLabels);
        model.addAttribute("trendBilled", trendBilled);
        model.addAttribute("trendCollected", trendCollected);
        model.addAttribute("statusLabels", statusLabels);
        model.addAttribute("statusCounts", statusCounts);
        model.addAttribute("urgencyBuckets", summary.urgencyBuckets());
        model.addAttribute("reminderRows", summary.reminderRows());
        model.addAttribute("chaseSuggestions", summary.chaseSuggestions());
        model.addAttribute("whatIfStudents", cashForecastService.studentsWithOpenInvoices());
        RoiStrip roi = valueRoiService.estimate(today);
        model.addAttribute("roi", roi);
        addRiskForecast(model, today);
        return "home";
    }

    /** Adds the 30/60/90-day forecast series and the plain-language risk banner for the next big bill. */
    private void addRiskForecast(Model model, LocalDate today) {
        RecurringBill bill = nextBigBill(today);
        LocalDate dueDate = bill == null ? null : nextDueDate(bill, today);
        LocalDate target = bill == null ? null : dueDate.plusDays(bill.getGraceDays());
        model.addAttribute("rescuePlan", rescuePlanService.plan(today, target, 90));

        Map<String, Object> forecasts = new LinkedHashMap<>();
        double shortfallPercent = 0.0;
        for (int horizon : FORECAST_HORIZONS) {
            // The simulator rejects a target outside its horizon, so pass it only when it falls inside.
            LocalDate horizonTarget = target != null && !target.isAfter(today.plusDays(horizon - 1L)) ? target : null;
            CashForecastResult result = cashForecastService.forecast(today, horizon, horizonTarget, FORECAST_SEED);
            forecasts.put(String.valueOf(horizon), toSeries(result, horizonTarget));
            if (horizon == 90 && horizonTarget != null) {
                shortfallPercent = result.probabilityBelowZeroBeforeTarget();
            }
        }

        RiskBanner banner = bill == null
                ? new RiskBanner(false, "")
                : RiskBanner.forProbability(shortfallPercent, bill.getName(), bill.getAmount(), dueDate.getDayOfMonth());

        model.addAttribute("forecasts", forecasts);
        model.addAttribute("riskBannerVisible", banner.visible());
        model.addAttribute("riskBannerMessage", banner.message());
        model.addAttribute("shortfallPercent", shortfallPercent);
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

    private static Map<String, Object> toSeries(CashForecastResult result, LocalDate target) {
        List<String> labels = new ArrayList<>();
        List<Double> p10 = new ArrayList<>();
        List<Double> p50 = new ArrayList<>();
        List<Double> p90 = new ArrayList<>();
        int billIndex = -1;
        for (DailyForecast day : result.days()) {
            if (target != null && day.date().equals(target)) {
                billIndex = labels.size();
            }
            labels.add(day.date().format(LABEL_FORMAT));
            p10.add(day.worstCase().doubleValue());
            p50.add(day.mostLikely().doubleValue());
            p90.add(day.bestCase().doubleValue());
        }
        Map<String, Object> series = new LinkedHashMap<>();
        series.put("labels", labels);
        series.put("p10", p10);
        series.put("p50", p50);
        series.put("p90", p90);
        series.put("billIndex", billIndex);
        return series;
    }

    /** What-if delay simulator: pushes one student's open invoices back by {@code delayDays} (0-365). */
    @GetMapping("/api/forecast/what-if")
    @ResponseBody
    public Map<String, Object> whatIf(@RequestParam(required = false) Long studentId,
                                      @RequestParam(defaultValue = "15") int delayDays) {
        int clampedDelay = Math.max(0, Math.min(365, delayDays));
        LocalDate today = LocalDate.now();
        RecurringBill bill = nextBigBill(today);
        LocalDate target = bill == null ? null : nextDueDate(bill, today).plusDays(bill.getGraceDays());

        CashForecastService.DelayScenarioResult result =
                cashForecastService.simulateDelay(today, 90, target, studentId, clampedDelay);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("before", toDelaySeriesMap(result.before()));
        body.put("after", toDelaySeriesMap(result.after()));
        body.put("worstCaseBefore", round2(result.worstCaseBefore()));
        body.put("worstCaseAfter", round2(result.worstCaseAfter()));
        body.put("worstCaseChange", round2(result.worstCaseChange()));
        body.put("shortfallBefore", round2(result.shortfallBefore()));
        body.put("shortfallAfter", round2(result.shortfallAfter()));
        body.put("shortfallChange", round2(result.shortfallChange()));
        body.put("totalOwed", result.totalOwed().toPlainString());
        body.put("delayDays", clampedDelay);
        body.put("studentId", studentId);
        return body;
    }

    private static Map<String, Object> toDelaySeriesMap(CashForecastService.DelaySeries series) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("labels", series.labels());
        map.put("p10", roundAll(series.p10()));
        map.put("p50", roundAll(series.p50()));
        map.put("p90", roundAll(series.p90()));
        return map;
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
