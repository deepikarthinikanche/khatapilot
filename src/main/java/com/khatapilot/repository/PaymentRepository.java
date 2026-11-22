package com.khatapilot.repository;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoiceOrderByPaymentDateAscIdAsc(Invoice invoice);

    @Modifying
    @Query("delete from Payment")
    void deleteAllInBulk();
}
