package com.khatapilot.repository;

import com.khatapilot.model.RecurringBill;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecurringBillRepository extends JpaRepository<RecurringBill, Long> {
}
