package com.khatapilot.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

class RecurringBillTest {

    @Test
    void dueDateForClampsDayThirtyOneToLastDayOfFebruary() {
        RecurringBill bill = new RecurringBill("Rent", new BigDecimal("12000"), 31, 0);

        assertThat(bill.dueDateFor(YearMonth.of(2031, 2))).isEqualTo(LocalDate.of(2031, 2, 28));
    }

    @Test
    void dueDateForUsesDayOfMonthWhenMonthIsLongEnough() {
        RecurringBill bill = new RecurringBill("Rent", new BigDecimal("12000"), 31, 0);

        assertThat(bill.dueDateFor(YearMonth.of(2031, 1))).isEqualTo(LocalDate.of(2031, 1, 31));
    }

    @Test
    void isOverdueIsFalseOnDueDatePlusGraceDays() {
        RecurringBill bill = new RecurringBill("Internet", new BigDecimal("999"), 10, 3);
        // due 2031-01-10, grace 3 -> last non-overdue day is 2031-01-13
        assertThat(bill.isOverdue(YearMonth.of(2031, 1), LocalDate.of(2031, 1, 13))).isFalse();
    }

    @Test
    void isOverdueIsTrueTheDayAfterDueDatePlusGraceDays() {
        RecurringBill bill = new RecurringBill("Internet", new BigDecimal("999"), 10, 3);
        assertThat(bill.isOverdue(YearMonth.of(2031, 1), LocalDate.of(2031, 1, 14))).isTrue();
    }
}
