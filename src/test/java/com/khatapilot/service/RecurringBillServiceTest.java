package com.khatapilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.khatapilot.model.RecurringBill;
import com.khatapilot.repository.RecurringBillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

@ExtendWith(MockitoExtension.class)
class RecurringBillServiceTest {

    @Mock
    private RecurringBillRepository recurringBillRepository;

    @InjectMocks
    private RecurringBillService recurringBillService;

    @Test
    void createPersistsFieldsAndReturnsSavedBill() {
        when(recurringBillRepository.save(any(RecurringBill.class))).thenAnswer(inv -> inv.getArgument(0));

        RecurringBill saved = recurringBillService.create("Rent", new BigDecimal("12000.00"), 5, 3);

        ArgumentCaptor<RecurringBill> captor = ArgumentCaptor.forClass(RecurringBill.class);
        verify(recurringBillRepository).save(captor.capture());
        RecurringBill persisted = captor.getValue();
        assertThat(persisted.getName()).isEqualTo("Rent");
        assertThat(persisted.getAmount()).isEqualByComparingTo("12000.00");
        assertThat(persisted.getDayOfMonth()).isEqualTo(5);
        assertThat(persisted.getGraceDays()).isEqualTo(3);
        assertThat(saved).isSameAs(persisted);
    }

    @Test
    void createRejectsNonPositiveAmountWithoutSaving() {
        assertThatThrownBy(() -> recurringBillService.create("Rent", BigDecimal.ZERO, 5, 0))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recurringBillRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void listAllDelegatesToRepository() {
        List<RecurringBill> bills = List.of(new RecurringBill("Internet", new BigDecimal("999"), 10, 2));
        when(recurringBillRepository.findAll()).thenReturn(bills);

        assertThat(recurringBillService.listAll()).isSameAs(bills);
    }

    @Test
    void deleteRemovesExistingBillById() {
        when(recurringBillRepository.existsById(7L)).thenReturn(true);

        recurringBillService.delete(7L);

        verify(recurringBillRepository).deleteById(7L);
    }

    @Test
    void deleteMissingBillThrowsAndDoesNotDelete() {
        when(recurringBillRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> recurringBillService.delete(99L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(recurringBillRepository, org.mockito.Mockito.never()).deleteById(99L);
    }
}
