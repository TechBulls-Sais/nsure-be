package com.nsure.portfolio;

import static com.nsure.portfolio.PdfReport.STAMP;
import static com.nsure.portfolio.PdfReport.last4;
import static com.nsure.portfolio.PdfReport.longDate;
import static com.nsure.portfolio.PdfReport.money;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The claim summary as an A4 PDF: the one record of a claim as decided, issued once the claim is paid or rejected
 * (see {@link ClaimDocumentService}). It sets out what was claimed, the checks made, the decision and why, the
 * payment and who received it, the evidence on file and the claim's timeline. The claimant can download it too,
 * so account numbers are masked.
 */
final class ClaimSummaryPdf {

    /** Stored with each summary; bump it when the layout changes so older summaries are replaced on start-up. */
    static final int LAYOUT_VERSION = 2;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    /** Each automatic check, said as what it confirms. */
    private static final Map<String, String> CHECKS = Map.of(
            "Policy Active", "The policy was active",
            "Waiting Period", "The event was after the waiting period",
            "Premiums Paid", "Premiums were paid up",
            "Beneficiary Match", "The claimant is a named beneficiary",
            "No Duplicate", "No earlier claim for the same event");

    private ClaimSummaryPdf() {
    }

    static byte[] render(InsuranceClaim c, InsurancePolicy policy, Map<String, String> checks,
            List<PortfolioViews.DocumentView> documents, List<PortfolioViews.HistoryView> history, String issuedBy) {
        boolean paid = ClaimWorkflow.PAID.equals(c.getStatus());
        boolean rejected = ClaimWorkflow.REJECTED.equals(c.getStatus());
        String decided = c.getResolvedAt() == null ? null : longDate(c.getResolvedAt().toString());

        PdfReport pdf = new PdfReport("Claim summary " + c.getRef(), "Claim summary",
                c.getRef() + " · " + c.getProduct() + " · issued " + LocalDateTime.now().format(STAMP) + " by " + issuedBy);

        pdf.keyFacts(new String[][] {
                {"Claim number", c.getRef()},
                {"Outcome", paid ? "Paid" : rejected ? "Rejected" : c.getStatus()},
                {"Policy", c.getPolicyRef()},
                {"Amount claimed", money(c.getAmount())},
                {"Amount paid", paid && c.getPayoutAmount() != null ? money(c.getPayoutAmount()) : "Nothing paid"},
                {rejected ? "Rejected on" : "Decided on", decided},
        });

        // 1 ── the claim
        pdf.section("1. The claim");
        pdf.row("Claim number", c.getRef());
        pdf.row("Policy", c.getPolicyRef() + " · " + c.getProduct()
                + (policy == null || policy.getTier() == null ? "" : " · " + policy.getTier()));
        pdf.row("Policyholder", c.getCustomerName());
        pdf.row("Claim type", c.getClaimType());
        pdf.row("Date of event", longDate(c.getEventDate()));
        pdf.row("Lodged on", longDate(c.getRecordDate()));
        pdf.row("Amount claimed", money(c.getAmount()));
        pdf.row("What happened", c.getDescription());
        pdf.row("How it was handled", ClaimWorkflow.STP.equals(c.getProcessingType())
                ? "Straight-through: every check passed and it was within the automatic limit."
                : ClaimWorkflow.MANUAL_REVIEW.equals(c.getProcessingType())
                        ? "Reviewed by an assessor" + (blank(c.getStpIneligibleReason()) ? "."
                                : ", because: " + lowerFirst(c.getStpIneligibleReason()) + ".")
                        : c.getProcessingType());

        // 2 ── the claimant
        pdf.section("2. Claimant");
        pdf.row("Name", c.getClaimantName());
        pdf.row("Relationship to policyholder", c.getClaimantRelationship());
        pdf.row("Mobile", c.getClaimantMobile());

        // 3 ── the checks
        pdf.section("3. Checks");
        pdf.paragraph("Each claim is checked against the policy before it is decided.");
        List<String[]> checkRows = new ArrayList<>();
        checks.forEach((name, result) -> checkRows.add(new String[] {CHECKS.getOrDefault(name, name), result}));
        pdf.table(new String[] {"Check", "Result"}, new float[] {0.8f, 0.2f}, checkRows, false);

        // 4 ── the decision
        pdf.section("4. Decision");
        pdf.row("Outcome", paid ? "Approved and paid" : rejected ? "Rejected" : c.getStatus());
        pdf.row("Reason", rejected ? c.getRejectionReason() : null);
        pdf.row("Approved despite", blank(c.getCheckOverrideReason()) ? null
                : String.join(" and ", ClaimWorkflow.failedSoftChecks(c)) + " failing, because: "
                        + c.getCheckOverrideReason());
        pdf.row("Audit", ClaimWorkflow.auditFlag(c));
        pdf.row("Assessed by", blank(c.getRecommendedBy()) ? null : c.getRecommendedBy() + on(c.getRecommendedAt()));
        pdf.row("Decided by", blank(c.getAdjudicatedBy()) ? null : c.getAdjudicatedBy() + on(c.getResolvedAt()));
        pdf.row("Approval control", fourEyes(c));

        // 5 ── the payment
        pdf.section("5. Payment");
        if (paid) {
            pdf.row("Amount paid", c.getPayoutAmount() == null ? null : money(c.getPayoutAmount())
                    + (c.getPayoutAmount() != null && c.getPayoutAmount() != c.getAmount()
                            ? " (claimed " + money(c.getAmount()) + ")" : ""));
            pdf.row("Paid on", c.getPaidAt() == null ? null : c.getPaidAt().format(WHEN));
            pdf.row("Paid to", blank(c.getPayeeName()) ? null : c.getPayeeName()
                    + (blank(c.getPayeeRelationship()) ? "" : " (" + c.getPayeeRelationship() + ")"));
            pdf.row("Paid by", blank(c.getPayeePayoutMethod()) ? null : c.getPayeePayoutMethod()
                    + (blank(c.getPayeeAccountRef()) ? "" : ", account ending " + last4(c.getPayeeAccountRef())));
            pdf.row("Payee details", c.isPayoutVerified()
                    ? "Verified" + (blank(c.getPayoutVerifiedBy()) ? "" : " by " + c.getPayoutVerifiedBy())
                            + on(c.getPayoutVerifiedAt())
                            + (blank(c.getPayeeSource()) ? "" : "; " + lowerFirst(c.getPayeeSource()))
                    : "Not verified");
            pdf.row("Payment reference", c.getPayoutReference());
        } else if (rejected) {
            pdf.paragraph("No payment was made, because the claim was rejected.");
        } else {
            pdf.paragraph("No payment has been made yet.");
        }

        // 6 ── the evidence
        pdf.section("6. Documents on file");
        if (documents.isEmpty()) {
            pdf.paragraph("No documents were added to this claim.");
        } else {
            List<String[]> rows = new ArrayList<>();
            for (PortfolioViews.DocumentView d : documents) {
                rows.add(new String[] {d.type(), d.fileName(), d.uploadedBy(), longDate(d.uploadedAt())});
            }
            pdf.table(new String[] {"Document", "File", "Added by", "Added on"},
                    new float[] {0.24f, 0.38f, 0.16f, 0.22f}, rows, false);
        }

        // 7 ── the timeline
        pdf.section("7. Timeline");
        if (history.isEmpty()) {
            pdf.paragraph("No events were recorded.");
        } else {
            List<String[]> rows = new ArrayList<>();
            for (PortfolioViews.HistoryView h : history) {
                rows.add(new String[] {when(h.performedAt()), h.action(), h.performedBy(),
                        blank(h.details()) ? "" : h.details()});
            }
            pdf.table(new String[] {"When", "Event", "By", "Details"},
                    new float[] {0.2f, 0.2f, 0.14f, 0.46f}, rows, false);
        }

        // 8 ── what to know
        pdf.section("8. Important");
        pdf.bullet("This summary records the claim as decided. It is issued once, after the claim is paid or rejected.");
        if (rejected) {
            pdf.bullet("If you disagree with this decision, contact Nsure and quote claim number " + c.getRef()
                    + ". You may be asked for more evidence.");
        } else {
            pdf.bullet("For any question about this claim, contact Nsure and quote claim number " + c.getRef() + ".");
        }
        pdf.bullet("Account numbers are shown by their last four digits only.");
        return pdf.bytes();
    }

    private static String fourEyes(InsuranceClaim c) {
        if (c.getFourEyesApplied() == null) {
            return null;
        }
        if (c.getFourEyesApplied()) {
            return "Assessed and approved by two different people";
        }
        return c.getRecommendedBy() != null && c.getRecommendedBy().equals(c.getAdjudicatedBy())
                ? "Approved by the assessor, as a super admin override" : "Single approval (the two-person control was off)";
    }

    private static String on(LocalDateTime when) {
        return when == null ? "" : " on " + when.format(WHEN);
    }

    private static String when(String isoDateTime) {
        try {
            return LocalDateTime.parse(isoDateTime.length() > 19 ? isoDateTime.substring(0, 19) : isoDateTime)
                    .format(WHEN);
        } catch (RuntimeException e) {
            return isoDateTime.replace('T', ' ');
        }
    }

    private static String lowerFirst(String value) {
        return value.isEmpty() ? value : Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
