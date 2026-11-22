package com.khatapilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.Payment;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.Student;
import com.khatapilot.service.ReminderService.Tone;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

class ReminderServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 3, 20);

    private final ReminderService reminderService = new ReminderService(new InrFormatter());

    @Test
    void eachToneProducesDistinctMessage() {
        Invoice invoice = sentInvoice("Asha Rao", "1500.00", LocalDate.of(2025, 3, 10), "2025-03");

        String gentle = reminderService.draft(invoice, Tone.GENTLE, TODAY);
        String firm = reminderService.draft(invoice, Tone.FIRM, TODAY);
        String fin = reminderService.draft(invoice, Tone.FINAL, TODAY);

        assertThat(List.of(gentle, firm, fin)).doesNotHaveDuplicates();
    }

    @Test
    void everyToneContainsNameBalanceDueDateAndDaysLate() {
        Invoice invoice = sentInvoice("Asha Rao", "1500.00", LocalDate.of(2025, 3, 10), "2025-03");

        for (Tone tone : Tone.values()) {
            String msg = reminderService.draft(invoice, tone, TODAY);
            assertThat(msg).as(tone + " name").contains("Asha Rao");
            assertThat(msg).as(tone + " balance").contains("\u20B91,500.00");
            assertThat(msg).as(tone + " due date").contains("10 Mar 2025");
            assertThat(msg).as(tone + " days late").contains("10 days late");
            assertThat(msg).as(tone + " batch").contains("March 2025 batch");
        }
    }

    @Test
    void toneWordingIsTypeSpecific() {
        Invoice invoice = sentInvoice("Asha Rao", "1500.00", LocalDate.of(2025, 3, 10), "2025-03");

        assertThat(reminderService.draft(invoice, Tone.GENTLE, TODAY)).contains("friendly reminder");
        assertThat(reminderService.draft(invoice, Tone.FIRM, TODAY)).contains("firm reminder").contains("by 23 Mar 2025");
        assertThat(reminderService.draft(invoice, Tone.FINAL, TODAY)).contains("LAST NOTICE");
    }

    @Test
    void notYetLateInvoiceSaysZeroDaysAndNotYetLate() {
        Invoice invoice = sentInvoice("Ravi Kumar", "800.00", LocalDate.of(2025, 3, 25), "2025-03");

        String msg = reminderService.draft(invoice, Tone.GENTLE, TODAY);

        assertThat(msg).contains("due on 25 Mar 2025 (not yet late)");
        assertThat(msg).doesNotContain("days late");
    }

    @Test
    void partlyPaidInvoiceUsesRemainingBalance() {
        Invoice invoice = sentInvoice("Meera Nair", "1000.00", LocalDate.of(2025, 3, 1), "2025-03");
        invoice.setStatus(com.khatapilot.model.InvoiceStatus.PARTLY_PAID);
        invoice.addPayment(new Payment(new BigDecimal("400.00"), LocalDate.of(2025, 3, 5), PaymentMethod.UPI));

        String msg = reminderService.draft(invoice, Tone.FINAL, TODAY);

        assertThat(msg).contains("\u20B9600.00").contains("19 days late");
    }

    @Test
    void draftRejectsInvoiceWithNothingOutstanding() {
        Invoice invoice = sentInvoice("Asha Rao", "1500.00", LocalDate.of(2025, 3, 10), "2025-03");
        invoice.addPayment(new Payment(new BigDecimal("1500.00"), LocalDate.of(2025, 3, 12), PaymentMethod.CASH));
        invoice.setStatus(com.khatapilot.model.InvoiceStatus.PAID);

        assertThatThrownBy(() -> reminderService.draft(invoice, Tone.GENTLE, TODAY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftsReturnAllThreeTonesWithWhatsappLinks() {
        Invoice invoice = sentInvoice("Asha Rao", "1500.00", LocalDate.of(2025, 3, 10), "2025-03");

        List<ReminderService.ReminderDraft> drafts = reminderService.drafts(invoice, TODAY);

        assertThat(drafts).extracting(ReminderService.ReminderDraft::tone)
                .containsExactly(Tone.GENTLE, Tone.FIRM, Tone.FINAL);
        for (ReminderService.ReminderDraft d : drafts) {
            assertThat(d.whatsappUrl()).startsWith("https://wa.me/?text=");
            assertThat(d.message()).isEqualTo(reminderService.draft(invoice, d.tone(), TODAY));
        }
    }

    @Test
    void whatsappUrlUsesPercentEncodingForSpacesAndSpecialCharacters() {
        String message = "Pay \u20B9500 & 2+2 = 4 #now\nThanks";

        String url = ReminderService.whatsappUrl(message);

        assertThat(url).startsWith("https://wa.me/?text=");
        String encoded = url.substring("https://wa.me/?text=".length());
        assertThat(encoded).doesNotContain(" ").doesNotContain("+").doesNotContain("\n");
        assertThat(encoded).contains("%20").contains("%E2%82%B9").contains("%0A").contains("%26").contains("%2B").contains("%23");
        assertThat(encoded).startsWith("Pay%20%E2%82%B9500%20%26%202%2B2%20%3D%204%20%23now%0AThanks");
    }

    @Test
    void whatsappUrlRoundTripsThroughUrlDecoding() {
        String message = "Hi A & B, \u20B92,000.00 due #1 + extra\nLine two 100%";

        String url = ReminderService.whatsappUrl(message);
        String encoded = url.substring("https://wa.me/?text=".length());

        assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo(message);
    }

    private static Invoice sentInvoice(String name, String amount, LocalDate due, String billingMonth) {
        Student student = new Student(name, new BigDecimal(amount), null);
        Invoice invoice = new Invoice(student, new BigDecimal(amount), due.minusDays(14), due, billingMonth);
        invoice.setStatus(com.khatapilot.model.InvoiceStatus.SENT);
        return invoice;
    }
}
