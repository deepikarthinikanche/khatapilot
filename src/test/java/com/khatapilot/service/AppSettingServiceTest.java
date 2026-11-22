package com.khatapilot.service;

import com.khatapilot.model.AppSetting;
import com.khatapilot.repository.AppSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingServiceTest {

    private static final String KEY = AppSettingService.OPENING_CASH_BALANCE_KEY;

    @Mock
    private AppSettingRepository appSettingRepository;

    @InjectMocks
    private AppSettingService appSettingService;

    @Test
    void defaultIsReturnedWhenNoSettingStored() {
        when(appSettingRepository.findById(KEY)).thenReturn(Optional.empty());

        BigDecimal balance = appSettingService.getOpeningCashBalance();

        assertThat(balance).isEqualByComparingTo(new BigDecimal("10000.00"));
    }

    @Test
    void storedValueIsParsed() {
        when(appSettingRepository.findById(KEY)).thenReturn(Optional.of(new AppSetting(KEY, "2500.50")));

        BigDecimal balance = appSettingService.getOpeningCashBalance();

        assertThat(balance).isEqualByComparingTo(new BigDecimal("2500.50"));
    }

    @Test
    void unparseableStoredValueThrows() {
        when(appSettingRepository.findById(KEY)).thenReturn(Optional.of(new AppSetting(KEY, "abc")));

        assertThatThrownBy(() -> appSettingService.getOpeningCashBalance())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void setPersistsScaledValue() {
        appSettingService.setOpeningCashBalance(new BigDecimal("1234.5"));

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(appSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getKey()).isEqualTo(KEY);
        assertThat(captor.getValue().getValue()).isEqualTo("1234.50");
    }

    @Test
    void setRejectsNull() {
        assertThatThrownBy(() -> appSettingService.setOpeningCashBalance(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Opening cash balance is required");

        verify(appSettingRepository, never()).save(any(AppSetting.class));
    }

    @Test
    void setRejectsNegative() {
        assertThatThrownBy(() -> appSettingService.setOpeningCashBalance(new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Opening cash balance cannot be negative");

        verify(appSettingRepository, never()).save(any(AppSetting.class));
    }

    @Test
    void zeroIsAllowed() {
        appSettingService.setOpeningCashBalance(BigDecimal.ZERO);

        ArgumentCaptor<AppSetting> captor = ArgumentCaptor.forClass(AppSetting.class);
        verify(appSettingRepository).save(captor.capture());
        assertThat(captor.getValue().getValue()).isEqualTo("0.00");
    }
}
