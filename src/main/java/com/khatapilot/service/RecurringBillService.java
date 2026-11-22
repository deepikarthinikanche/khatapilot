package com.khatapilot.service;

import com.khatapilot.model.RecurringBill;
import com.khatapilot.repository.RecurringBillRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class RecurringBillService {

    private final RecurringBillRepository recurringBillRepository;

    public RecurringBillService(RecurringBillRepository recurringBillRepository) {
        this.recurringBillRepository = recurringBillRepository;
    }

    @Transactional
    public RecurringBill create(String name, BigDecimal amount, int dayOfMonth, int graceDays) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Bill name is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Bill amount must be greater than zero");
        }
        if (dayOfMonth < 1 || dayOfMonth > 31) {
            throw new IllegalArgumentException("Day of month must be between 1 and 31");
        }
        if (graceDays < 0) {
            throw new IllegalArgumentException("Grace days cannot be negative");
        }
        return recurringBillRepository.save(new RecurringBill(name.trim(), amount, dayOfMonth, graceDays));
    }

    @Transactional(readOnly = true)
    public List<RecurringBill> listAll() {
        return recurringBillRepository.findAll();
    }

    @Transactional
    public void delete(Long id) {
        if (!recurringBillRepository.existsById(id)) {
            throw new IllegalArgumentException("Recurring bill not found: " + id);
        }
        recurringBillRepository.deleteById(id);
    }
}
