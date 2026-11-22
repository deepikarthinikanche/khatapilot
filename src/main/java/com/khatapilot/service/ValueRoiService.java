package com.khatapilot.service;

import com.khatapilot.model.Invoice;
import com.khatapilot.model.InvoiceStatus;
import com.khatapilot.repository.InvoiceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Monthly value-versus-price estimate for the dashboard ROI strip. Every figure is an ESTIMATE.
 * The subscription price is read from {@code khatapilot.subscription.monthly-price} (default 399).
 */
@Service
public class ValueRoiService {

    /** ESTIMATE: share of current-month overdue dues expected to be recovered after reminders. Not measured. */
    public static final double REMINDER_RECOVERY_RATE = 0.40;
    public static final String PRICE_PROPERTY = "${khatapilot.subscription.monthly-price:399}";

    /** All figures are estimates. {@code valueToPriceMultiple} is estimated recovered cash divided by price. */
    public record RoiStrip(BigDecimal overdueThisMonth, BigDecimal estimatedRecovered, BigDecimal moneyAtRisk,
                           BigDecimal subscriptionPrice, BigDecimal valueToPriceMultiple) {
    }

    private final InvoiceRepository invoiceRepository;
    private final BigDecimal subscriptionPrice;

    public ValueRoiService(InvoiceRepository invoiceRepository,
                           @Value(PRICE_PROPERTY) BigDecimal subscriptionPrice) {
        this.invoiceRepository = invoiceRepository;
        this.subscriptionPrice = subscriptionPrice;
    }

    @Transactional(readOnly = true)
    public RoiStrip estimate(LocalDate today) {
        String month = YearMonth.from(today).toString();
        BigDecimal overdue = BigDecimal.ZERO;
        for (Invoice invoice : invoiceRepository.findByBillingMonth(month)) {
            if (invoice.effectiveStatus(today) == InvoiceStatus.OVERDUE && invoice.balance().signum() > 0) {
                overdue = overdue.add(invoice.balance());
            }
        }
        overdue = overdue.setScale(2, RoundingMode.HALF_UP);

        BigDecimal recovered = overdue.multiply(BigDecimal.valueOf(REMINDER_RECOVERY_RATE))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal atRisk = overdue.subtract(recovered).setScale(2, RoundingMode.HALF_UP);
        BigDecimal multiple = subscriptionPrice.signum() <= 0
                ? BigDecimal.ZERO
                : recovered.divide(subscriptionPrice, 2, RoundingMode.HALF_UP);

        return new RoiStrip(overdue, recovered, atRisk, subscriptionPrice, multiple);
    }
}
