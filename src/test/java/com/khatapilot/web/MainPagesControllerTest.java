package com.khatapilot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.Student;
import com.khatapilot.service.InvoiceService;
import com.khatapilot.service.StudentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MainPagesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private StudentService studentService;

    @Test
    void homePageReturnsSummaryModel() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(model().attributeExists("summary"));
    }

    @Test
    void homePageIncludesRiskBannerAndForecastModel() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("riskBannerVisible", "riskBannerMessage", "forecasts"));
    }

    @Test
    void invoicesPageRenders() throws Exception {
        mockMvc.perform(get("/invoices"))
                .andExpect(status().isOk())
                .andExpect(view().name("invoices/list"));
    }

    @Test
    void studentsPageRenders() throws Exception {
        mockMvc.perform(get("/students"))
                .andExpect(status().isOk())
                .andExpect(view().name("students/list"));
    }

    @Test
    void billsPageRenders() throws Exception {
        mockMvc.perform(get("/bills"))
                .andExpect(status().isOk())
                .andExpect(view().name("bills/list"));
    }

    @Test
    void sendMarksDraftInvoiceAsSentAndRedirects() throws Exception {
        Invoice invoice = createDraftInvoice("2031-01", new BigDecimal("1000"));

        mockMvc.perform(post("/invoices/{id}/send", invoice.getId()))
                .andExpect(status().is3xxRedirection())
                .andExpect(view().name("redirect:/invoices/" + invoice.getId()));

        assertThat(invoiceService.getInvoice(invoice.getId()).getStatus()).isEqualTo(InvoiceStatus.SENT);
    }

    @Test
    void partialPaymentMovesSentInvoiceToPartlyPaid() throws Exception {
        Invoice invoice = createDraftInvoice("2031-02", new BigDecimal("1000"));
        invoiceService.markSent(invoice.getId());

        mockMvc.perform(post("/invoices/{id}/payments", invoice.getId())
                        .param("amount", "400")
                        .param("paymentDate", LocalDate.now().toString())
                        .param("paymentMethod", "UPI"))
                .andExpect(status().is3xxRedirection());

        Invoice after = invoiceService.getInvoice(invoice.getId());
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.PARTLY_PAID);
        assertThat(after.balance()).isEqualByComparingTo("600");
    }

    @Test
    void remainingPaymentMovesInvoiceToPaid() throws Exception {
        Invoice invoice = createDraftInvoice("2031-03", new BigDecimal("1000"));
        invoiceService.markSent(invoice.getId());
        invoiceService.recordPayment(invoice.getId(), new BigDecimal("400"), LocalDate.now(),
                PaymentMethod.UPI);

        mockMvc.perform(post("/invoices/{id}/payments", invoice.getId())
                        .param("amount", "600")
                        .param("paymentDate", LocalDate.now().toString())
                        .param("paymentMethod", "CASH"))
                .andExpect(status().is3xxRedirection());

        Invoice after = invoiceService.getInvoice(invoice.getId());
        assertThat(after.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(after.balance()).isEqualByComparingTo("0");
    }

    private Invoice createDraftInvoice(String billingMonth, BigDecimal amount) {
        Student student = studentService.listAll().get(0);
        LocalDate issue = LocalDate.of(2031, 1, 1);
        return invoiceService.createInvoice(student, amount, issue, issue.plusDays(14), billingMonth);
    }
}
