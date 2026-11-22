package com.khatapilot.forecast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RiskBannerTest {

    @Test
    void hiddenAtExactlyTheThreshold() {
        RiskBanner banner = RiskBanner.forProbability(10.0, "Rent", new BigDecimal("12000"), 5);

        assertThat(banner.visible()).isFalse();
        assertThat(banner.message()).isEmpty();
    }

    @Test
    void visibleJustAboveTheThreshold() {
        RiskBanner banner = RiskBanner.forProbability(10.01, "Rent", new BigDecimal("12000"), 5);

        assertThat(banner.visible()).isTrue();
    }

    @Test
    void hiddenAtZero() {
        RiskBanner banner = RiskBanner.forProbability(0.0, "Rent", new BigDecimal("12000"), 5);

        assertThat(banner.visible()).isFalse();
    }

    @Test
    void messageNamesTheBillAmountAndDay() {
        RiskBanner banner = RiskBanner.forProbability(34.0, "Rent", new BigDecimal("12000"), 5);

        assertThat(banner.visible()).isTrue();
        assertThat(banner.message()).isEqualTo("34% chance you can't cover Rent (\u20B912,000) on the 5th");
    }

    @Test
    void ordinalSuffixes() {
        assertThat(RiskBanner.ordinal(1)).isEqualTo("1st");
        assertThat(RiskBanner.ordinal(2)).isEqualTo("2nd");
        assertThat(RiskBanner.ordinal(3)).isEqualTo("3rd");
        assertThat(RiskBanner.ordinal(11)).isEqualTo("11th");
        assertThat(RiskBanner.ordinal(21)).isEqualTo("21st");
        assertThat(RiskBanner.ordinal(22)).isEqualTo("22nd");
    }
}
