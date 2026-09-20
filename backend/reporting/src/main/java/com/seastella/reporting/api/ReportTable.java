package com.seastella.reporting.api;

import java.time.Instant;
import java.util.List;

/**
 * One rendered report: what it is, whose data it covers, and its rows.
 *
 * <p>Deliberately one shape for every report. The screen renders it, the PDF
 * writer prints it, and a new report is a query plus a column list rather than
 * a new screen and a new exporter (SoW s7).
 */
public record ReportTable(String key, String title, String subtitle, String scopeNote,
                          Instant generatedAt, String generatedBy,
                          List<Column> columns, List<List<String>> rows, List<Total> totals) {

    /** {@code numeric} right-aligns and lets a total sit under it. */
    public record Column(String label, boolean numeric) {}

    public record Total(String label, String value) {}

    public static Column text(String label) {
        return new Column(label, false);
    }

    public static Column number(String label) {
        return new Column(label, true);
    }
}
