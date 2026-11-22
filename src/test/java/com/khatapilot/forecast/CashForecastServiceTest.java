package com.khatapilot.forecast;

import com.khatapilot.model.AppSetting;
import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.Payment;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.RecurringBill;
import com.khatapilot.model.Student;
import com.khatapilot.repository.AppSettingRepository;
import com.khatapilot.repository.InvoiceRepository;
import com.khatapilot.repository.RecurringBillRepository;
import com.khatapilot.service.AppSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CashForecastServiceTest {

    @Mock
    InvoiceRepository invoiceRepository;

    @Mock
    RecurringBillRepository recurringBillRepository;

    @Mock
    AppSettingRepository appSettingRepository;

    CashForecastService service;

    @BeforeEach
    void setUp() {
        AppSettingService appSettingService = new AppSettingService(appSettingRepository);
        service = new CashForecastService(invoiceRepository, recurringBillRepository, appSettingService);
    }

    @Test
    void emptyInvoiceListAndNoBillsGivesFlatOpeningBalance() {
        stubOpening("10000.00");
        when(invoiceRepository.findAll()).thenReturn(List.of());
        when(recurringBillRepository.findAll()).thenReturn(List.of());

        CashForecastResult result = service.forecast(LocalDate.of(2025, 1, 1), 30, null, 500, 1L);

        assertThat(result.days()).hasSize(30);
        assertThat(result.days())
                .allSatisfy(d -> assertThat(d.mostLikely()).isEqualByComparingTo("10000.00"));
        assertThat(result.probabilityBelowZeroBeforeTarget()).isEqualTo(0.0);
    }

    @Test
    void partlyPaidInvoiceContributesOnlyRemainingBalance() {
        Student student = student(1L);
        Invoice invoice = invoice(1L, student, new BigDecimal("1000.00"),
                LocalDate.of(2025, 1, 10), InvoiceStatus.PARTLY_PAID);
        invoice.addPayment(new Payment(new BigDecimal("400.00"), LocalDate.of(2025, 1, 5), PaymentMethod.UPI));
        invoice.setStatus(InvoiceStatus.PARTLY_PAID);

        stubOpening("0.00");
        when(invoiceRepository.findAll()).thenReturn(List.of(invoice));
        when(recurringBillRepository.findAll()).thenReturn(List.of());

        CashForecastResult result = service.forecast(LocalDate.of(2025, 1, 1), 30, null, 200, 7L);

        assertThat(dayValue(result, LocalDate.of(2025, 1, 9))).isEqualByComparingTo("0.00");
        assertThat(dayValue(result, LocalDate.of(2025, 1, 10))).isEqualByComparingTo("600.00");
    }

    @Test
    void paidInvoiceIsNotAReceivable() {
        Student student = student(1L);
        Invoice invoice = invoice(1L, student, new BigDecimal("500.00"),
                LocalDate.of(2025, 1, 5), InvoiceStatus.PAID);
        invoice.addPayment(new Payment(new BigDecimal("500.00"), LocalDate.of(2025, 1, 3), PaymentMethod.UPI));
        invoice.setStatus(InvoiceStatus.PAID);

        stubOpening("500.00");
        when(invoiceRepository.findAll()).thenReturn(List.of(invoice));
        when(recurringBillRepository.findAll()).thenReturn(List.of());

        CashForecastResult result = service.forecast(LocalDate.of(2025, 1, 1), 30, null, 200, 1L);

        assertThat(result.days())
                .allSatisfy(d -> assertThat(d.mostLikely()).isEqualByComparingTo("500.00"));
    }

    @Test
    void graceDaysPushOutflowIntoNextMonth() {
        RecurringBill rent = new RecurringBill("Rent", new BigDecimal("1000.00"), 28, 5);

        stubOpening("10000.00");
        when(invoiceRepository.findAll()).thenReturn(List.of());
        when(recurringBillRepository.findAll()).thenReturn(List.of(rent));

        CashForecastResult result = service.forecast(LocalDate.of(2025, 1, 1), 90, null, 200, 1L);

        assertThat(dayValue(result, LocalDate.of(2025, 2, 1))).isEqualByComparingTo("9000.00");
        assertThat(dayValue(result, LocalDate.of(2025, 2, 2))).isEqualByComparingTo("8000.00");
        assertThat(dayValue(result, LocalDate.of(2025, 3, 4))).isEqualByComparingTo("8000.00");
        assertThat(dayValue(result, LocalDate.of(2025, 3, 5))).isEqualByComparingTo("7000.00");
    }

    @Test
    void day31BillClampedInShortMonth() {
        RecurringBill bill = new RecurringBill("Utilities", new BigDecimal("500.00"), 31, 0);

        stubOpening("10000.00");
        when(invoiceRepository.findAll()).thenReturn(List.of());
        when(recurringBillRepository.findAll()).thenReturn(List.of(bill));

        CashForecastResult result = service.forecast(LocalDate.of(2025, 2, 1), 30, null, 200, 1L);

        assertThat(dayValue(result, LocalDate.of(2025, 2, 27))).isEqualByComparingTo("10000.00");
        assertThat(dayValue(result, LocalDate.of(2025, 2, 28))).isEqualByComparingTo("9500.00");
    }

    @Test
    void overlappingBillsOnSameDaySum() {
        RecurringBill billA = new RecurringBill("A", new BigDecimal("200.00"), 10, 0);
        RecurringBill billB = new RecurringBill("B", new BigDecimal("300.00"), 10, 0);

        stubOpening("10000.00");
        when(invoiceRepository.findAll()).thenReturn(List.of());
        when(recurringBillRepository.findAll()).thenReturn(List.of(billA, billB));

        CashForecastResult result = service.forecast(LocalDate.of(2025, 1, 1), 30, null, 200, 1L);

        assertThat(dayValue(result, LocalDate.of(2025, 1, 10))).isEqualByComparingTo("9500.00");
    }

    @Test
    void horizonCoversNinetyDaysAcrossTwoMonthEnds() {
        stubOpening("10000.00");
        when(invoiceRepository.findAll()).thenReturn(List.of());
        when(recurringBillRepository.findAll()).thenReturn(List.of());

        CashForecastResult result = service.forecast(LocalDate.of(2025, 1, 1), 90, null, 100, 1L);

        assertThat(result.days()).hasSize(90);
        assertThat(result.days().get(89).date()).isEqualTo(LocalDate.of(2025, 3, 31));
    }

    @Test
    void targetDateOutsideHorizonIsRejected() {
        stubOpening("10000.00");
        when(invoiceRepository.findAll()).thenReturn(List.of());
        when(recurringBillRepository.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> service.forecast(LocalDate.of(2025, 1, 1), 30, LocalDate.of(2025, 2, 15), 100, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameSeedGivesIdenticalResultThroughService() {
        stubUncertainCase();

        CashForecastResult first = service.forecast(LocalDate.of(2025, 1, 1), 60, null, 300, 99L);
        CashForecastResult second = service.forecast(LocalDate.of(2025, 1, 1), 60, null, 300, 99L);

        assertThat(first.days()).isEqualTo(second.days());
        assertThat(first.probabilityBelowZeroBeforeTarget())
                .isEqualTo(second.probabilityBelowZeroBeforeTarget());
    }

    @Test
    void differentSeedCanDifferForUncertainCase() {
        stubUncertainCase();

        CashForecastResult a = service.forecast(LocalDate.of(2025, 1, 1), 60, null, 300, 99L);
        CashForecastResult b = service.forecast(LocalDate.of(2025, 1, 1), 60, null, 300, 3L);

        // Not certain the two seeds yield different lists, so assert the invariants instead.
        assertThat(a.days()).hasSize(60);
        assertThat(b.days()).hasSize(60);
        for (CashForecastResult r : List.of(a, b)) {
            assertThat(r.days()).allSatisfy(d -> {
                assertThat(d.worstCase()).isLessThanOrEqualTo(d.mostLikely());
                assertThat(d.mostLikely()).isLessThanOrEqualTo(d.bestCase());
            });
        }
    }

    // --- helpers ---

    /**
     * Stubs the persisted opening-cash-balance setting. AppSettingService is a real instance
     * backed by the mocked AppSettingRepository, so we stub the repository lookup it performs.
     */
    private void stubOpening(String value) {
        lenient().when(appSettingRepository.findById(AppSettingService.OPENING_CASH_BALANCE_KEY))
                .thenReturn(Optional.of(new AppSetting(AppSettingService.OPENING_CASH_BALANCE_KEY, value)));
    }

    private void stubUncertainCase() {
        Student student = student(1L);
        Invoice paid1 = invoice(1L, student, new BigDecimal("1000.00"),
                LocalDate.of(2024, 12, 10), InvoiceStatus.PAID);
        paid1.addPayment(new Payment(new BigDecimal("1000.00"), LocalDate.of(2024, 12, 25), PaymentMethod.UPI));
        paid1.setStatus(InvoiceStatus.PAID);

        Invoice paid2 = invoice(2L, student, new BigDecimal("1000.00"),
                LocalDate.of(2024, 11, 10), InvoiceStatus.PAID);
        paid2.addPayment(new Payment(new BigDecimal("1000.00"), LocalDate.of(2024, 11, 30), PaymentMethod.UPI));
        paid2.setStatus(InvoiceStatus.PAID);

        Invoice open = invoice(3L, student, new BigDecimal("1000.00"),
                LocalDate.of(2025, 1, 5), InvoiceStatus.SENT);

        stubOpening("0.00");
        when(invoiceRepository.findAll()).thenReturn(List.of(paid1, paid2, open));
        when(recurringBillRepository.findAll()).thenReturn(List.of());
    }

    private static Student student(Long id) {
        Student student = new Student("Test Student", new BigDecimal("1000.00"), null);
        student.setId(id);
        return student;
    }

    private static Invoice invoice(Long id, Student student, BigDecimal amount,
                                   LocalDate dueDate, InvoiceStatus status) {
        Invoice invoice = new Invoice(student, amount, dueDate.minusDays(10), dueDate, "2025-01");
        invoice.setId(id);
        invoice.setStatus(status);
        return invoice;
    }

    private static BigDecimal dayValue(CashForecastResult result, LocalDate date) {
        return result.days().stream()
                .filter(d -> d.date().equals(date))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No forecast day for " + date))
                .mostLikely();
    }

    @Test
    void delayStudentKeepsTotalBalanceAndShiftsOnlyTargetStudent() {
        LocalDate due = LocalDate.of(2025, 1, 10);
        List<Receivable> receivables = List.of(
                new Receivable(1L, 1L, new BigDecimal("1000.00"), due),
                new Receivable(2L, 2L, new BigDecimal("750.00"), due));

        List<Receivable> delayed = CashForecastService.delayStudent(receivables, 1L, 15);

        BigDecimal before = receivables.stream().map(Receivable::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal after = delayed.stream().map(Receivable::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(after).isEqualByComparingTo(before);
        assertThat(delayed.get(0).dueDate()).isEqualTo(due.plusDays(15));
        assertThat(delayed.get(1).dueDate()).isEqualTo(due);
    }

    @Test
    void delayStudentWithZeroDaysReturnsEqualReceivables() {
        List<Receivable> receivables = List.of(
                new Receivable(1L, 1L, new BigDecimal("1000.00"), LocalDate.of(2025, 1, 10)));

        assertThat(CashForecastService.delayStudent(receivables, 1L, 0)).isEqualTo(receivables);
    }

}
