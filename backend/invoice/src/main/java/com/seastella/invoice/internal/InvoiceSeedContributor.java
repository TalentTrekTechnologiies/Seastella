package com.seastella.invoice.internal;

import com.seastella.core.api.seed.SeedContext;
import com.seastella.core.api.seed.SeedContributor;
import com.seastella.invoice.api.InvoiceStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Seeds invoices matching the requests already placed in invoice states.
 *
 * <p>Consistency matters here more than volume: a request sitting in
 * {@code INVOICE_ACCEPTED} with no accepted invoice row would make the gate's
 * second check (which reads the invoice records, not the status column) refuse
 * an assignment the dashboard says is ready. The pairs below are deliberate.
 */
@Component
public class InvoiceSeedContributor implements SeedContributor {

    private final InvoiceRepository invoices;

    InvoiceSeedContributor(InvoiceRepository invoices) {
        this.invoices = invoices;
    }

    @Override public int order() { return 50; }

    @Override public String name() { return "invoices (raised, queried, rejected, accepted)"; }

    @Override
    public void contribute(SeedContext ctx) {
        Long acme = ctx.id("org.acme");
        Long coordinator = ctx.id("user.coordinator");
        Long sm1 = ctx.id("user.sm.one");
        Long sm2 = ctx.id("user.sm.two");

        int seq = 1;

        seq = invoice(ctx, seq, "sr.invoice.raised", acme, "vessel.kestrel", coordinator,
                new BigDecimal("2240.00"),
                "ECDIS SSD unit replacement: attendance at Singapore, replacement SSD, "
                        + "chart reload and verification.",
                InvoiceStatus.RAISED, null, null, 7);

        seq = invoice(ctx, seq, "sr.invoice.queried", acme, "vessel.coral", coordinator,
                new BigDecimal("4980.00"),
                "Gyrocompass alignment and heading verification, including attendance "
                        + "at Busan and two days on site.",
                InvoiceStatus.QUERIED, sm2,
                "Please break down the labour element and confirm whether the alignment "
                        + "is covered under the existing service agreement.", 11);

        seq = invoice(ctx, seq, "sr.invoice.rejected", acme, "vessel.sable", coordinator,
                new BigDecimal("3120.00"),
                "Echo sounder transducer replacement including diver attendance.",
                InvoiceStatus.REJECTED, sm2,
                "Vessel is in dry dock; the transducer will be renewed under the "
                        + "dry-dock specification at no additional attendance cost.", 14);

        seq = invoice(ctx, seq, "sr.invoice.accepted", acme, "vessel.brahmaputra", coordinator,
                new BigDecimal("6450.00"),
                "S-Band magnetron replacement: magnetron, attendance at Fujairah, "
                        + "alignment and performance test.",
                InvoiceStatus.ACCEPTED, sm1, "Approved. Attend at Fujairah.", 8);

        // Assigned and in-flight work each needs its accepted invoice, or the
        // gate's record-level check would contradict the request's status.
        seq = invoice(ctx, seq, "sr.assigned", acme, "vessel.kestrel", coordinator,
                new BigDecimal("1980.00"),
                "X-Band scanner unit fan replacement including attendance and test.",
                InvoiceStatus.ACCEPTED, sm1, "Approved - safety critical.", 10);

        seq = invoice(ctx, seq, "sr.inprogress", acme, "vessel.coral", coordinator,
                new BigDecimal("1420.00"),
                "ECDIS No.2 display fan replacement including attendance.",
                InvoiceStatus.ACCEPTED, sm2, "Approved.", 13);

        seq = invoice(ctx, seq, "sr.reported", acme, "vessel.brahmaputra", coordinator,
                new BigDecimal("2760.00"),
                "AIS transponder diagnosis and VDL transmission repair.",
                InvoiceStatus.ACCEPTED, sm1, "Approved.", 16);

        seq = invoice(ctx, seq, "sr.completed", acme, "vessel.kestrel", coordinator,
                new BigDecimal("1750.00"),
                "EPIRB battery replacement and hydrostatic release check.",
                InvoiceStatus.ACCEPTED, sm1, "Approved ahead of class survey.", 28);

        invoice(ctx, seq, "sr.completed.two", acme, "vessel.coral", coordinator,
                new BigDecimal("1310.00"),
                "S-Band display fan replacement including attendance.",
                InvoiceStatus.ACCEPTED, sm2, "Approved.", 45);
    }

    private int invoice(SeedContext ctx, int seq, String requestHandle, Long orgId,
                        String vesselHandle, Long raisedBy, BigDecimal amount, String description,
                        InvoiceStatus status, Long decidedBy, String note, int daysAgo) {

        if (!ctx.has(requestHandle)) {
            return seq;
        }
        Long requestId = ctx.id(requestHandle);
        Long vesselId = ctx.id(vesselHandle);

        String number = String.format("INV-ACME-%s-%04d",
                ctx.today().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")), seq);

        if (invoices.existsByInvoiceNumber(number)) {
            return seq + 1;
        }

        Invoice inv = new Invoice(number, requestId, orgId, vesselId, raisedBy,
                amount, "USD", description);
        inv.markSeed();

        if (status != InvoiceStatus.RAISED && decidedBy != null) {
            inv.seedDecision(status, decidedBy, note,
                    Instant.now().minus(Math.max(daysAgo - 1, 0), ChronoUnit.DAYS));
        }

        Invoice saved = invoices.save(inv);
        ctx.put("invoice." + requestHandle.substring("sr.".length()), saved.getId());
        return seq + 1;
    }
}
