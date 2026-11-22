package com.khatapilot.backtest;

import com.khatapilot.forecast.BillOutflow;
import com.khatapilot.forecast.CashForecastResult;
import com.khatapilot.forecast.CashForecastService;
import com.khatapilot.forecast.CashForecastService.ForecastInputs;
import com.khatapilot.forecast.PaidInvoiceHistory;
import com.khatapilot.forecast.PaymentDelayModel;
import com.khatapilot.forecast.Receivable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Synthetic calibration backtest for {@link CashForecastService}.
 *
 * <p>The service generates one fixed synthetic history (seeded, sample data only, no database access) and
 * then evaluates 12 monthly checkpoints in 2025. At each checkpoint C it builds the forecast from data
 * dated on or before C only:
 * <ul>
 *   <li>opening balance = synthetic opening cash + payments dated on or before C - bills due on or before C;</li>
 *   <li>receivables = invoices issued on or before C and not yet paid by C;</li>
 *   <li>payment-delay history = invoices fully paid on or before C;</li>
 *   <li>bills = recurring bills due in the 30-day window that follows C.</li>
 * </ul>
 * The predicted value is {@code shortfallPercent}: the Monte Carlo share (0-100) of paths whose balance
 * goes below zero within the 30-day horizon (C+1 .. C+30).
 *
 * <p><b>Actual outcome definition (exact):</b> a shortfall occurred at checkpoint C if
 * {@code (cash collected with paid date in [C+1, C+30]) - (recurring bills due in [C+1, C+30]) < 0}.
 * This outcome ignores the opening balance, while the prediction includes it; the comparison is
 * therefore a calibration check against a simple flow-based outcome, not a proof of real-world accuracy.
 *
 * <p><b>Buckets</b> over the predicted percent: [0,10), [10,30), [30,50), [50,70), [70,100].
 * Per bucket: checkpoint count, mean predicted percent, and observed shortfall rate (percent of checkpoints
 * whose actual outcome was a shortfall). Empty buckets report 0.0 for both means.
 */
@Service
public class BacktestService {

    /** Number of monthly checkpoints evaluated. */
    public static final int CHECKPOINT_COUNT = 12;
    /** Forecast and outcome window length in days. */
    public static final int HORIZON_DAYS = 30;

    private static final long SEED = 42L;
    private static final int ITERATIONS = CashForecastService.DEFAULT_ITERATIONS;
    private static final BigDecimal OPENING_CASH = new BigDecimal("5000.00");
    private static final int STUDENT_COUNT = 10;
    private static final LocalDate HISTORY_START = LocalDate.of(2024, 1, 1);
    private static final YearMonth HISTORY_END_MONTH = YearMonth.of(2025, 12);
    private static final YearMonth FIRST_CHECKPOINT_MONTH = YearMonth.of(2025, 1);
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final double[] BUCKET_UPPER = {10, 30, 50, 70, 100};
    private static final String[] BUCKET_LABELS = {"0–10%", "10–30%", "30–50%", "50–70%", "70–100%"};

    private final CashForecastService cashForecastService;

    public BacktestService(CashForecastService cashForecastService) {
        this.cashForecastService = cashForecastService;
    }

    /** One synthetic checkpoint: month label, predicted shortfall percent (0-100), and the actual outcome. */
    public record Checkpoint(LocalDate checkpointDate, String month, double predictedShortfallPercent,
                             boolean actualShortfall) {}

    /** One calibration bucket row. Percent fields are 0-100; empty buckets report 0.0. */
    public record BucketRow(String label, int checkpoints, double meanPredictedPercent,
                            double observedShortfallPercent) {}

    /** Full backtest output: the checkpoints in chronological order and the bucket rows in bucket order. */
    public record BacktestResult(List<Checkpoint> checkpoints, List<BucketRow> buckets) {
        public BacktestResult {
            checkpoints = List.copyOf(checkpoints);
            buckets = List.copyOf(buckets);
        }
    }

    /** Synthetic invoice: issued and due dates, amount, and the date it was paid in full (null if unpaid). */
    private record SyntheticInvoice(long id, long studentId, LocalDate issued, LocalDate due,
                                    BigDecimal amount, LocalDate paidOn) {}

    /** Synthetic recurring bill occurrence. Grace is zero, so the outflow date equals the due date. */
    private record SyntheticBill(LocalDate due, BigDecimal amount) {}

    /** Runs the full synthetic backtest. Same output on every call (fixed seed and history). */
    public BacktestResult runBacktest() {
        Random random = new Random(SEED);
        List<SyntheticInvoice> invoices = generateInvoices(random);
        List<SyntheticBill> bills = generateBills();

        List<Checkpoint> checkpoints = new ArrayList<>(CHECKPOINT_COUNT);
        for (int i = 0; i < CHECKPOINT_COUNT; i++) {
            YearMonth month = FIRST_CHECKPOINT_MONTH.plusMonths(i);
            LocalDate checkpoint = month.atDay(1);
            double predicted = predictAt(checkpoint, invoices, bills);
            boolean actual = actualShortfall(checkpoint, invoices, bills);
            checkpoints.add(new Checkpoint(checkpoint, month.format(MONTH_FORMAT), predicted, actual));
        }
        return new BacktestResult(checkpoints, buildBuckets(checkpoints));
    }

    /**
     * Groups checkpoints into the five fixed buckets. Every checkpoint lands in exactly one bucket, so the
     * bucket counts sum to the number of checkpoints.
     */
    public static List<BucketRow> buildBuckets(List<Checkpoint> checkpoints) {
        int n = BUCKET_UPPER.length;
        int[] counts = new int[n];
        double[] predictedSum = new double[n];
        int[] shortfalls = new int[n];
        for (Checkpoint c : checkpoints) {
            int b = bucketIndex(c.predictedShortfallPercent());
            counts[b]++;
            predictedSum[b] += c.predictedShortfallPercent();
            if (c.actualShortfall()) {
                shortfalls[b]++;
            }
        }
        List<BucketRow> rows = new ArrayList<>(n);
        for (int b = 0; b < n; b++) {
            double mean = counts[b] == 0 ? 0.0 : predictedSum[b] / counts[b];
            double observed = counts[b] == 0 ? 0.0 : shortfalls[b] * 100.0 / counts[b];
            rows.add(new BucketRow(BUCKET_LABELS[b], counts[b], mean, observed));
        }
        return rows;
    }

    private static int bucketIndex(double percent) {
        for (int b = 0; b < BUCKET_UPPER.length - 1; b++) {
            if (percent < BUCKET_UPPER[b]) {
                return b;
            }
        }
        return BUCKET_UPPER.length - 1;
    }

    /** Forecast at checkpoint C using only data dated on or before C (see class Javadoc). */
    private double predictAt(LocalDate checkpoint, List<SyntheticInvoice> invoices, List<SyntheticBill> bills) {
        LocalDate start = checkpoint.plusDays(1);
        LocalDate end = checkpoint.plusDays(HORIZON_DAYS);

        BigDecimal opening = OPENING_CASH;
        List<Receivable> receivables = new ArrayList<>();
        List<PaidInvoiceHistory> history = new ArrayList<>();
        for (SyntheticInvoice inv : invoices) {
            if (inv.paidOn() != null && !inv.paidOn().isAfter(checkpoint)) {
                opening = opening.add(inv.amount());
                history.add(new PaidInvoiceHistory(inv.studentId(), inv.due(), inv.paidOn()));
            } else if (!inv.issued().isAfter(checkpoint)) {
                receivables.add(new Receivable(inv.id(), inv.studentId(), inv.amount(), inv.due()));
            }
        }
        List<BillOutflow> windowBills = new ArrayList<>();
        for (SyntheticBill bill : bills) {
            if (!bill.due().isAfter(checkpoint)) {
                opening = opening.subtract(bill.amount());
            } else if (!bill.due().isAfter(end)) {
                windowBills.add(new BillOutflow(bill.due(), bill.amount(), 0));
            }
        }

        ForecastInputs inputs = new ForecastInputs(opening, receivables, windowBills,
                PaymentDelayModel.learn(history));
        CashForecastResult result = cashForecastService.run(inputs, start, HORIZON_DAYS, null, ITERATIONS, SEED);
        return result.probabilityBelowZeroBeforeTarget();
    }

    /** Actual outcome per the class Javadoc: collections minus bills in [C+1, C+30] below zero. */
    private static boolean actualShortfall(LocalDate checkpoint, List<SyntheticInvoice> invoices,
                                           List<SyntheticBill> bills) {
        LocalDate start = checkpoint.plusDays(1);
        LocalDate end = checkpoint.plusDays(HORIZON_DAYS);
        BigDecimal collected = BigDecimal.ZERO;
        for (SyntheticInvoice inv : invoices) {
            if (inv.paidOn() != null && !inv.paidOn().isBefore(start) && !inv.paidOn().isAfter(end)) {
                collected = collected.add(inv.amount());
            }
        }
        BigDecimal due = BigDecimal.ZERO;
        for (SyntheticBill bill : bills) {
            if (!bill.due().isBefore(start) && !bill.due().isAfter(end)) {
                due = due.add(bill.amount());
            }
        }
        return collected.subtract(due).signum() < 0;
    }

    /** Ten synthetic students: fixed monthly fee issued on the 1st, due on the 15th, paid on due date plus a seeded delay. */
    private static List<SyntheticInvoice> generateInvoices(Random random) {
        List<SyntheticInvoice> invoices = new ArrayList<>();
        long nextId = 1L;
        for (YearMonth ym = YearMonth.from(HISTORY_START); !ym.isAfter(HISTORY_END_MONTH); ym = ym.plusMonths(1)) {
            for (int s = 1; s <= STUDENT_COUNT; s++) {
                LocalDate issued = ym.atDay(1);
                LocalDate due = ym.atDay(15);
                int baseDelay = (s % 4) * 6;
                LocalDate paidOn = due.plusDays(baseDelay + random.nextInt(8));
                BigDecimal amount = BigDecimal.valueOf(2500L + 500L * (s % 3));
                invoices.add(new SyntheticInvoice(nextId++, s, issued, due, amount, paidOn));
            }
        }
        return invoices;
    }

    /** Two fixed recurring bills per month: rent on the 5th and utilities on the 20th. */
    private static List<SyntheticBill> generateBills() {
        List<SyntheticBill> bills = new ArrayList<>();
        YearMonth last = HISTORY_END_MONTH.plusMonths(1);
        for (YearMonth ym = YearMonth.from(HISTORY_START); !ym.isAfter(last); ym = ym.plusMonths(1)) {
            bills.add(new SyntheticBill(ym.atDay(5), new BigDecimal("12000.00")));
            bills.add(new SyntheticBill(ym.atDay(20), new BigDecimal("3500.00")));
        }
        return bills;
    }
}