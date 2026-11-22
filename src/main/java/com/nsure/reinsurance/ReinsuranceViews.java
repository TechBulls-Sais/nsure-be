package com.nsure.reinsurance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ReinsuranceViews {

    private ReinsuranceViews() {
    }

    public record TreatyView(
            String ref,
            String name,
            String reinsurer,
            String treatyType,
            List<String> products,
            BigDecimal cededPercent,
            BigDecimal retention,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            // Approved and in force today: cedes new premiums automatically.
            boolean ceding,
            String createdBy,
            LocalDateTime createdAt,
            String approvedBy,
            LocalDateTime approvedAt,
            long cessionCount,
            BigDecimal totalCededPremium,
            // NS-SH07-002: most ceded per life (null = no limit); the policy of a facultative arrangement.
            BigDecimal capacity,
            String policyRef,
            // NS-SH07-001: version, participants and terms; the excess-of-loss model.
            int version,
            String amendsRef,
            String amendmentReason,
            List<ParticipantView> participants,
            BigDecimal commissionPercent,
            String premiumBasis,
            String reportingFrequency,
            String bordereauFormat,
            BigDecimal xlPriority,
            BigDecimal xlLimit
    ) {
    }

    public record ParticipantView(String reinsurer, BigDecimal sharePercent, boolean lead) {
    }

    /** NS-SH07-006: gross, ceded and retained per treaty for a month, against what the subledger posted. */
    public record ReconciliationView(String period, List<ReconRow> treaties, BigDecimal grossPremium,
            BigDecimal cededPremium, BigDecimal retainedPremium, BigDecimal ledgerCededPremium, BigDecimal difference,
            BigDecimal recoveriesRecognised, BigDecimal ledgerRecoveries, List<QueueItem> missingAgreements,
            List<QueueItem> capacityBreaches, List<QueueItem> overdueRecoveries) {
    }

    public record ReconRow(String treatyRef, String treatyName, long cessions, BigDecimal grossPremium,
            BigDecimal cededPremium, BigDecimal retainedPremium) {
    }

    public record QueueItem(String ref, String policyRef, String detail, BigDecimal amount, long ageDays) {
    }

    public record BordereauLine(String ref, String policyRef, String product, BigDecimal sumAssured, BigDecimal cededSum,
            BigDecimal premium, BigDecimal cededPremium, String claimRef, BigDecimal claimAmount,
            BigDecimal recoveryAmount, String receiptRef) {
    }

    /** One version of a policy's ceded exposure (NS-SH07-002/003). */
    public record ExposureView(String ref, String policyRef, String customerRef, String product, int version,
            BigDecimal sumAssured, BigDecimal aggregateSum, BigDecimal retainedSum, BigDecimal cededSum,
            BigDecimal cededShare, String treatyRef, String treatyName, String facultativeRef, String facultativeStatus,
            String status, LocalDate effectiveFrom, LocalDate endedOn, String sourceEventType, String reason,
            String supersedesRef, LocalDateTime createdAt) {
    }

    /** A facultative referral and its placement (NS-SH07-002). */
    public record FacultativeView(String ref, String policyRef, String customerRef, String product,
            BigDecimal sumAssured, BigDecimal aggregateSum, BigDecimal excessSum, String treatyRef, String status,
            String referredReason, LocalDateTime referredAt, String reinsurer, BigDecimal offeredSum, String offeredBy,
            LocalDateTime offeredAt, BigDecimal acceptedSum, String acceptanceRef, String decisionNote,
            String decidedBy, LocalDateTime decidedAt, String facTreatyRef) {
    }

    public record CessionView(
            String ref,
            String treatyRef,
            String treatyName,
            String policyRef,
            String product,
            BigDecimal sumAssured,
            BigDecimal cededSum,
            BigDecimal premium,
            BigDecimal cededPremium,
            BigDecimal cededShare,
            String bordereauRef,
            String sourceEventId,
            LocalDateTime createdAt,
            // NS-SH07-003: what a cession came from or adjusts, and the exposure version applied.
            String receiptRef,
            String adjustsRef,
            String exposureRef,
            LocalDate eventDate,
            String reason
    ) {
    }

    public record RecoveryView(
            String ref,
            String treatyRef,
            String treatyName,
            String reinsurer,
            String claimRef,
            String policyRef,
            BigDecimal claimAmount,
            BigDecimal cededShare,
            BigDecimal recoveryAmount,
            String status,
            LocalDateTime createdAt,
            BigDecimal receivedAmount,
            String receivedReference,
            String receivedNote,
            String receivedBy,
            LocalDateTime receivedAt,
            // NS-SH07-005: the recovery ledger.
            String stage,
            LocalDate eventDate,
            BigDecimal claimPaidAmount,
            BigDecimal originalAmount,
            String disputeReason,
            String disputedBy,
            LocalDateTime disputedAt,
            BigDecimal writeOffAmount,
            String writeOffReason,
            String writeOffRequestedBy,
            LocalDateTime writeOffRequestedAt,
            String writeOffDecidedBy,
            LocalDateTime writeOffDecidedAt,
            long ageDays
    ) {
    }

    public record BordereauView(
            String ref,
            String treatyRef,
            String treatyName,
            String reinsurer,
            String period,
            int recordCount,
            BigDecimal totalCededPremium,
            BigDecimal totalCededSum,
            String status,
            String createdBy,
            LocalDateTime createdAt,
            // NS-SH07-004: premium or claims, and the reinsurer's side of the lifecycle.
            String kind,
            int correctionCount,
            String ackReference,
            String correctionNote,
            String settlementReference,
            BigDecimal settledAmount,
            BigDecimal totalRecovery,
            String format
    ) {
    }
}
