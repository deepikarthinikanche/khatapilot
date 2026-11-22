package com.khatapilot.config;

import com.khatapilot.forecast.CashForecastResult;
import com.khatapilot.forecast.CashForecastService;
import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.RecurringBill;
import com.khatapilot.repository.InvoiceRepository;
import com.khatapilot.repository.RecurringBillRepository;
import com.khatapilot.service.AppSettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the bad-day demo dataset. Runs inside a transaction so the demo data is rolled back afterwards.
 * The shortfall figure is a percent (0-100), the same units the app shows.
 */
@SpringBootTest
@Transactional
class BadDayDemoTest {

    /** Same seed and horizon the dashboard uses for its 90-day forecast. */
    private static final long FORECAST_SEED = 42L;
    private static final int FORECAST_HORIZON = 90;

    @Autowired
    private SampleDataLoader sampleDataLoader;

    @Autowired
    private AppSettingService appSettingService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private RecurringBillRepository recurringBillRepository;

    @Autowired
    private CashForecastService cashForecastService;

    @Test
    void badDayDemoSetsOpeningCashAndFiveOverdueStudents() {
        sampleDataLoader.loadBadDayDemo();
        LocalDate today = LocalDate.now();

        assertThat(appSettingService.getOpeningCashBalance()).isEqualByComparingTo("9200");

        List<Invoice> overdue = invoiceRepository.findByBillingMonth(YearMonth.from(today).toString()).stream()
                .filter(inv -> inv.effectiveStatus(today) == InvoiceStatus.OVERDUE)
                .toList();
        assertThat(overdue).hasSize(5);
    }

    @Test
    void badDayDemoRentIsDueInFourDaysAndShortfallRiskIsAroundThirtyToForty() {
        sampleDataLoader.loadBadDayDemo();
        LocalDate today = LocalDate.now();

        List<RecurringBill> bills = recurringBillRepository.findAll();
        assertThat(bills).hasSize(1);
        RecurringBill rent = bills.get(0);
        assertThat(rent.getName()).isEqualTo("Rent");
        assertThat(rent.getAmount()).isEqualByComparingTo("12000");

        // Same next-due rule the dashboard uses: this month's date, rolled forward if already past.
        LocalDate dueDate = rent.dueDateFor(YearMonth.from(today));
        if (dueDate.isBefore(today)) {
            dueDate = rent.dueDateFor(YearMonth.from(today).plusMonths(1));
        }
        assertThat(dueDate).isEqualTo(today.plusDays(4));

        // Same call HomeController makes: 90-day horizon, target = due date plus grace (0 for Rent).
        LocalDate target = dueDate.plusDays(rent.getGraceDays());
        CashForecastResult result = cashForecastService.forecast(today, FORECAST_HORIZON, target, FORECAST_SEED);
        double shortfallPercent = result.probabilityBelowZeroBeforeTarget();
        assertThat(shortfallPercent).isBetween(30.0, 40.0);
    }
}
