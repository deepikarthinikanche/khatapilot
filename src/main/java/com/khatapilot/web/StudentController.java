package com.khatapilot.web;

import com.khatapilot.model.Student;
import com.khatapilot.service.PaymentReliabilityService;
import com.khatapilot.service.PaymentReliabilityService.Reliability;
import com.khatapilot.service.StudentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;

@Controller
public class StudentController {

    public record StudentRow(Student student, String reliabilityLabel, String reliabilityCss) {
    }

    private final StudentService studentService;
    private final PaymentReliabilityService paymentReliabilityService;

    public StudentController(StudentService studentService,
                             PaymentReliabilityService paymentReliabilityService) {
        this.studentService = studentService;
        this.paymentReliabilityService = paymentReliabilityService;
    }

    @GetMapping("/students")
    public String list(Model model) {
        List<StudentRow> rows = studentService.listAll().stream()
                .map(s -> {
                    Reliability reliability = paymentReliabilityService.reliabilityOf(s);
                    return new StudentRow(s, reliability.label(), cssFor(reliability));
                })
                .toList();
        model.addAttribute("students", rows);
        return "students/list";
    }

    @PostMapping("/students")
    public String create(@RequestParam String name,
                         @RequestParam BigDecimal monthlyFee,
                         @RequestParam(required = false) String notes,
                         RedirectAttributes redirectAttributes) {
        try {
            Student student = studentService.create(name, monthlyFee, notes);
            redirectAttributes.addFlashAttribute("success", "Student \"" + student.getName() + "\" added");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/students";
    }

    private static String cssFor(Reliability reliability) {
        return switch (reliability) {
            case ON_TIME -> "bg-success";
            case LATE_5_10 -> "bg-warning text-dark";
            case LATE_15_PLUS -> "bg-danger";
        };
    }
}
