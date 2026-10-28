package com.seastella.reporting.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Prints a Shore Based Maintenance Certificate on the client's own template.
 *
 * <p>The page is {@code SBMC- SAMPLE.pdf} itself with only its placeholder
 * lines taken out (templates/README.txt), so the border, sunburst, logo,
 * addresses and the SOLAS paragraph are the client's file, not a copy of it.
 * Each value goes back on the placeholder's own baseline, in a font with the
 * same widths as the one the sample used, centred where the sample centred.
 */
@Component
class SbmcRenderer {

    static final String TEMPLATE_VERSION = "SBMC-v1";

    private static final String TEMPLATE = "/templates/sbmc-v1.pdf";
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /** Every printed line in the sample is centred on the page, 396 pt across. */
    private static final float CENTRE = 396f;
    /** Widest a centred line may run inside the orange border before it is set smaller. */
    private static final float MAX_WIDTH = 600f;

    record Fields(String certificateNumber, String vesselName, String callSign, String mmsi, String imo,
                  String ownedBy, LocalDate issueDate, LocalDate expiryDate) {}

    byte[] render(Fields f) {
        try (InputStream template = SbmcRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (template == null) throw new IllegalStateException("The SBMC template is missing: " + TEMPLATE);
            try (PDDocument doc = Loader.loadPDF(template.readAllBytes())) {
                PDPage page = doc.getPage(0);
                float h = page.getMediaBox().getHeight();
                PDFont serif = font(doc, "LiberationSerif-Regular.ttf");
                PDFont serifBold = font(doc, "LiberationSerif-Bold.ttf");
                PDFont cambriaBold = font(doc, "Caladea-Bold.ttf");

                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    // Baselines are the sample's own, measured from the top of the page.
                    centred(cs, 393.8f, h - 85.22f, new Run(serif, 12f, "Certificate No.: " + f.certificateNumber()));
                    centred(cs, CENTRE, h - 243.17f, new Run(serifBold, 21f, vesselLine(f.vesselName())));
                    centred(cs, CENTRE, h - 271.13f,
                            new Run(serifBold, 12f, "("),
                            new Run(cambriaBold, 12f, "Call Sign: " + f.callSign() + "; MMSI: " + f.mmsi()
                                    + "; IMO: " + f.imo()),
                            new Run(serifBold, 12f, ")"));
                    centred(cs, CENTRE, h - 320.71f, new Run(serifBold, 17.04f, f.ownedBy()));
                    dated(cs, true, CENTRE, h - 382.39f, serif, 14.04f, 9f, 5.04f,
                            "This certificate expires: ", f.expiryDate());
                    dated(cs, false, 79.22f, h - 439.18f, serifBold, 12f, 8.04f, 3.96f,
                            "Issue Date: ", f.issueDate());
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                doc.save(out);
                return out.toByteArray();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("The SBMC could not be rendered", e);
        }
    }

    /** "21st", "22nd", "23rd", "11th". */
    static String ordinal(int day) {
        if (day % 100 >= 11 && day % 100 <= 13) return "th";
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private record Run(PDFont font, float size, String text) {
        float width() throws IOException {
            return font.getStringWidth(text) / 1000f * size;
        }
    }

    /** One line made of runs in different fonts, centred as a whole; set smaller if it would not fit. */
    private static void centred(PDPageContentStream cs, float centre, float baseline, Run... runs) throws IOException {
        float width = 0;
        for (Run r : runs) width += r.width();
        float scale = width > MAX_WIDTH ? MAX_WIDTH / width : 1f;
        float x = centre - width * scale / 2f;
        for (Run r : runs) {
            cs.beginText();
            cs.setFont(r.font(), r.size() * scale);
            cs.newLineAtOffset(x, baseline);
            cs.showText(r.text());
            cs.endText();
            x += r.width() * scale;
        }
    }

    /** "Label 22nd Dec 2026" with the ordinal raised and smaller, as the sample sets it. */
    private static void dated(PDPageContentStream cs, boolean centre, float x, float baseline, PDFont font,
                              float size, float supSize, float rise, String label, LocalDate date)
            throws IOException {
        String day = label + date.getDayOfMonth();
        String sup = ordinal(date.getDayOfMonth());
        String rest = " " + MONTH_YEAR.format(date);
        float width = font.getStringWidth(day) / 1000f * size + font.getStringWidth(sup) / 1000f * supSize
                + font.getStringWidth(rest) / 1000f * size;
        float left = centre ? x - width / 2f : x;

        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(left, baseline);
        cs.showText(day);
        cs.endText();
        left += font.getStringWidth(day) / 1000f * size;

        cs.beginText();
        cs.setFont(font, supSize);
        cs.newLineAtOffset(left, baseline + rise);
        cs.showText(sup);
        cs.endText();
        left += font.getStringWidth(sup) / 1000f * supSize;

        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(left, baseline);
        cs.showText(rest);
        cs.endText();
    }

    /**
     * "MV. ARDBEG". The template's own "MV." is not doubled when the vessel is
     * already recorded as "MV Kestrel Trader"; any other prefix (MT, MS) is the
     * vessel's name as recorded, and stays.
     */
    static String vesselLine(String name) {
        String n = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        n = n.replaceFirst("^M\\s*[./]?\\s*V\\.?\\s+", "");
        return "MV. " + n;
    }

    static PDFont font(PDDocument doc, String file) throws IOException {
        try (InputStream in = SbmcRenderer.class.getResourceAsStream("/fonts/" + file)) {
            if (in == null) throw new IllegalStateException("Font missing: " + file);
            return PDType0Font.load(doc, in, true);
        }
    }
}
