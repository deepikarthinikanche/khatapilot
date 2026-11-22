package com.khatapilot.forecast;

import com.khatapilot.forecast.CashForecastService.ForecastInputs;
import com.khatapilot.forecast.RescuePlanService.Action;
import com.khatapilot.forecast.RescuePlanService.RescuePlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RescuePlanServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 1, 1);

    private final RescuePlanService service =
            new RescuePlanService(new CashForecastService(null, null, null));

    @Test
    void rankPutsFreeActionsFirstThenPaidByRecoveryPerCost() {
        Action paidLowRatio = action("DISCOUNT:1", "DISCOUNT", "600.00", "30.00");
        Action paidHighRatio = action("DISCOUNT:2", "DISCOUNT", "100.00", "1.00");
        Action free = action("CHASE:1", "CHASE", "300.00", "0.00");

        List<Action> ranked = RescuePlanService.rank(List.of(paidLowRatio, paidHighRatio, free));

        assertThat(ranked).extracting(Action::id)
                .containsExactly("CHASE:1", "DISCOUNT:2", "DISCOUNT:1");
    }

    @Test
    void healthyBufferNeedsNoActions() {
        ForecastInputs in = new ForecastInputs(new BigDecimal("100000.00"), List.of(), List.of(),
                PaymentDelayModel.learn(List.of()));

        RescuePlan plan = service.plan(in, TODAY, TODAY.plusDays(20), 30);

        assertThat(plan.baselineShortfallPercent()).isLessThanOrEqualTo(RescuePlanService.SHORTFALL_THRESHOLD);
        assertThat(plan.healthy()).isTrue();
        assertThat(plan.cards()).isEmpty();
        assertThat(plan.appliedActionIds()).isEmpty();
        assertThat(plan.notice()).isEmpty();
    }

    @Test
    void unfixableShortfallProducesUnfixableNotice() {
        // Negative opening balance, no receivables and no bills: no card can change the outcome.
        ForecastInputs in = new ForecastInputs(new BigDecimal("-50000.00"), List.of(), List.of(),
                PaymentDelayModel.learn(List.of()));

        RescuePlan plan = service.plan(in, TODAY, TODAY.plusDays(20), 30);

        assertThat(plan.healthy()).isFalse();
        assertThat(plan.cards()).isEmpty();
        assertThat(plan.fullyResolved()).isFalse();
        assertThat(plan.finalShortfallPercent()).isGreaterThan(RescuePlanService.SHORTFALL_THRESHOLD);
        assertThat(plan.notice()).startsWith("No combination of these actions can fully eliminate");
    }

    @Test
    void delayStudentKeepsTotalOwedUnchanged() {
        // simulateDelay never touches bills; its totalOwed is the receivables sum, which a delay must preserve.
        List<Receivable> receivables = List.of(
                new Receivable(1L, 7L, new BigDecimal("1200.00"), TODAY.plusDays(2)),
                new Receivable(2L, 8L, new BigDecimal("800.50"), TODAY.plusDays(4)),
                new Receivable(3L, 7L, new BigDecimal("300.25"), TODAY.plusDays(6)));

        List<Receivable> delayed = CashForecastService.delayStudent(receivables, 7L, 5);

        assertThat(delayed).hasSize(receivables.size());
        assertThat(sumBalances(delayed)).isEqualByComparingTo(sumBalances(receivables));
    }

    @Test
    void shiftingBillLowersSimulatedRiskAndKeepsBillAmount() {
        // Opening 1000 covers nothing else; a 1500 bill on day 3 with grace 4 drives the balance negative
        // before the day-5 target. Shifting it by its grace days moves it to day 7, after the target.
        BigDecimal billAmount = new BigDecimal("1500.00");
        ForecastInputs in = new ForecastInputs(new BigDecimal("1000.00"), List.of(),
                List.of(new BillOutflow(TODAY.plusDays(3), billAmount, 4)),
                PaymentDelayModel.learn(List.of()));

        RescuePlan plan = service.plan(in, TODAY, TODAY.plusDays(5), 30);

        assertThat(plan.baselineShortfallPercent()).isGreaterThan(RescuePlanService.SHORTFALL_THRESHOLD);
        assertThat(plan.cards()).isNotEmpty();
        assertThat(plan.cards().get(0).id()).isEqualTo("SHIFT_BILL:0");
        assertThat(plan.riskAfterAloneByCard().get(0)).isLessThan(plan.baselineShortfallPercent());
        assertThat(plan.appliedActionIds()).contains("SHIFT_BILL:0");
        assertThat(plan.finalShortfallPercent()).isLessThan(plan.baselineShortfallPercent());
    }

    private static Action action(String id, String type, String recovered, String cost) {
        return new Action(id, type, id, 0L, 0L,
                new BigDecimal(recovered), new BigDecimal(cost), "test", true);
    }

    private static BigDecimal sumBalances(List<Receivable> receivables) {
        BigDecimal total = BigDecimal.ZERO;
        for (Receivable r : receivables) {
            total = total.add(r.balance());
        }
        return total;
    }
}
