package com.nsure.group;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Response shapes for the group administration API. Amounts are BWP with two decimals. */
public final class GroupViews {

    private GroupViews() {
    }

    /** A scheme with live statistics computed from membership and invoices. */
    public record SchemeView(
            String ref,
            String name,
            String sector,
            String initials,
            String color,
            String status,
            LocalDate inceptionDate,
            LocalDate renewalDate,
            String renewal,
            String billingFrequency,
            int activeMembers,
            int pendingUnderwriting,
            BigDecimal monthlyPremium,
            BigDecimal outstandingBalance,
            int categories,
            // The quotation the master policy was activated from (accepted terms), if any.
            String quoteRef,
            Integer policyYear,
            // Send back in If-Match when changing the scheme (NS-TXN-001).
            long version) {
    }

    public record SchemeDetailView(
            SchemeView scheme,
            List<CategoryView> categories,
            List<MemberView> members,
            List<CensusBatchSummaryView> censusBatches,
            List<InvoiceView> invoices) {
    }

    public record CategoryView(
            String code,
            String name,
            String benefitBasis,
            BigDecimal salaryMultiple,
            BigDecimal fixedCover,
            BigDecimal freeCoverLimit,
            BigDecimal ratePerMille,
            int minEntryAge,
            int maxEntryAge,
            Integer lateEntryDays,
            int activeMembers,
            String updatedBy,
            LocalDateTime updatedAt) {
    }

    public record MemberView(
            Long id,
            String memberKey,
            String firstName,
            String lastName,
            String name,
            LocalDate dateOfBirth,
            String category,
            String categoryName,
            BigDecimal salary,
            String status,
            LocalDate joinDate,
            LocalDate exitDate,
            BigDecimal requestedCover,
            BigDecimal acceptedCover,
            BigDecimal pendingCover,
            BigDecimal monthlyPremium,
            String underwritingDecision,
            String underwritingReason,
            String underwrittenBy,
            LocalDateTime underwrittenAt,
            String sourceBatchRef,
            Boolean activelyAtWork,
            long version) {
    }

    public record CensusBatchSummaryView(
            String ref,
            String schemeRef,
            String externalBatchId,
            String mode,
            String status,
            String fileHash,
            int totalRows,
            int validRows,
            int invalidRows,
            int acceptedRows,
            int rejectedRows,
            String submittedBy,
            LocalDateTime submittedAt,
            String committedBy,
            LocalDateTime committedAt,
            int heldRows,
            String schemaVersion,
            String correctsBatchRef) {
    }

    public record CensusBatchView(
            String ref,
            String schemeRef,
            String schemeName,
            String externalBatchId,
            String mode,
            String status,
            String fileHash,
            int totalRows,
            int validRows,
            int invalidRows,
            int acceptedRows,
            int rejectedRows,
            String submittedBy,
            LocalDateTime submittedAt,
            String committedBy,
            LocalDateTime committedAt,
            List<CensusRowView> rows,
            // Rows held for an authorised review (AT-GL04-03), the template version and the batch corrected.
            int heldRows,
            String schemaVersion,
            String correctsBatchRef,
            // Preview (NS-GL04-005): a full month's premium on the rows' members before and after the batch.
            BigDecimal premiumBefore,
            BigDecimal premiumAfter,
            String prorationBasis) {
    }

    public record CensusRowView(
            int lineNo,
            String memberKey,
            String firstName,
            String lastName,
            String dateOfBirth,
            String category,
            String salary,
            String action,
            String effectiveDate,
            String status,
            String error,
            Long memberId,
            Long changeId,
            String rowKey,
            String holdReason,
            String reviewedBy,
            String reviewNote,
            String adjustmentRefs,
            Integer correctsLine,
            String correctedBy,
            BigDecimal premiumBefore,
            BigDecimal premiumAfter) {
    }

    public record InvoiceView(
            String ref,
            String schemeRef,
            String schemeName,
            String period,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal amount,
            BigDecimal paidAmount,
            BigDecimal outstanding,
            // Premium on lines the sponsor disputes; cover is not changed by a dispute (NS-GL05-003).
            BigDecimal disputedAmount,
            String status,
            Integer policyYear,
            int memberCount,
            String issuedBy,
            LocalDateTime issuedAt,
            List<InvoiceLineView> lines,
            List<ReceiptView> receipts,
            // Net supplementary adjustments on the balance and the adjustments themselves (NS-GL05-002).
            BigDecimal adjustmentAmount,
            List<AdjustmentView> adjustments,
            // The proration basis and rate source the invoice was priced on (NS-GL05-001).
            String prorationBasis,
            String rateSource) {
    }

    /** A supplementary debit or credit on an issued invoice, with its member lines against the original lines. */
    public record AdjustmentView(
            String ref,
            String invoiceRef,
            String period,
            Integer policyYear,
            String type,
            BigDecimal amount,
            BigDecimal appliedToInvoice,
            BigDecimal heldAsCredit,
            String sourceRef,
            String reason,
            String issuedBy,
            LocalDateTime issuedAt,
            List<AdjustmentLineView> lines) {
    }

    public record AdjustmentLineView(
            Long originalLineId,
            Long memberId,
            String memberKey,
            String memberName,
            String category,
            String changeType,
            BigDecimal coverAfter,
            int daysCovered,
            BigDecimal billedBefore,
            BigDecimal recalculated,
            BigDecimal amount) {
    }

    public record InvoiceLineView(
            Long memberId,
            String memberKey,
            String memberName,
            String category,
            BigDecimal acceptedCover,
            BigDecimal ratePerMille,
            int daysCovered,
            int daysInPeriod,
            BigDecimal premium,
            String disputeStatus,
            String disputeNote,
            Long lineId,
            BigDecimal paidAmount) {
    }

    public record ReceiptView(
            String ref,
            String reference,
            BigDecimal amount,
            BigDecimal outstandingAfter,
            String receivedBy,
            LocalDateTime receivedAt) {
    }

    /** A claim on a member's group cover (GL claim register). */
    public record ClaimView(
            String ref,
            String schemeRef,
            Long memberId,
            String memberKey,
            String member,
            String benefitType,
            LocalDate eventDate,
            BigDecimal claimedAmount,
            BigDecimal approvedAmount,
            String status,
            LocalDate nextReviewDate,
            String paymentReference,
            String note,
            String notifiedBy,
            LocalDateTime notifiedAt,
            String decidedBy,
            LocalDateTime decidedAt) {
    }

    /** The allocation just made (or the existing one for a repeated reference) and the invoice after it. */
    public record ReceiptAllocationView(ReceiptView receipt, InvoiceView invoice) {
    }
}
