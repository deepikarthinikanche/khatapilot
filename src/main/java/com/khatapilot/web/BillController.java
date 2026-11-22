package com.khatapilot.web;

import com.khatapilot.service.RecurringBillService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
public class BillController {

    private final RecurringBillService recurringBillService;

    public BillController(RecurringBillService recurringBillService) {
        this.recurringBillService = recurringBillService;
    }

    @GetMapping("/bills")
    public String list(Model model) {
        model.addAttribute("bills", recurringBillService.listAll());
        return "bills/list";
    }

    // Validation (dayOfMonth 1..31, graceDays >= 0, name, amount) is enforced by RecurringBillService.
    @PostMapping("/bills")
    public String create(@RequestParam String name,
                         @RequestParam BigDecimal amount,
                         @RequestParam int dayOfMonth,
                         @RequestParam int graceDays,
                         RedirectAttributes redirectAttributes) {
        try {
            recurringBillService.create(name, amount, dayOfMonth, graceDays);
            redirectAttributes.addFlashAttribute("success", "Recurring bill added");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/bills";
    }

    @PostMapping("/bills/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            recurringBillService.delete(id);
            redirectAttributes.addFlashAttribute("success", "Recurring bill deleted");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/bills";
    }
}
