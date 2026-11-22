package com.khatapilot.forecast;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * Learns each student's payment delay (days paid after due date; early payment counts as 0).
 * A student with fewer than {@link #MIN_OWN_HISTORY} observations blends their own delays with the
 * global pool of all students' delays: their own delays are used with probability n/3, the global pool otherwise.
 */
public final class PaymentDelayModel {

    /** Number of own observations needed before a student's history is trusted fully. */
    public static final int MIN_OWN_HISTORY = 3;

    private final Map<Long, List<Long>> ownDelays;
    private final List<Long> pool;

    private PaymentDelayModel(Map<Long, List<Long>> ownDelays, List<Long> pool) {
        this.ownDelays = ownDelays;
        this.pool = pool;
    }

    /** Builds the model from paid-invoice history. */
    public static PaymentDelayModel learn(List<PaidInvoiceHistory> history) {
        Objects.requireNonNull(history, "history");
        Map<Long, List<Long>> own = new LinkedHashMap<>();
        List<Long> pool = new ArrayList<>();
        for (PaidInvoiceHistory h : history) {
            long delay = Math.max(0L, ChronoUnit.DAYS.between(h.dueDate(), h.lastPaymentDate()));
            own.computeIfAbsent(h.studentId(), k -> new ArrayList<>()).add(delay);
            pool.add(delay);
        }
        return new PaymentDelayModel(own, pool);
    }

    /** Weight given to the student's own delays: 0 for none, n/3 for 1-2, 1 for 3 or more. */
    public double ownWeight(long studentId) {
        int n = ownDelays.getOrDefault(studentId, List.of()).size();
        if (n == 0) {
            return 0.0;
        }
        return n >= MIN_OWN_HISTORY ? 1.0 : n / (double) MIN_OWN_HISTORY;
    }

    /**
     * Expected delay in days: {@code w * ownMean + (1 - w) * globalMean} where w is {@link #ownWeight(long)}.
     * Returns the global mean when the student has no history, and 0.0 when there is no history at all.
     */
    public double meanDelay(long studentId) {
        if (pool.isEmpty()) {
            return 0.0;
        }
        double globalMean = mean(pool);
        List<Long> own = ownDelays.getOrDefault(studentId, List.of());
        if (own.isEmpty()) {
            return globalMean;
        }
        double w = ownWeight(studentId);
        return w * mean(own) + (1.0 - w) * globalMean;
    }

    /** Draws one delay in days for the student, using the own/global mixture described on the class. */
    public long sampleDelay(long studentId, Random random) {
        List<Long> own = ownDelays.getOrDefault(studentId, List.of());
        double w = ownWeight(studentId);
        if (!own.isEmpty() && w == 1.0) {
            return own.get(random.nextInt(own.size()));
        }
        if (own.isEmpty()) {
            return drawFromPool(random);
        }
        if (random.nextDouble() < w) {
            return own.get(random.nextInt(own.size()));
        }
        return drawFromPool(random);
    }

    private long drawFromPool(Random random) {
        return pool.isEmpty() ? 0L : pool.get(random.nextInt(pool.size()));
    }

    private static double mean(List<Long> values) {
        long sum = 0L;
        for (long v : values) {
            sum += v;
        }
        return sum / (double) values.size();
    }
}

