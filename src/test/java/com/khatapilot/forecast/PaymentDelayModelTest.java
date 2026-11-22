package com.khatapilot.forecast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class PaymentDelayModelTest {

    private static final LocalDate DUE = LocalDate.of(2025, 1, 1);

    private static PaidInvoiceHistory paid(long studentId, long delayDays) {
        return new PaidInvoiceHistory(studentId, DUE, DUE.plusDays(delayDays));
    }

    @Test
    void studentWithFiveDelaysHasFullOwnWeightAndSamplesOwnValues() {
        PaymentDelayModel model = PaymentDelayModel.learn(List.of(
                paid(1, 0), paid(1, 5), paid(1, 10), paid(1, 20), paid(1, 40), paid(2, 3)));
        assertThat(model.ownWeight(1)).isEqualTo(1.0);

        Random random = new Random(1);
        for (int i = 0; i < 500; i++) {
            assertThat(model.sampleDelay(1, random)).isIn(0L, 5L, 10L, 20L, 40L);
        }
    }

    @Test
    void studentWithOneDelayHasOneThirdWeight() {
        PaymentDelayModel model = PaymentDelayModel.learn(List.of(paid(1, 9)));
        assertThat(model.ownWeight(1)).isCloseTo(1.0 / 3, offset(1e-9));
    }

    @Test
    void studentWithTwoDelaysHasTwoThirdsWeight() {
        PaymentDelayModel model = PaymentDelayModel.learn(List.of(paid(1, 4), paid(1, 6)));
        assertThat(model.ownWeight(1)).isCloseTo(2.0 / 3, offset(1e-9));
    }

    @Test
    void studentWithNoPaidInvoicesHasZeroWeightAndSamplesGlobalPool() {
        PaymentDelayModel model = PaymentDelayModel.learn(List.of(paid(1, 7), paid(1, 7), paid(1, 7)));
        assertThat(model.ownWeight(99)).isEqualTo(0.0);

        Random random = new Random(3);
        for (int i = 0; i < 100; i++) {
            assertThat(model.sampleDelay(99, random)).isEqualTo(7L);
        }
    }

    @Test
    void emptyHistoryGivesZeroDelayAndZeroMean() {
        PaymentDelayModel model = PaymentDelayModel.learn(List.of());
        assertThat(model.sampleDelay(1, new Random(1))).isEqualTo(0L);
        assertThat(model.meanDelay(1)).isEqualTo(0.0);
    }

    @Test
    void earlyPaymentCountsAsZeroDelay() {
        PaymentDelayModel model = PaymentDelayModel.learn(List.of(
                paid(1, -5), paid(1, -2), paid(1, -10)));
        Random random = new Random(5);
        for (int i = 0; i < 50; i++) {
            assertThat(model.sampleDelay(1, random)).isEqualTo(0L);
        }
        assertThat(model.meanDelay(1)).isEqualTo(0.0);
    }

    @Test
    void meanDelayBlendsOwnAndGlobalMeanForOneHistoryStudent() {
        // Student 1: own delay 9. Global pool: 9, 0, 3 -> global mean 4.
        PaymentDelayModel model = PaymentDelayModel.learn(List.of(paid(1, 9), paid(2, 0), paid(3, 3)));
        double expected = (1.0 / 3) * 9 + (2.0 / 3) * 4;
        assertThat(model.meanDelay(1)).isCloseTo(expected, offset(1e-9));
    }

    @Test
    void meanDelayForUnknownStudentIsGlobalMean() {
        PaymentDelayModel model = PaymentDelayModel.learn(List.of(paid(2, 0), paid(3, 6)));
        assertThat(model.meanDelay(99)).isCloseTo(3.0, offset(1e-9));
    }

    @Test
    void oneHistoryStudentMixtureSamplesOwnDelayAboutOneThird() {
        // Student 1 owns the only 30-day delay; 40 other students pay on time, so the pool
        // contains 30 exactly once out of 41. Expected fraction of 30s = 1/3 + (2/3)(1/41) ~= 0.35.
        List<PaidInvoiceHistory> history = new ArrayList<>();
        history.add(paid(1, 30));
        for (long s = 2; s <= 41; s++) {
            history.add(paid(s, 0));
        }
        PaymentDelayModel model = PaymentDelayModel.learn(history);

        Random random = new Random(42);
        int samples = 3000;
        int thirties = 0;
        for (int i = 0; i < samples; i++) {
            if (model.sampleDelay(1, random) == 30L) {
                thirties++;
            }
        }
        double fraction = thirties / (double) samples;
        assertThat(fraction).isBetween(0.30, 0.37);
    }
}

