package com.khatapilot.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.Payment;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.Student;
import com.khatapilot.service.ChaseRankingService.ChaseSuggestion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

class ChaseRankingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 3, 20);

    private final ChaseRankingService service = new ChaseRankingService(new InrFormatter());

    @Test
    void ranksByExpectedRecoveryTimesLikelihood() {
        // Meera: 3000 overdue, no history -> likelihood 0.5 -> score 1500
        // Ravi: 1000 overdue, always pays on time -> likelihood 1.0 -> score 1000
        // Asha: 1000 overdue, averages 20 days late -> likelihood 1/3 -> score ~333
        Student meera = new Student("Meera Nair", new BigDecimal("1000"), null);
        Student ravi = new Student("Ravi Kumar", new BigDecimal("1000"), null);
        Student asha = new Student("Asha Rao", new BigDecimal("1000"), null);
        List<Invoice> invoices = new ArrayList<>();
        invoices.add(open(meera, "2000", LocalDate.of(2025, 2, 25)));
        invoices.add(open(meera, "1000", LocalDate.of(2025, 3, 5)));
        invoices.add(open(ravi, "1000", LocalDate.of(2025, 3, 1)));
        invoices.add(paidOn(ravi, "1000", LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 10)));
        invoices.add(open(asha, "1000", LocalDate.of(2025, 3, 1)));
        invoices.add(paidOn(asha, "1000", LocalDate.of(2025, 1, 10), LocalDate.of(2025, 1, 30)));

        List<ChaseSuggestion> top = service.topChases(invoices, TODAY, 3);

        assertThat(top).extracting(ChaseSuggestion::studentName)
                .containsExactly("Meera Nair", "Ravi Kumar", "Asha Rao");
        assertThat(top.get(0).score()).isCloseTo(1500.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(top.get(1).score()).isCloseTo(1000.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(top.get(2).score()).isCloseTo(1000.0 / 3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void limitsResultsToTopN() {
        List<Invoice> invoices = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            Student s = new Student("Student " + i, new BigDecimal("1000"), null);
            invoices.add(open(s, String.valueOf(i * 100), LocalDate.of(2025, 3, 1)));
        }

        assertThat(service.topChases(invoices, TODAY, 2)).hasSize(2);
        assertThat(service.topChases(invoices, TODAY, 3)).hasSize(3);
    }

    @Test
    void studentsWithNoOverdueBalanceAreExcluded() {
        Student kiran = new Student("Kiran Das", new BigDecimal("1000"), null);
        List<Invoice> invoices = new ArrayList<>();
        // not yet due
        invoices.add(open(kiran, "1000", LocalDate.of(2025, 3, 31)));
        // fully paid, and late in the past
        invoices.add(paidOn(kiran, "1000", LocalDate.of(2025, 2, 10), LocalDate.of(2025, 2, 20)));

        assertThat(service.topChases(invoices, TODAY, 3)).isEmpty();
    }

    @Test
    void lateHistoryLowersLikelihood() {
        assertThat(ChaseRankingService.likelihoodForAverageDelay(0)).isEqualTo(1.0);
        assertThat(ChaseRankingService.likelihoodForAverageDelay(10)).isEqualTo(0.5);
        assertThat(ChaseRankingService.likelihoodForAverageDelay(20)).isLessThan(
                ChaseRankingService.likelihoodForAverageDelay(5));
    }

    @Test
    void suggestionCarriesOverdueTotalDaysAndChaseMessage() {
        Student meera = new Student("Meera Nair", new BigDecimal("1000"), null);
        List<Invoice> invoices = new ArrayList<>();
        invoices.add(open(meera, "2000", LocalDate.of(2025, 2, 25)));
        invoices.add(open(meera, "1000", LocalDate.of(2025, 3, 5)));

        ChaseSuggestion only = service.topChases(invoices, TODAY, 3).get(0);

        assertThat(only.studentName()).isEqualTo("Meera Nair");
        assertThat(only.overdueAmount()).isEqualByComparingTo("3000");
        assertThat(only.daysOverdue()).isEqualTo(23);
        assertThat(only.likelihood()).isEqualTo(0.5);
        assertThat(only.message()).isEqualTo("Chase Meera Nair today: \u20B93,000.00 overdue 23 days");
    }

    @Test
    void isDeterministicForSameInputs() {
        Student a = new Student("A", new BigDecimal("1000"), null);
        Student b = new Student("B", new BigDecimal("1000"), null);
        List<Invoice> invoices = List.of(open(a, "500", LocalDate.of(2025, 3, 1)),
                open(b, "500", LocalDate.of(2025, 3, 1)));

        assertThat(service.topChases(invoices, TODAY, 3)).extracting(ChaseSuggestion::studentName)
                .containsExactly("A", "B");
        assertThat(service.topChases(invoices, TODAY, 3))
                .isEqualTo(service.topChases(invoices, TODAY, 3));
    }

    private static Invoice open(Student student, String amount, LocalDate due) {
        Invoice inv = new Invoice(student, new BigDecimal(amount), due.minusDays(14), due, "2025-03");
        inv.setStatus(InvoiceStatus.SENT);
        return inv;
    }

    private static Invoice paidOn(Student student, String amount, LocalDate due, LocalDate paid) {
        Invoice inv = new Invoice(student, new BigDecimal(amount), due.minusDays(14), due, "2025-01");
        inv.setStatus(InvoiceStatus.PAID);
        inv.addPayment(new Payment(new BigDecimal(amount), paid, PaymentMethod.UPI));
        return inv;
    }
}
