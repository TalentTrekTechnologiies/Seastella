package com.seastella.reporting.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prints the QM-F11 pre-survey form on the client's own file.
 *
 * <p>The client's form is a fillable PDF, and its 71 fields say exactly where
 * each value goes, at what size and alignment. So this reads those positions
 * from the file, takes the fields away (a filled form should not still be
 * editable), and writes each value in their place - in Carlito, which has
 * Calibri's widths, the font the form was made with. Dropdown cells keep the
 * white background the form gives them over the blue rows.
 */
@Component
class PreSurveyRenderer {

    static final String TEMPLATE_VERSION = "QM-F11-v1";

    private static final String TEMPLATE = "/templates/pre-survey-qm-f11-v1.pdf";
    private static final Pattern SIZE = Pattern.compile("([0-9.]+)\\s+Tf");

    /** The free-text box on page 2, between the header and the notes, in points from the top. */
    private static final float REMARKS_TOP = 128f;
    private static final float REMARKS_LEFT = 72f;
    private static final float REMARKS_WIDTH = 470f;
    private static final int REMARKS_LINES = 4;

    /** Where one field sits on the page, as the client's form defines it. */
    record Slot(String name, int page, PDRectangle rect, float fontSize, int align, boolean choice) {}

    private volatile List<Slot> slots;

    /** Every field on the form, read once from the template. */
    List<Slot> slots() {
        List<Slot> found = slots;
        if (found != null) return found;
        try (PDDocument doc = Loader.loadPDF(template())) {
            found = readSlots(doc);
            slots = found;
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException("The pre-survey template could not be read", e);
        }
    }

    byte[] render(Map<String, String> values, String remarks) {
        try (PDDocument doc = Loader.loadPDF(template())) {
            List<Slot> layout = readSlots(doc);
            removeFields(doc);
            PDFont font = SbmcRenderer.font(doc, "Carlito-Regular.ttf");

            Map<Integer, List<Slot>> byPage = new LinkedHashMap<>();
            for (Slot s : layout) byPage.computeIfAbsent(s.page(), k -> new ArrayList<>()).add(s);

            for (Map.Entry<Integer, List<Slot>> e : byPage.entrySet()) {
                PDPage page = doc.getPage(e.getKey());
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    for (Slot s : e.getValue()) {
                        String value = values.get(s.name());
                        if (s.choice()) {
                            // The form paints its dropdowns white over the blue status rows.
                            cs.setNonStrokingColor(Color.WHITE);
                            cs.addRect(s.rect().getLowerLeftX(), s.rect().getLowerLeftY(),
                                    s.rect().getWidth(), s.rect().getHeight());
                            cs.fill();
                        }
                        if (value != null && !value.isBlank()) write(cs, font, s, clean(value));
                    }
                }
            }

            if (remarks != null && !remarks.isBlank() && doc.getNumberOfPages() > 1) {
                PDPage page2 = doc.getPage(1);
                float h = page2.getMediaBox().getHeight();
                try (PDPageContentStream cs = new PDPageContentStream(doc, page2,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(Color.BLACK);
                    float y = h - REMARKS_TOP;
                    for (String line : wrap(font, 11f, remarks.trim(), REMARKS_WIDTH, REMARKS_LINES)) {
                        cs.beginText();
                        cs.setFont(font, 11f);
                        cs.newLineAtOffset(REMARKS_LEFT, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 14f;
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("The pre-survey form could not be rendered", e);
        }
    }

    private static void write(PDPageContentStream cs, PDFont font, Slot s, String text) throws IOException {
        float pad = 2f;
        float room = s.rect().getWidth() - 2 * pad;
        float size = s.fontSize();
        float width = font.getStringWidth(text) / 1000f * size;
        // A long value is set smaller to stay inside its cell, never cut off.
        if (width > room) {
            size = Math.max(5f, size * room / width);
            width = font.getStringWidth(text) / 1000f * size;
        }
        float capHeight = font.getFontDescriptor() == null || font.getFontDescriptor().getCapHeight() <= 0
                ? 0.64f : font.getFontDescriptor().getCapHeight() / 1000f;
        float x = s.align() == 1 ? s.rect().getLowerLeftX() + (s.rect().getWidth() - width) / 2f
                : s.align() == 2 ? s.rect().getUpperRightX() - pad - width
                : s.rect().getLowerLeftX() + pad;
        float y = s.rect().getLowerLeftY() + (s.rect().getHeight() - capHeight * size) / 2f;

        cs.setNonStrokingColor(Color.BLACK);
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }

    private static List<Slot> readSlots(PDDocument doc) {
        PDAcroForm form = doc.getDocumentCatalog().getAcroForm();
        if (form == null) throw new IllegalStateException("The pre-survey template has no form fields.");
        List<Slot> out = new ArrayList<>();
        for (PDField field : form.getFieldTree()) {
            if (!(field instanceof PDTerminalField terminal)) continue;
            String da = field instanceof PDVariableText vt && vt.getDefaultAppearance() != null
                    ? vt.getDefaultAppearance() : form.getDefaultAppearance();
            float size = 11f;
            Matcher m = SIZE.matcher(da == null ? "" : da);
            if (m.find() && Float.parseFloat(m.group(1)) > 0) size = Float.parseFloat(m.group(1));
            int align = field instanceof PDVariableText vt ? vt.getQ() : 0;
            boolean choice = field instanceof PDComboBox;
            for (PDAnnotationWidget w : terminal.getWidgets()) {
                out.add(new Slot(field.getFullyQualifiedName(), pageOf(doc, w), w.getRectangle(), size, align, choice));
            }
        }
        return List.copyOf(out);
    }

    private static int pageOf(PDDocument doc, PDAnnotationWidget widget) {
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            try {
                for (PDAnnotation a : doc.getPage(i).getAnnotations()) {
                    if (a.getCOSObject() == widget.getCOSObject()) return i;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return 0;
    }

    /** A filled form is a record, not something to keep editing: its fields go, the page stays. */
    private static void removeFields(PDDocument doc) throws IOException {
        for (PDPage page : doc.getPages()) {
            List<PDAnnotation> kept = new ArrayList<>();
            for (PDAnnotation a : page.getAnnotations()) {
                if (!(a instanceof PDAnnotationWidget)) kept.add(a);
            }
            page.setAnnotations(kept);
        }
        doc.getDocumentCatalog().getCOSObject().removeItem(COSName.ACRO_FORM);
    }

    /** One line, and only characters the font can draw. */
    static String clean(String value) {
        return value.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
    }

    private static List<String> wrap(PDFont font, float size, String text, float width, int maxLines)
            throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\\r?\\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : clean(paragraph).split(" ")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(next) / 1000f * size > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
        }
        return lines.size() > maxLines ? lines.subList(0, maxLines) : lines;
    }

    private static byte[] template() throws IOException {
        try (InputStream in = PreSurveyRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) throw new IllegalStateException("The pre-survey template is missing: " + TEMPLATE);
            return in.readAllBytes();
        }
    }
}
