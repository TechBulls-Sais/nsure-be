package com.nsure.portfolio;

import static com.nsure.portfolio.PdfReport.STAMP;
import static com.nsure.portfolio.PdfReport.money;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A claim summary as an A4 PDF: the claim, claimant, checks, payee, decision, documents on file and history,
 * as they stand at the moment it is generated.
 */
final class ClaimSummaryPdf {

    private ClaimSummaryPdf() {
    }

    static byte[] render(InsuranceClaim c, Map<String, String> checks, List<PortfolioViews.DocumentView> documents,
            List<PortfolioViews.HistoryView> history, String generatedBy) {
        PdfReport pdf = new PdfReport("Claim summary " + c.getRef(), "Claim summary  " + c.getRef(),
                "Generated " + LocalDateTime.now().format(STAMP) + " by " + generatedBy
                        + ". A snapshot of the claim at that moment.");

        pdf.section("Claim");
        pdf.row("Status", c.getStatus());
        pdf.row("Customer", c.getCustomerName());
        pdf.row("Product", c.getProduct());
        pdf.row("Policy", c.getPolicyRef());
        pdf.row("Claim type", c.getClaimType());
        pdf.row("Event date", c.getEventDate());
        pdf.row("Lodged", c.getRecordDate());
        pdf.row("Claimed amount", money(c.getAmount()));
        pdf.row("Processing", c.getProcessingType());
        pdf.row("Not straight-through", c.getStpIneligibleReason());

        pdf.section("Claimant");
        pdf.row("Name", c.getClaimantName());
        pdf.row("Relationship", c.getClaimantRelationship());
        pdf.row("Mobile", c.getClaimantMobile());
        pdf.row("What happened", c.getDescription());

        pdf.section("Checks");
        if (checks.isEmpty()) {
            pdf.row("Checks", "Not run");
        }
        checks.forEach(pdf::row);

        pdf.section("Payee");
        pdf.row("Name", c.getPayeeName());
        pdf.row("Relationship", c.getPayeeRelationship());
        pdf.row("Mobile", c.getPayeeMobile());
        pdf.row("Payout method", c.getPayeePayoutMethod());
        pdf.row("Account / wallet", c.getPayeeAccountRef());
        pdf.row("Verified", c.isPayoutVerified()
                ? "Yes" + (blank(c.getPayoutVerifiedBy()) ? "" : ", by " + c.getPayoutVerifiedBy()) + at(c.getPayoutVerifiedAt())
                : "No");

        pdf.section("Decision");
        pdf.row("Assessed by", blank(c.getRecommendedBy()) ? null : c.getRecommendedBy() + at(c.getRecommendedAt()));
        pdf.row("Decided by", blank(c.getAdjudicatedBy()) ? null : c.getAdjudicatedBy() + at(c.getResolvedAt()));
        pdf.row("Four-eyes", fourEyes(c));
        pdf.row("Rejection reason", c.getRejectionReason());
        pdf.row("Payout amount", c.getPayoutAmount() == null ? null : money(c.getPayoutAmount()));
        pdf.row("Paid", c.getPaidAt() == null ? null : c.getPaidAt().format(STAMP));
        pdf.row("Payout reference", c.getPayoutReference());

        pdf.section("Documents on file");
        if (documents.isEmpty()) {
            pdf.row("Documents", "None");
        }
        for (PortfolioViews.DocumentView d : documents) {
            pdf.row(d.type(), d.fileName() + " (" + d.uploadedBy() + ", " + d.uploadedAt().replace('T', ' ') + ")");
        }

        history(pdf, history);
        return pdf.bytes();
    }

    /** The workflow log, oldest first; shared with the policy schedule. */
    static void history(PdfReport pdf, List<PortfolioViews.HistoryView> history) {
        pdf.section("History");
        if (history.isEmpty()) {
            pdf.row("History", "No entries");
        }
        for (PortfolioViews.HistoryView h : history) {
            String when = h.performedAt().length() >= 16 ? h.performedAt().substring(0, 16).replace('T', ' ') : h.performedAt();
            pdf.row(when, h.action() + " by " + h.performedBy() + (blank(h.details()) ? "" : ": " + h.details()));
        }
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

    private static String at(LocalDateTime when) {
        return when == null ? "" : " on " + when.format(STAMP);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
