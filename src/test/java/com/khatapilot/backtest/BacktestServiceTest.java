package com.khatapilot.backtest;

import com.khatapilot.backtest.BacktestService.BacktestResult;
import com.khatapilot.backtest.BacktestService.BucketRow;
import com.khatapilot.backtest.BacktestService.Checkpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class BacktestServiceTest {

    @Autowired
    private BacktestService backtestService;

    @Test
    void evaluatesExactlyTwelveCheckpoints() {
        BacktestResult result = backtestService.runBacktest();

        assertThat(result.checkpoints()).hasSize(12);
    }

    @Test
    void bucketCountsSumToTwelve() {
        BacktestResult result = backtestService.runBacktest();

        int total = result.buckets().stream().mapToInt(BucketRow::checkpoints).sum();
        assertThat(total).isEqualTo(12);
    }

    @Test
    void predictedProbabilitiesStayWithinZeroToHundred() {
        BacktestResult result = backtestService.runBacktest();

        for (Checkpoint c : result.checkpoints()) {
            assertThat(c.predictedShortfallPercent()).isBetween(0.0, 100.0);
        }
        for (BucketRow row : result.buckets()) {
            assertThat(row.meanPredictedPercent()).isBetween(0.0, 100.0);
            assertThat(row.observedShortfallPercent()).isBetween(0.0, 100.0);
        }
    }
}
