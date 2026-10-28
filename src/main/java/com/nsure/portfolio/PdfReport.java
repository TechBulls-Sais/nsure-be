package com.nsure.portfolio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A plain A4 report in the Nsure style: a title block, then sections of label / value rows that wrap and flow
 * onto new pages. Used for the generated claim summary and policy schedule.
 */
final class PdfReport {

    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private static final float MARGIN = 50;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float LABEL_WIDTH = 150;
    private static final float[] GREEN = {0.106f, 0.306f, 0.196f};
    private static final float[] GREY = {0.42f, 0.45f, 0.5f};

    private final PDDocument pdf = new PDDocument();
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final Map<Character, Boolean> encodable = new HashMap<>();
    private final String footer;
    private PDPageContentStream page;
    private float y;
    private int pages;

    /** Starts page one with the title block. */
    PdfReport(String documentTitle, String heading, String subtitle) {
        // "Policy schedule POL-10843" becomes "Nsure policy schedule POL-10843"; the reference keeps its case.
        this.footer = "Nsure " + documentTitle.substring(0, 1).toLowerCase(Locale.ENGLISH) + documentTitle.substring(1);
        pdf.getDocumentInformation().setTitle(documentTitle);
        pdf.getDocumentInformation().setAuthor("Nsure");
        try {
            newPage();
            text(bold, 9, GREEN, MARGIN, "NSURE LIFE");
            y -= 22;
            text(bold, 20, null, MARGIN, heading);
            y -= 16;
            text(regular, 9, GREY, MARGIN, subtitle);
            y -= 10;
            page.setStrokingColor(0.85f, 0.87f, 0.85f);
            page.moveTo(MARGIN, y);
            page.lineTo(MARGIN + WIDTH, y);
            page.stroke();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void section(String title) {
        try {
            need(40);
            y -= 18;
            text(bold, 12, GREEN, MARGIN, title);
            y -= 6;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A label and a value; the value wraps under itself. Empty values are left out. */
    void row(String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            List<String> lines = wrap(safe(value), regular, 10, WIDTH - LABEL_WIDTH);
            need(14);
            y -= 14;
            text(regular, 9, GREY, MARGIN, label);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    need(13);
                    y -= 13;
                }
                text(regular, 10, null, MARGIN + LABEL_WIDTH, lines.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A full-width paragraph in small grey type, such as a note or disclaimer. */
    void note(String value) {
        try {
            for (String line : wrap(safe(value), regular, 9, WIDTH)) {
                need(12);
                y -= 12;
                text(regular, 9, GREY, MARGIN, line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    byte[] bytes() {
        try {
            page.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            pdf.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String money(Number amount) {
        return "BWP " + String.format(Locale.ENGLISH, "%,.2f", amount.doubleValue()).replace(".00", "");
    }

    // ── layout ───────────────────────────────────────────────────────────────────────────────────────

    private void text(PDType1Font font, float size, float[] color, float x, String value) throws IOException {
        page.beginText();
        page.setFont(font, size);
        if (color == null) {
            page.setNonStrokingColor(0.1f, 0.12f, 0.11f);
        } else {
            page.setNonStrokingColor(color[0], color[1], color[2]);
        }
        page.newLineAtOffset(x, y);
        page.showText(safe(value));
        page.endText();
    }

    private void need(float height) throws IOException {
        if (y - height < MARGIN + 20) {
            newPage();
        }
    }

    private void newPage() throws IOException {
        if (page != null) {
            page.close();
        }
        PDPage next = new PDPage(PDRectangle.A4);
        pdf.addPage(next);
        page = new PDPageContentStream(pdf, next);
        pages++;
        y = MARGIN - 10;
        text(regular, 8, GREY, MARGIN, footer + " · page " + pages);
        y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private List<String> wrap(String value, PDType1Font font, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : value.split("\\R")) {
            StringBuilder line = new StringBuilder();
            for (String whole : paragraph.split(" ")) {
                String word = whole;
                // A word wider than the column (a long file name or reference) is broken across lines.
                while (word.length() > 1 && font.getStringWidth(word) / 1000 * size > width) {
                    int cut = word.length() - 1;
                    while (cut > 1 && font.getStringWidth(word.substring(0, cut)) / 1000 * size > width) {
                        cut--;
                    }
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                        line.setLength(0);
                    }
                    lines.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * size <= width) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    if (!line.isEmpty()) {
                        lines.add(line.toString());
                    }
                    line.setLength(0);
                    line.append(word);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    /** The standard PDF fonts cover Western European text only; anything else becomes "?" instead of failing. */
    private String safe(String value) {
        String text = value.replace("→", "->").replace('\t', ' ');
        StringBuilder out = new StringBuilder(text.length());
        for (char ch : text.toCharArray()) {
            boolean ok = encodable.computeIfAbsent(ch, c -> {
                try {
                    regular.encode(String.valueOf(c));
                    return true;
                } catch (IOException | IllegalArgumentException e) {
                    return false;
                }
            });
            out.append(ok || ch == '\n' || ch == '\r' ? ch : '?');
        }
        return out.toString();
    }
}
