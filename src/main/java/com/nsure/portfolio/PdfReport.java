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
            need(46);
            y -= 24;
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

    /**
     * A shaded box of headline facts, three to a row: a small grey label over the value in bold. Facts with an
     * empty value are left out.
     */
    void keyFacts(String[][] facts) {
        List<String[]> shown = new ArrayList<>();
        for (String[] fact : facts) {
            if (fact[1] != null && !fact[1].isBlank()) {
                shown.add(fact);
            }
        }
        if (shown.isEmpty()) {
            return;
        }
        try {
            int rows = (shown.size() + 2) / 3;
            float cell = 36;
            float height = rows * cell + 8;
            need(height + 16);
            y -= 16;
            page.setNonStrokingColor(0.945f, 0.969f, 0.953f);
            page.addRect(MARGIN, y - height, WIDTH, height);
            page.fill();
            float column = WIDTH / 3;
            float top = y;
            for (int i = 0; i < shown.size(); i++) {
                float x = MARGIN + 12 + (i % 3) * column;
                y = top - 8 - (i / 3) * cell - 9;
                text(regular, 8, GREY, x, shown.get(i)[0].toUpperCase(Locale.ENGLISH));
                y -= 14;
                text(bold, 11, null, x, fit(shown.get(i)[1], bold, 11, column - 18));
            }
            y = top - height;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A table: a header row, then rows whose cells wrap within their column. {@code widths} are fractions of the
     * page width. With {@code totalRow}, the last row is drawn in bold above a rule, as a total.
     */
    void table(String[] headers, float[] widths, List<String[]> rows, boolean totalRow) {
        try {
            need(40);
            y -= 6;
            tableHeader(headers, widths);
            for (int r = 0; r < rows.size(); r++) {
                boolean total = totalRow && r == rows.size() - 1;
                PDType1Font font = total ? bold : regular;
                List<List<String>> cells = new ArrayList<>();
                int lines = 1;
                for (int c = 0; c < headers.length; c++) {
                    String value = rows.get(r)[c] == null ? "" : rows.get(r)[c];
                    List<String> wrapped = wrap(safe(value), font, 9.5f, widths[c] * WIDTH - 8);
                    cells.add(wrapped);
                    lines = Math.max(lines, wrapped.size());
                }
                float height = lines * 12 + 6;
                if (y - height < MARGIN + 20) {
                    newPage();
                    tableHeader(headers, widths);
                }
                if (total) {
                    rule(y, 0.6f);
                }
                float top = y;
                float x = MARGIN;
                for (int c = 0; c < headers.length; c++) {
                    y = top - 12;
                    for (String line : cells.get(c)) {
                        text(font, 9.5f, null, x, line);
                        y -= 12;
                    }
                    x += widths[c] * WIDTH;
                }
                y = top - height;
                if (!total && r < rows.size() - 1) {
                    rule(y + 3, 0.3f);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A full-width paragraph in body type. */
    void paragraph(String value) {
        try {
            y -= 2;
            for (String line : wrap(safe(value), regular, 10, WIDTH)) {
                need(13);
                y -= 13;
                text(regular, 10, null, MARGIN, line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A bulleted line in body type; long text wraps under itself. */
    void bullet(String value) {
        try {
            List<String> lines = wrap(safe(value), regular, 10, WIDTH - 14);
            need(lines.size() * 13); // a bullet is kept on one page
            for (int i = 0; i < lines.size(); i++) {
                y -= 13;
                if (i == 0) {
                    text(bold, 10, GREEN, MARGIN + 2, "•");
                }
                text(regular, 10, null, MARGIN + 14, lines.get(i));
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
            // Footers go on last, when the page count is known.
            int total = pdf.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream footerStream = new PDPageContentStream(pdf, pdf.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    page = footerStream;
                    rule(MARGIN + 2, 0.5f);
                    y = MARGIN - 10;
                    text(regular, 8, GREY, MARGIN, footer);
                    String count = "Page " + (i + 1) + " of " + total;
                    text(regular, 8, GREY, MARGIN + WIDTH - regular.getStringWidth(count) / 1000 * 8, count);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            pdf.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "5 November 2026" from an ISO date or date-time ("2026-11-05...") or a record date ("05 Nov 2026"). */
    static String longDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        DateTimeFormatter longForm = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
        try {
            return java.time.LocalDate.parse(v.length() > 10 ? v.substring(0, 10) : v).format(longForm);
        } catch (RuntimeException notIso) {
            try {
                return java.time.LocalDate.parse(v, DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH))
                        .format(longForm);
            } catch (RuntimeException e) {
                return v;
            }
        }
    }

    /** Identity and account numbers are shown by their last four characters only. */
    static String last4(String value) {
        String v = value.trim();
        return v.length() <= 4 ? v : v.substring(v.length() - 4);
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
        y = PDRectangle.A4.getHeight() - MARGIN;
    }

    private void tableHeader(String[] headers, float[] widths) throws IOException {
        y -= 12;
        float x = MARGIN;
        for (int c = 0; c < headers.length; c++) {
            text(bold, 8.5f, GREY, x, headers[c].toUpperCase(Locale.ENGLISH));
            x += widths[c] * WIDTH;
        }
        y -= 5;
        rule(y, 0.8f);
    }

    /** A thin horizontal line across the page at the given height. */
    private void rule(float at, float weight) throws IOException {
        page.setLineWidth(weight);
        page.setStrokingColor(0.82f, 0.85f, 0.83f);
        page.moveTo(MARGIN, at);
        page.lineTo(MARGIN + WIDTH, at);
        page.stroke();
    }

    /** The value cut with an ellipsis-like "..." so it fits on one line of the given width. */
    private String fit(String value, PDType1Font font, float size, float width) throws IOException {
        String v = safe(value);
        if (font.getStringWidth(v) / 1000 * size <= width) {
            return v;
        }
        while (v.length() > 1 && font.getStringWidth(v + "...") / 1000 * size > width) {
            v = v.substring(0, v.length() - 1);
        }
        return v.trim() + "...";
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
