package com.seastella.invoice.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Where the money stands on an invoice, worked out one way for every screen:
 * received and outstanding, whether the agreed advance is in, and whether the
 * balance is past its due date.
 *
 * <p>Only an accepted invoice is owed; for any other status nothing is due, so
 * nothing is ever shown as overdue on an invoice the Ship Manager has not
 * agreed to.
 *
 * @param status UNPAID, PART_PAID or PAID; NOT_DUE before acceptance
 */
public record PaymentPosition(
        int advancePercent,
        BigDecimal advanceAmount,
        LocalDate dueDate,
        BigDecimal received,
        BigDecimal balance,
        boolean advanceReceived,
        String status,
        boolean overdue) {

    public static PaymentPosition of(InvoiceStatus invoiceStatus, BigDecimal amount, int advancePercent,
                                     LocalDate dueDate, BigDecimal received, LocalDate today) {
        BigDecimal got = received == null ? BigDecimal.ZERO : received;
        BigDecimal balance = amount.subtract(got).max(BigDecimal.ZERO);
        BigDecimal advance = amount.multiply(BigDecimal.valueOf(advancePercent))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        boolean owed = invoiceStatus == InvoiceStatus.ACCEPTED;

        String status;
        if (!owed) status = "NOT_DUE";
        else if (balance.signum() == 0) status = "PAID";
        else if (got.signum() > 0) status = "PART_PAID";
        else status = "UNPAID";

        boolean overdue = owed && balance.signum() > 0 && dueDate != null && dueDate.isBefore(today);
        return new PaymentPosition(advancePercent, advance, dueDate, got, balance,
                got.compareTo(advance) >= 0, status, overdue);
    }
}
