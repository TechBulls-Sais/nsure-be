package com.nsure.distribution;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class DistributionViews {

    private DistributionViews() {
    }

    public record IntermediaryView(
            String ref,
            String name,
            String type,
            String licenceNumber,
            String status,
            String statusReason,
            LocalDate appointmentExpiry,
            // Active and unexpired today: can take new policy assignments.
            boolean canTakeBusiness,
            String parentRef,
            String parentName,
            String email,
            String mobile,
            long openAssignments,
            // Commission accrued and not yet on a statement (clawbacks included).
            BigDecimal unstatementedBalance,
            String createdBy,
            LocalDateTime createdAt,
            String updatedBy,
            LocalDateTime updatedAt
    ) {
    }

    public record CommissionRuleView(
            Long id,
            String product,
            String intermediaryType,
            BigDecimal initialRatePercent,
            BigDecimal renewalRatePercent,
            LocalDate effectiveFrom,
            // The rule in force today for its product and intermediary type.
            boolean current,
            String createdBy,
            LocalDateTime createdAt
    ) {
    }

    public record AssignmentView(
            Long id,
            String policyRef,
            String customerName,
            String product,
            String intermediaryRef,
            String intermediaryName,
            String intermediaryType,
            LocalDate effectiveFrom,
            LocalDate endedOn,
            boolean open,
            String assignedBy,
            LocalDateTime assignedAt,
            // AT-SH06-03: a transfer is PENDING until a second person approves it.
            String status,
            String approvedBy,
            String transferReason
    ) {
    }

    public record CommissionView(
            String ref,
            String intermediaryRef,
            String intermediaryName,
            String policyRef,
            String product,
            String kind,
            BigDecimal basisAmount,
            BigDecimal ratePercent,
            BigDecimal amount,
            String status,
            String reversesRef,
            String statementRef,
            String sourceEventId,
            LocalDateTime createdAt,
            // NS-SH06-003: receipt, INITIAL/RENEWAL/CLAWBACK, calculation trace, premium date and agreement.
            String receiptRef,
            String basis,
            String calcTrace,
            java.time.LocalDate premiumDate,
            String agreementRef,
            String disputeReason
    ) {
    }

    public record StatementView(
            String ref,
            String intermediaryRef,
            String intermediaryName,
            BigDecimal netAmount,
            int lineCount,
            String status,
            String issuedBy,
            LocalDateTime issuedAt,
            String paidBy,
            LocalDateTime paidAt,
            List<CommissionView> lines,
            String approvedBy,
            LocalDateTime approvedAt,
            // NS-SH06-005: a negative statement carried onto this one.
            String carriedFromRef,
            BigDecimal carriedAmount,
            String batchRef
    ) {
    }

    public record ExceptionView(String ref, String policyRef, String product, String intermediaryRef,
            BigDecimal premiumAmount, String basis, String receiptRef, java.time.LocalDate premiumDate,
            String reasonCode, String reason, String status, String resolvedBy, LocalDateTime resolvedAt,
            String resolution, String commissionRef, LocalDateTime createdAt) {
    }

    /** A payment batch; {@code lines} is the bank file (one per statement, account masked). */
    public record PaymentBatchView(String ref, String status, int statementCount, BigDecimal totalAmount,
            String provider, String bankFileRef, String preparedBy, LocalDateTime preparedAt, String releasedBy,
            LocalDateTime releasedAt, LocalDateTime settledAt, List<BankLine> lines) {
    }

    public record BankLine(String statementRef, String intermediaryRef, String intermediaryName, String bankName,
            String accountMasked, String branch, BigDecimal amount, String status) {
    }

    public record AgreementView(String ref, String intermediaryRef, String intermediaryName, int version,
            List<String> products, String channels, int clawbackMonths, String bankName, String accountMasked,
            String bankBranch, java.time.LocalDate effectiveFrom, String status, String note, String createdBy,
            LocalDateTime createdAt, String approvedBy, LocalDateTime approvedAt) {
    }
}
