package com.khatapilot.forecast;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Seeded Monte Carlo cash forecaster. Receivables are collected on due date plus a sampled delay;
 * bills are paid on their scheduled date. All arithmetic is done in long cents.
 */
public final class MonteCarloCashSimulator {

    private MonteCarloCashSimulator() {
    }

    /**
     * Simulates end-of-day cash balances over {@code horizonDays} starting at {@code startDate}.
     * A {@code null} target date defaults to the last day of the horizon.
     */
    public static CashForecastResult simulate(BigDecimal openingBalance, LocalDate startDate, int horizonDays,
            LocalDate targetDate, List<Receivable> receivables, List<BillOutflow> bills,
            PaymentDelayModel delayModel, int iterations, long seed) {
        Objects.requireNonNull(openingBalance, "openingBalance");
        Objects.requireNonNull(startDate, "startDate");
        Objects.requireNonNull(receivables, "receivables");
        Objects.requireNonNull(bills, "bills");
        Objects.requireNonNull(delayModel, "delayModel");
        if (horizonDays < 1 || horizonDays > 365) {
            throw new IllegalArgumentException("horizonDays must be between 1 and 365: " + horizonDays);
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("iterations must be at least 1: " + iterations);
        }

        LocalDate endDate = startDate.plusDays(horizonDays - 1L);
        LocalDate target = targetDate == null ? endDate : targetDate;
        if (target.isBefore(startDate) || target.isAfter(endDate)) {
            throw new IllegalArgumentException("targetDate " + target + " is outside [" + startDate + ", " + endDate + "]");
        }
        int targetIndex = dayIndex(startDate, target);

        long openingCents = cents(openingBalance);

        long[] billCents = new long[horizonDays];
        for (BillOutflow bill : bills) {
            if (!bill.date().isBefore(startDate) && !bill.date().isAfter(endDate)) {
                billCents[dayIndex(startDate, bill.date())] += cents(bill.amount());
            }
        }

        List<Receivable> sorted = receivables.stream()
                .sorted(Comparator.comparingLong(Receivable::invoiceId))
                .toList();
        long[] receivableCents = new long[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            receivableCents[i] = cents(sorted.get(i).balance());
        }

        Random random = new Random(seed);
        long[][] endBalances = new long[iterations][horizonDays];
        boolean[] negativeBeforeTarget = new boolean[iterations];
        for (int it = 0; it < iterations; it++) {
            long[] inflow = new long[horizonDays];
            for (int i = 0; i < sorted.size(); i++) {
                Receivable r = sorted.get(i);
                long delay = delayModel.sampleDelay(r.studentId(), random);
                LocalDate collect = r.dueDate().plusDays(delay);
                if (collect.isBefore(startDate)) {
                    collect = startDate;
                }
                if (!collect.isAfter(endDate)) {
                    inflow[dayIndex(startDate, collect)] += receivableCents[i];
                }
            }
            long balance = openingCents;
            for (int k = 0; k < horizonDays; k++) {
                balance += inflow[k] - billCents[k];
                endBalances[it][k] = balance;
                if (k <= targetIndex && balance < 0) {
                    negativeBeforeTarget[it] = true;
                }
            }
        }

        List<DailyForecast> days = new ArrayList<>(horizonDays);
        long[] column = new long[iterations];
        for (int k = 0; k < horizonDays; k++) {
            for (int it = 0; it < iterations; it++) {
                column[it] = endBalances[it][k];
            }
            Arrays.sort(column);
            days.add(new DailyForecast(
                    startDate.plusDays(k),
                    toBig(percentile(column, 10)),
                    toBig(percentile(column, 50)),
                    toBig(percentile(column, 90))));
        }

        long negativeCount = 0;
        for (boolean b : negativeBeforeTarget) {
            if (b) {
                negativeCount++;
            }
        }
        double probability = negativeCount * 100.0 / iterations;

        return new CashForecastResult(startDate, horizonDays, target,
                openingBalance.setScale(2, RoundingMode.HALF_UP), seed, iterations, days, probability);
    }

    /** Nearest-rank percentile of an ascending-sorted array. */
    private static long percentile(long[] sorted, int p) {
        int n = sorted.length;
        long rank = (p * (long) n + 99) / 100;
        int index = (int) Math.min(Math.max(rank - 1, 0), n - 1);
        return sorted[index];
    }

    private static int dayIndex(LocalDate startDate, LocalDate date) {
        return (int) ChronoUnit.DAYS.between(startDate, date);
    }

    private static long cents(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private static BigDecimal toBig(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}

