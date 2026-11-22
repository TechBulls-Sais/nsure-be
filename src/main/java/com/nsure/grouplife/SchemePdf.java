package com.nsure.grouplife;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A plain A4 document for group scheme documents (NS-GL01-005): the scheme schedule with its benefit summary and the
 * member certificate. A title block, then sections of label/value rows and paragraphs that wrap and flow onto new
 * pages, and a footer naming the accepted terms and wording the document was generated from.
 */
final class SchemePdf {

    private static final float MARGIN = 50;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float LABEL = 170;

    private final PDDocument pdf = new PDDocument();
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final String footer;
    private PDPageContentStream page;
    private float y;

    SchemePdf(String title, String heading, String subtitle, String footer) {
        this.footer = footer;
        pdf.getDocumentInformation().setTitle(title);
        pdf.getDocumentInformation().setAuthor("Nsure");
        try {
            newPage();
            text(bold, 9, true, MARGIN, "NSURE LIFE - GROUP BUSINESS");
            y -= 22;
            text(bold, 18, false, MARGIN, heading);
            y -= 15;
            for (String line : wrap(subtitle, regular, 9, WIDTH)) {
                text(regular, 9, true, MARGIN, line);
                y -= 11;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    SchemePdf section(String title) {
        try {
            need(40);
            y -= 18;
            text(bold, 12, false, MARGIN, title);
            y -= 4;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    SchemePdf row(String label, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        try {
            List<String> lines = wrap(value, regular, 10, WIDTH - LABEL);
            need(14);
            y -= 14;
            text(regular, 9, true, MARGIN, label);
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) {
                    need(13);
                    y -= 13;
                }
                text(regular, 10, false, MARGIN + LABEL, lines.get(i));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    SchemePdf paragraph(String value) {
        try {
            y -= 4;
            for (String line : wrap(value, regular, 10, WIDTH)) {
                need(13);
                y -= 13;
                text(regular, 10, false, MARGIN, line);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }

    byte[] bytes() {
        try {
            page.close();
            int total = pdf.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream f = new PDPageContentStream(pdf, pdf.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    page = f;
                    y = MARGIN - 12;
                    text(regular, 7.5f, true, MARGIN, footer + "  |  Page " + (i + 1) + " of " + total);
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

    private void text(PDType1Font font, float size, boolean grey, float x, String value) throws IOException {
        page.beginText();
        page.setFont(font, size);
        if (grey) {
            page.setNonStrokingColor(0.42f, 0.45f, 0.5f);
        } else {
            page.setNonStrokingColor(0.1f, 0.12f, 0.11f);
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

    private List<String> wrap(String value, PDType1Font font, float size, float width) {
        List<String> out = new ArrayList<>();
        for (String paragraph : safe(value).split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (textWidth(candidate, font, size) > width && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            out.add(line.toString());
        }
        return out;
    }

    private static float textWidth(String text, PDType1Font font, float size) {
        try {
            return font.getStringWidth(text) / 1000 * size;
        } catch (IOException | IllegalArgumentException e) {
            return text.length() * size * 0.5f;
        }
    }

    /** Standard 14 fonts encode WinAnsi only: anything else becomes '?'. */
    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            out.append(c == '–' || c == '—' ? '-' : c == '×' ? 'x' : c < 256 && c != '\t' && c != '\r' ? c : '?');
        }
        return out.toString();
    }
}
