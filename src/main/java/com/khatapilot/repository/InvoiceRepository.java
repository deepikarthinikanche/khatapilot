package com.khatapilot.repository;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByStudentAndBillingMonth(Student student, String billingMonth);

    List<Invoice> findByBillingMonth(String billingMonth);

    List<Invoice> findByStudentOrderByDueDateDesc(Student student);
}
