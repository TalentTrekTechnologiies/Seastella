package com.seastella.invoice.internal;

import com.seastella.core.api.time.BusinessTime;
import com.seastella.core.api.event.DomainEventPublisher;
import com.seastella.invoice.api.InvoiceEvents;
import com.seastella.servicerequest.api.ServiceRequestMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Announces an accepted invoice whose balance is still unpaid after its due
 * date - once per due date, so a late payment does not alert every night.
 * Runs nightly and on start-up. The alert informs; nothing is stopped by it.
 */
@Component
class InvoicePaymentMonitor {

    private static final Logger log = LoggerFactory.getLogger(InvoicePaymentMonitor.class);

    private final InvoiceRepository invoices;
    private final InvoicePaymentRepository payments;
    private final ServiceRequestMetrics requests;
    private final DomainEventPublisher events;
    private final TransactionTemplate tx;

    InvoicePaymentMonitor(InvoiceRepository invoices, InvoicePaymentRepository payments,
                          ServiceRequestMetrics requests, DomainEventPublisher events,
                          PlatformTransactionManager transactions) {
        this.invoices = invoices;
        this.payments = payments;
        this.requests = requests;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        scan();
    }

    @Scheduled(cron = "${seastella.invoice.payment-scan-cron:0 30 0 * * *}", zone = "${seastella.time-zone:Asia/Kolkata}")
    public void scan() {
        try {
            Integer announced = tx.execute(status -> announceOverdue());
            if (announced != null && announced > 0) log.info("Payment overdue: {} alert(s) raised", announced);
        } catch (RuntimeException e) {
            log.error("Payment overdue scan failed", e);
        }
    }

    private int announceOverdue() {
        LocalDate today = BusinessTime.today();
        int announced = 0;
        for (Invoice invoice : invoices.findByStatusAndPaymentDueDateBeforeAndOverdueAlertedAtIsNull(
                com.seastella.invoice.api.InvoiceStatus.ACCEPTED, today)) {
            BigDecimal balance = invoice.getAmount().subtract(payments.totalReceived(invoice.getId()));
            if (balance.signum() <= 0) continue;

            var request = requests.summary(invoice.getServiceRequestId()).orElse(null);
            Instant now = Instant.now();
            events.publish(new InvoiceEvents.PaymentOverdue(invoice.getId(), invoice.getInvoiceNumber(),
                    invoice.getServiceRequestId(), request == null ? null : request.requestNumber(),
                    request == null ? null : request.vesselName(), invoice.getAmount(), balance,
                    invoice.getCurrency(), invoice.getPaymentDueDate(), invoice.getOrganizationId(),
                    invoice.getVesselId(), now));
            invoice.overdueAlerted(now);
            invoices.save(invoice);
            announced++;
        }
        return announced;
    }
}
