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
            int categories) {
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
            String sourceBatchRef) {
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
            LocalDateTime committedAt) {
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
            List<CensusRowView> rows) {
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
            Long memberId) {
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
            String status,
            int memberCount,
            String issuedBy,
            LocalDateTime issuedAt,
            List<InvoiceLineView> lines,
            List<ReceiptView> receipts) {
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
            BigDecimal premium) {
    }

    public record ReceiptView(
            String ref,
            String reference,
            BigDecimal amount,
            BigDecimal outstandingAfter,
            String receivedBy,
            LocalDateTime receivedAt) {
    }

    /** The allocation just made (or the existing one for a repeated reference) and the invoice after it. */
    public record ReceiptAllocationView(ReceiptView receipt, InvoiceView invoice) {
    }
}
