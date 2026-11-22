package com.khatapilot.repository;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoiceOrderByPaymentDateAscIdAsc(Invoice invoice);
}
