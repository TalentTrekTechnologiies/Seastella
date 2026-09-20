package com.seastella.reporting.internal;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.seastella.reporting.api.ReportTable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Reports as PDF (RPT-08).
 *
 * <p>Landscape A4, because these are wide tables; a header that says what the
 * report is, whose data it covers and when it was produced, and a footer with
 * the page number - a printed page that cannot say what it is or how current it
 * is has no business being in a survey folder.
 *
 * <p>The letterhead is a plain wordmark rather than artwork (OI-10, RPT-10). A
 * report goes into a folder and is read a year later, so it has to say whose
 * platform produced it; a typeset name does that honestly, and an invented logo
 * would not. The name and the site are configuration, so putting a real mark on
 * it later is a settings change rather than a code change.
 */
@Component
class ReportPdfWriter {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private static final Color INK = new Color(15, 33, 45);
    private static final Color MUTED = new Color(95, 115, 128);
    private static final Color RULE = new Color(205, 216, 222);
    private static final Color BAND = new Color(238, 243, 246);
    /** The one accent on the page: the wordmark. */
    private static final Color MARK = new Color(11, 94, 130);

    private final String brand;
    private final String brandSite;

    ReportPdfWriter(@Value("${seastella.notification.email.brand:Seastella}") String brand,
                    @Value("${seastella.notification.email.brand-site:seastella.in}") String brandSite) {
        this.brand = brand;
        this.brandSite = brandSite;
    }

    byte[] write(ReportTable report) {
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 42, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PageNumbers(report, brand));
            document.open();

            document.add(letterhead());
            document.add(heading(report));
            document.add(table(report));
            if (!report.totals().isEmpty()) {
                document.add(totals(report));
            }
            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("The report could not be written as a PDF", e);
        }
    }

    /** The wordmark the report title sits under. */
    private Paragraph letterhead() {
        Paragraph mark = new Paragraph();
        mark.add(new Phrase(brand.toUpperCase(Locale.ENGLISH), font(13, Font.BOLD, MARK)));
        mark.add(new Phrase("   Maritime Ops   ·   " + brandSite + "\n", font(9, Font.NORMAL, MUTED)));
        mark.setSpacingAfter(6f);
        return mark;
    }

    private static Paragraph heading(ReportTable report) {
        Paragraph heading = new Paragraph();
        heading.add(new Phrase(report.title() + "\n", font(16, Font.BOLD, INK)));
        heading.add(new Phrase(report.subtitle() + "\n", font(10, Font.NORMAL, MUTED)));
        heading.add(new Phrase(report.scopeNote() + " · produced by " + report.generatedBy()
                + " · " + STAMP.format(report.generatedAt()) + "\n\n", font(9, Font.NORMAL, MUTED)));
        return heading;
    }

    private static PdfPTable table(ReportTable report) throws DocumentException {
        List<ReportTable.Column> columns = report.columns();
        PdfPTable table = new PdfPTable(columns.size());
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        for (ReportTable.Column column : columns) {
            PdfPCell cell = new PdfPCell(new Phrase(column.label(), font(9, Font.BOLD, INK)));
            cell.setBackgroundColor(BAND);
            cell.setBorderColor(RULE);
            cell.setPadding(5f);
            cell.setHorizontalAlignment(column.numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
            table.addCell(cell);
        }

        if (report.rows().isEmpty()) {
            PdfPCell empty = new PdfPCell(new Phrase("Nothing to report for this scope.", font(9, Font.ITALIC, MUTED)));
            empty.setColspan(columns.size());
            empty.setBorderColor(RULE);
            empty.setPadding(10f);
            empty.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(empty);
            return table;
        }

        for (List<String> row : report.rows()) {
            for (int i = 0; i < columns.size(); i++) {
                String text = i < row.size() && row.get(i) != null ? row.get(i) : "";
                PdfPCell cell = new PdfPCell(new Phrase(text, font(8.5f, Font.NORMAL, INK)));
                cell.setBorderColor(RULE);
                cell.setPadding(4.5f);
                cell.setHorizontalAlignment(columns.get(i).numeric() ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT);
                table.addCell(cell);
            }
        }
        return table;
    }

    private static Paragraph totals(ReportTable report) {
        String line = report.totals().stream()
                .map(t -> t.label() + ": " + t.value())
                .collect(Collectors.joining("   ·   "));
        Paragraph paragraph = new Paragraph("\n" + line, font(9.5f, Font.BOLD, INK));
        paragraph.setAlignment(Element.ALIGN_RIGHT);
        return paragraph;
    }

    private static Font font(float size, int style, Color colour) {
        return FontFactory.getFont(FontFactory.HELVETICA, size, style, colour);
    }

    /**
     * A printed page has to say what it is on every sheet, because sheets are
     * separated the moment they leave the printer.
     */
    private static final class PageNumbers extends PdfPageEventHelper {

        private final ReportTable report;
        private final String brand;

        private PageNumbers(ReportTable report, String brand) {
            this.report = report;
            this.brand = brand;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            Phrase footer = new Phrase(brand + " · " + report.title() + " · page " + writer.getPageNumber(),
                    font(8, Font.NORMAL, MUTED));
            ColumnText.showTextAligned(writer.getDirectContent(), Element.ALIGN_CENTER, footer,
                    (page.getLeft() + page.getRight()) / 2, page.getBottom() + 20, 0);
        }
    }
}
