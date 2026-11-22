package com.khatapilot.service;

import com.khatapilot.model.AppSetting;
import com.khatapilot.repository.AppSettingRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class AppSettingService {

    public static final String OPENING_CASH_BALANCE_KEY = "opening_cash_balance";
    public static final BigDecimal DEFAULT_OPENING_CASH_BALANCE = new BigDecimal("10000.00");

    private final AppSettingRepository appSettingRepository;

    public AppSettingService(AppSettingRepository appSettingRepository) {
        this.appSettingRepository = appSettingRepository;
    }

    public BigDecimal getOpeningCashBalance() {
        return appSettingRepository.findById(OPENING_CASH_BALANCE_KEY)
                .map(setting -> parseStoredBalance(setting.getValue()))
                .orElse(DEFAULT_OPENING_CASH_BALANCE);
    }

    public void setOpeningCashBalance(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Opening cash balance is required");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Opening cash balance cannot be negative");
        }
        String value = amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        appSettingRepository.save(new AppSetting(OPENING_CASH_BALANCE_KEY, value));
    }

    private BigDecimal parseStoredBalance(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Stored opening cash balance is not a number: " + value, ex);
        }
    }
}
