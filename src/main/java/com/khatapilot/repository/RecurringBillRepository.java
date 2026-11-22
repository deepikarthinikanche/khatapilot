package com.khatapilot.repository;

import com.khatapilot.model.RecurringBill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RecurringBillRepository extends JpaRepository<RecurringBill, Long> {

    @Modifying
    @Query("delete from RecurringBill")
    void deleteAllInBulk();
}
