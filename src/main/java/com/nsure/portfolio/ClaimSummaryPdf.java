package com.nsure.portfolio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * A claim summary as an A4 PDF: the claim, claimant, checks, payee, decision, documents on file and history,
 * as they stand at the moment it is generated.
 */
final class ClaimSummaryPdf {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private static final float MARGIN = 50;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float LABEL_WIDTH = 150;
    private static final float[] GREEN = {0.106f, 0.306f, 0.196f};
    private static final float[] GREY = {0.42f, 0.45f, 0.5f};

    private final PDDocument pdf = new PDDocument();
    private final PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final Map<Character, Boolean> encodable = new HashMap<>();
    private PDPageContentStream page;
    private float y;
    private int pages;

    private ClaimSummaryPdf() {
    }

    static byte[] render(InsuranceClaim claim, Map<String, String> checks, List<PortfolioViews.DocumentView> documents,
            List<PortfolioViews.HistoryView> history, String generatedBy) {
        try {
            return new ClaimSummaryPdf().build(claim, checks, documents, history, generatedBy);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the claim summary", e);
        }
    }

    private byte[] build(InsuranceClaim c, Map<String, String> checks, List<PortfolioViews.DocumentView> documents,
            List<PortfolioViews.HistoryView> history, String generatedBy) throws IOException {
        PDDocumentInformation info = pdf.getDocumentInformation();
        info.setTitle("Claim summary " + c.getRef());
        info.setAuthor("Nsure");
        newPage();

        text(bold, 9, GREEN, MARGIN, "NSURE LIFE");
        y -= 22;
        text(bold, 20, null, MARGIN, "Claim summary  " + c.getRef());
        y -= 16;
        text(regular, 9, GREY, MARGIN, "Generated " + LocalDateTime.now().format(STAMP) + " by " + generatedBy
                + ". A snapshot of the claim at that moment.");
        y -= 10;
        rule();

        section("Claim");
        row("Status", c.getStatus());
        row("Customer", c.getCustomerName());
        row("Product", c.getProduct());
        row("Policy", c.getPolicyRef());
        row("Claim type", c.getClaimType());
        row("Event date", c.getEventDate());
        row("Lodged", c.getRecordDate());
        row("Claimed amount", money(c.getAmount()));
        row("Processing", c.getProcessingType());
        row("Not straight-through", c.getStpIneligibleReason());

        section("Claimant");
        row("Name", c.getClaimantName());
        row("Relationship", c.getClaimantRelationship());
        row("Mobile", c.getClaimantMobile());
        row("What happened", c.getDescription());

        section("Checks");
        if (checks.isEmpty()) {
            row("Checks", "Not run");
        }
        checks.forEach(this::row);

        section("Payee");
        row("Name", c.getPayeeName());
        row("Relationship", c.getPayeeRelationship());
        row("Mobile", c.getPayeeMobile());
        row("Payout method", c.getPayeePayoutMethod());
        row("Account / wallet", c.getPayeeAccountRef());
        row("Verified", c.isPayoutVerified()
                ? "Yes" + (blank(c.getPayoutVerifiedBy()) ? "" : ", by " + c.getPayoutVerifiedBy()) + at(c.getPayoutVerifiedAt())
                : "No");

        section("Decision");
        row("Assessed by", blank(c.getRecommendedBy()) ? null : c.getRecommendedBy() + at(c.getRecommendedAt()));
        row("Decided by", blank(c.getAdjudicatedBy()) ? null : c.getAdjudicatedBy() + at(c.getResolvedAt()));
        row("Four-eyes", fourEyes(c));
        row("Rejection reason", c.getRejectionReason());
        row("Payout amount", c.getPayoutAmount() == null ? null : money(c.getPayoutAmount()));
        row("Paid", c.getPaidAt() == null ? null : c.getPaidAt().format(STAMP));
        row("Payout reference", c.getPayoutReference());

        section("Documents on file");
        if (documents.isEmpty()) {
            row("Documents", "None");
        }
        for (PortfolioViews.DocumentView d : documents) {
            row(d.type(), d.fileName() + " (" + d.uploadedBy() + ", " + d.uploadedAt().replace('T', ' ') + ")");
        }

        section("History");
        if (history.isEmpty()) {
            row("History", "No entries");
        }
        for (PortfolioViews.HistoryView h : history) {
            String when = h.performedAt().length() >= 16 ? h.performedAt().substring(0, 16).replace('T', ' ') : h.performedAt();
            row(when, h.action() + " by " + h.performedBy() + (blank(h.details()) ? "" : ": " + h.details()));
        }

        page.close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        pdf.save(out);
        pdf.close();
        return out.toByteArray();
    }

    private static String fourEyes(InsuranceClaim c) {
        if (c.getFourEyesApplied() == null) {
            return null;
        }
        if (c.getFourEyesApplied()) {
            return "Applied: assessed and approved by different people";
        }
        return c.getRecommendedBy() != null && c.getRecommendedBy().equals(c.getAdjudicatedBy())
                ? "Overridden by super admin" : "Control was off";
    }

    // ── layout ───────────────────────────────────────────────────────────────────────────────────────

    private void section(String title) throws IOException {
        need(40);
        y -= 18;
        text(bold, 12, GREEN, MARGIN, title);
        y -= 6;
    }

    /** A label and a value; the value wraps under itself. Empty values are left out. */
    private void row(String label, String value) {
        if (blank(value)) {
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

    private void rule() throws IOException {
        page.setStrokingColor(0.85f, 0.87f, 0.85f);
        page.moveTo(MARGIN, y);
        page.lineTo(MARGIN + WIDTH, y);
        page.stroke();
    }

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
        y = PDRectangle.A4.getHeight() - MARGIN;
        float saved = y;
        y = MARGIN - 10;
        text(regular, 8, GREY, MARGIN, "Nsure claim summary · page " + pages);
        y = saved;
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

    private static String at(LocalDateTime when) {
        return when == null ? "" : " on " + when.format(STAMP);
    }

    private static String money(int amount) {
        return "BWP " + String.format(Locale.ENGLISH, "%,d", amount);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
