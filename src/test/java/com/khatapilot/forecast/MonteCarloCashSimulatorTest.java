package com.khatapilot.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonteCarloCashSimulatorTest {

    private static final LocalDate START = LocalDate.of(2025, 1, 1);
    private static final int ITERATIONS = 500;
    private static final PaymentDelayModel NO_HISTORY = PaymentDelayModel.learn(List.of());

    private static CashForecastResult run(BigDecimal opening, LocalDate start, int horizon, LocalDate target,
            List<Receivable> receivables, List<BillOutflow> bills, PaymentDelayModel model, long seed) {
        return MonteCarloCashSimulator.simulate(opening, start, horizon, target,
                receivables, bills, model, ITERATIONS, seed);
    }

    private static CashForecastResult run(BigDecimal opening, LocalDate target, int horizon,
            List<Receivable> receivables, List<BillOutflow> bills, PaymentDelayModel model, long seed) {
        return run(opening, START, horizon, target, receivables, bills, model, seed);
    }

    private static PaymentDelayModel varied() {
        LocalDate due = LocalDate.of(2025, 1, 1);
        long[] delays = {0, 5, 10, 20, 40};
        List<PaidInvoiceHistory> history = new java.util.ArrayList<>();
        for (long d : delays) {
            history.add(new PaidInvoiceHistory(1, due, due.plusDays(d)));
        }
        return PaymentDelayModel.learn(history);
    }

    @Test
    void noReceivablesAndNoBillsKeepsEveryDayAtOpening() {
        CashForecastResult r = run(new BigDecimal("10000"), null, 30, List.of(), List.of(), NO_HISTORY, 1L);

        assertThat(r.days()).hasSize(30);
        r.days().forEach(d -> {
            assertThat(d.worstCase()).isEqualByComparingTo("10000");
            assertThat(d.mostLikely()).isEqualByComparingTo("10000");
            assertThat(d.bestCase()).isEqualByComparingTo("10000");
        });
        assertThat(r.probabilityBelowZeroBeforeTarget()).isEqualTo(0.0);
    }

    @Test
    void horizonProducesExactDayCountAndCrossesMonthBoundary() {
        LocalDate start = LocalDate.of(2025, 1, 20);
        for (int horizon : new int[] {30, 60, 90}) {
            CashForecastResult r = MonteCarloCashSimulator.simulate(BigDecimal.ZERO, start, horizon, null,
                    List.of(), List.of(), NO_HISTORY, ITERATIONS, 1L);
            assertThat(r.days()).hasSize(horizon);
            assertThat(r.days().get(0).date()).isEqualTo(start);
            assertThat(r.days().get(horizon - 1).date()).isEqualTo(start.plusDays(horizon - 1L));
            assertThat(r.targetDate()).isEqualTo(start.plusDays(horizon - 1L));
        }
    }

    @Test
    void billsReduceBalanceDayByDay() {
        List<BillOutflow> bills = List.of(
                new BillOutflow(START, new BigDecimal("500")),
                new BillOutflow(START.plusDays(2), new BigDecimal("300")));
        LocalDate lastDay = START.plusDays(2);

        CashForecastResult r = run(BigDecimal.ZERO, lastDay, 30, List.of(), bills, NO_HISTORY, 1L);

        assertThat(r.days().get(0).mostLikely()).isEqualByComparingTo("-500");
        assertThat(r.days().get(1).mostLikely()).isEqualByComparingTo("-500");
        assertThat(r.days().get(2).mostLikely()).isEqualByComparingTo("-800");
        assertThat(r.probabilityBelowZeroBeforeTarget()).isEqualTo(100.0);

        CashForecastResult funded = run(new BigDecimal("1000"), lastDay, 30, List.of(), bills, NO_HISTORY, 1L);
        assertThat(funded.probabilityBelowZeroBeforeTarget()).isEqualTo(0.0);
    }

    @Test
    void overlappingBillsOnSameDateAreSummed() {
        List<BillOutflow> bills = List.of(
                new BillOutflow(START.plusDays(1), new BigDecimal("200")),
                new BillOutflow(START.plusDays(1), new BigDecimal("300")));

        CashForecastResult r = run(BigDecimal.ZERO, null, 10, List.of(), bills, NO_HISTORY, 1L);

        assertThat(r.days().get(0).mostLikely()).isEqualByComparingTo("0");
        assertThat(r.days().get(1).mostLikely()).isEqualByComparingTo("-500");
        assertThat(r.days().get(1).worstCase()).isEqualByComparingTo("-500");
        assertThat(r.days().get(1).bestCase()).isEqualByComparingTo("-500");
    }

    @Test
    void partialPaymentBalanceCollectedOnDueDateWithZeroDelay() {
        // Balance 400 already net of a partial payment; no history means zero delay.
        Receivable receivable = new Receivable(1L, 1L, new BigDecimal("400"), LocalDate.of(2025, 1, 10));

        CashForecastResult r = run(BigDecimal.ZERO, START, 30, List.of(receivable), List.of(), NO_HISTORY, 1L);

        // Jan 9 is index 8, Jan 10 is index 9.
        DailyForecast beforeDue = r.days().get(8);
        assertThat(beforeDue.worstCase()).isEqualByComparingTo("0");
        assertThat(beforeDue.bestCase()).isEqualByComparingTo("0");

        DailyForecast dueDay = r.days().get(9);
        assertThat(dueDay.date()).isEqualTo(LocalDate.of(2025, 1, 10));
        assertThat(dueDay.worstCase()).isEqualByComparingTo("400");
        assertThat(dueDay.mostLikely()).isEqualByComparingTo("400");
        assertThat(dueDay.bestCase()).isEqualByComparingTo("400");
    }

    @Test
    void targetBoundaryControlsWhetherNegativeBalanceCounts() {
        // Bill of 1000 on day 5 is only funded by a 1000 receivable collected on day 6.
        List<BillOutflow> bills = List.of(new BillOutflow(START.plusDays(4), new BigDecimal("1000")));
        List<Receivable> receivables = List.of(
                new Receivable(1L, 1L, new BigDecimal("1000"), START.plusDays(5)));

        CashForecastResult beforeBill = run(BigDecimal.ZERO, START.plusDays(3), 30,
                receivables, bills, NO_HISTORY, 1L);
        assertThat(beforeBill.probabilityBelowZeroBeforeTarget()).isEqualTo(0.0);

        CashForecastResult onBill = run(BigDecimal.ZERO, START.plusDays(4), 30,
                receivables, bills, NO_HISTORY, 1L);
        assertThat(onBill.probabilityBelowZeroBeforeTarget()).isEqualTo(100.0);
    }

    @Test
    void negativeOpeningBalanceIsAlwaysBelowZero() {
        CashForecastResult r = run(new BigDecimal("-1"), START.plusDays(5), 30, List.of(), List.of(),
                NO_HISTORY, 1L);
        assertThat(r.probabilityBelowZeroBeforeTarget()).isEqualTo(100.0);
        assertThat(r.openingBalance()).isEqualByComparingTo("-1.00");
    }

    @Test
    void sameSeedGivesIdenticalResultAndDifferentSeedChangesDays() {
        // Spread of observed delays (0..40 days) makes the collection date genuinely uncertain.
        LocalDate due = LocalDate.of(2024, 6, 1);
        long[] delays = {0, 5, 10, 20, 40};
        List<PaidInvoiceHistory> history = new java.util.ArrayList<>();
        for (long d : delays) {
            history.add(new PaidInvoiceHistory(1L, due, due.plusDays(d)));
        }
        PaymentDelayModel model = PaymentDelayModel.learn(history);

        List<Receivable> receivables = List.of(
                new Receivable(1L, 1L, new BigDecimal("1000.00"), LocalDate.of(2025, 1, 5)),
                new Receivable(2L, 1L, new BigDecimal("1000.00"), LocalDate.of(2025, 1, 10)),
                new Receivable(3L, 1L, new BigDecimal("1000.00"), LocalDate.of(2025, 1, 20)),
                new Receivable(4L, 1L, new BigDecimal("1000.00"), LocalDate.of(2025, 2, 1)));
        List<BillOutflow> bills = List.of();

        CashForecastResult first = MonteCarloCashSimulator.simulate(new BigDecimal("0.00"), START, 60, null,
                receivables, bills, model, 300, 42L);
        CashForecastResult second = MonteCarloCashSimulator.simulate(new BigDecimal("0.00"), START, 60, null,
                receivables, bills, model, 300, 7L);
        CashForecastResult rerun = MonteCarloCashSimulator.simulate(new BigDecimal("0.00"), START, 60, null,
                receivables, bills, model, 300, 42L);

        for (CashForecastResult r : List.of(first, second)) {
            assertThat(r.days()).hasSize(60);
            assertThat(r.days()).allSatisfy(d -> {
                assertThat(d.worstCase()).isLessThanOrEqualTo(d.mostLikely());
                assertThat(d.mostLikely()).isLessThanOrEqualTo(d.bestCase());
            });
        }

        assertThat(rerun.days()).isEqualTo(first.days());
        assertThat(rerun.probabilityBelowZeroBeforeTarget())
                .isEqualTo(first.probabilityBelowZeroBeforeTarget());
        assertThat(first.days()).isNotEqualTo(second.days());
    }

    @Test
    void percentilesAreOrderedForEveryDay() {
        List<Receivable> receivables = List.of(
                new Receivable(1L, 1L, new BigDecimal("700"), START.plusDays(2)),
                new Receivable(2L, 1L, new BigDecimal("300"), START.plusDays(10)));
        List<BillOutflow> bills = List.of(new BillOutflow(START.plusDays(5), new BigDecimal("800")));

        CashForecastResult r = run(new BigDecimal("100"), null, 90, receivables, bills, varied(), 42L);

        assertThat(r.days()).hasSize(90);
        r.days().forEach(d -> {
            assertThat(d.worstCase()).isLessThanOrEqualTo(d.mostLikely());
            assertThat(d.mostLikely()).isLessThanOrEqualTo(d.bestCase());
        });
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThatThrownBy(() -> run(BigDecimal.ZERO, null, 0, List.of(), List.of(), NO_HISTORY, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(BigDecimal.ZERO, null, 366, List.of(), List.of(), NO_HISTORY, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonteCarloCashSimulator.simulate(BigDecimal.ZERO, START, 30, null,
                List.of(), List.of(), NO_HISTORY, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(BigDecimal.ZERO, START.minusDays(1), 30, List.of(), List.of(), NO_HISTORY, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(BigDecimal.ZERO, START.plusDays(30), 30, List.of(), List.of(), NO_HISTORY, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

