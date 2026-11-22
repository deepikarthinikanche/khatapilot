package com.khatapilot.web;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.model.PaymentMethod;
import com.khatapilot.model.Student;
import com.khatapilot.service.InvoiceService;
import com.khatapilot.service.StudentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;

@Controller
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final StudentService studentService;

    public InvoiceController(InvoiceService invoiceService, StudentService studentService) {
        this.invoiceService = invoiceService;
        this.studentService = studentService;
    }

    @GetMapping("/invoices")
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String billingMonth,
                       Model model) {
        LocalDate today = LocalDate.now();
        InvoiceStatus filter = parseStatus(status);
        model.addAttribute("invoices", invoiceService.search(filter, billingMonth, today));
        model.addAttribute("statuses", InvoiceStatus.values());
        model.addAttribute("filterStatus", status);
        model.addAttribute("filterMonth", billingMonth);
        model.addAttribute("students", studentService.listAll());
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("today", today);
        return "invoices/list";
    }

    @PostMapping("/invoices")
    public String create(@RequestParam Long studentId,
                         @RequestParam BigDecimal amount,
                         @RequestParam String billingMonth,
                         @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate issueDate,
                         @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate dueDate,
                         RedirectAttributes redirectAttributes) {
        try {
            Student student = studentService.listAll().stream()
                    .filter(s -> studentId.equals(s.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Student not found: " + studentId));
            invoiceService.createInvoice(student, amount, issueDate, dueDate, billingMonth);
            redirectAttributes.addFlashAttribute("success", "Invoice created for " + student.getName());
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/invoices";
    }

    @PostMapping("/invoices/generate")
    public String generate(@RequestParam String month,
                           @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate issueDate,
                           @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate dueDate,
                           RedirectAttributes redirectAttributes) {
        try {
            int created = invoiceService.generateMonthlyInvoices(YearMonth.parse(month.trim()), issueDate, dueDate).size();
            redirectAttributes.addFlashAttribute("success", "Generated " + created + " invoices");
        } catch (IllegalArgumentException | IllegalStateException | DateTimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/invoices";
    }

    @PostMapping("/invoices/{id}/send")
    public String send(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            invoiceService.markSent(id);
            redirectAttributes.addFlashAttribute("success", "Invoice marked as sent");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/invoices/" + id;
    }

    @PostMapping("/invoices/{id}/payments")
    public String recordPayment(@PathVariable Long id,
                                @RequestParam BigDecimal amount,
                                @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate paymentDate,
                                @RequestParam PaymentMethod paymentMethod,
                                RedirectAttributes redirectAttributes) {
        try {
            invoiceService.recordPayment(id, amount, paymentDate, paymentMethod);
            redirectAttributes.addFlashAttribute("success", "Payment of " + amount.toPlainString() + " recorded");
        } catch (IllegalArgumentException | IllegalStateException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/invoices/" + id;
    }

    @GetMapping("/invoices/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        LocalDate today = LocalDate.now();
        Invoice invoice;
        try {
            invoice = invoiceService.getInvoice(id);
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/invoices";
        }
        model.addAttribute("invoice", invoice);
        model.addAttribute("payments", invoiceService.paymentHistory(id));
        model.addAttribute("today", today);
        model.addAttribute("paymentMethods", PaymentMethod.values());
        model.addAttribute("effectiveStatus", invoice.effectiveStatus(today));
        return "invoices/detail";
    }

    private static InvoiceStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return InvoiceStatus.valueOf(status.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
