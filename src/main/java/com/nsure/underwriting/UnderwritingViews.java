package com.nsure.underwriting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the underwriting API returns. */
public final class UnderwritingViews {

    private UnderwritingViews() {
    }

    public record CaseView(
            String ref,
            String applicationRef,
            String customerRef,
            String customerName,
            String product,
            BigDecimal sumAssured,
            BigDecimal appliedPremium,
            BigDecimal aggregateExposure,
            Integer entryAge,
            String ruleSetVersion,
            String status,
            List<String> referralReasons,
            String decision,
            BigDecimal loadingPercent,
            String exclusion,
            LocalDate postponeUntil,
            String decisionReason,
            String decidedBy,
            LocalDateTime decidedAt,
            int offerVersion,
            BigDecimal offerPremium,
            String offerStatus,
            String offerAcceptedBy,
            LocalDateTime offerAcceptedAt,
            String assignedTo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            long version,
            int evidenceOutstanding,
            // The largest aggregate exposure the signed-in user may decide; null when not limited.
            BigDecimal myAuthority,
            boolean withinMyAuthority,
            String occupation,
            // The decision stands until this date, then must be reassessed (NS-IL02-003/006).
            LocalDate decisionValidUntil,
            boolean decisionExpired,
            // NOT_SCREENED, CLEAR, HIT (blocks acceptance and issue), CLEARED or CONFIRMED.
            String screeningStatus,
            String screeningDetail,
            String screeningResolvedBy,
            LocalDateTime screeningResolvedAt,
            // The signed-in user's loading limit and decision types; null when not limited.
            BigDecimal myMaxLoadingPercent,
            List<String> myDecisionTypes) {
    }

    public record EvidenceView(
            Long id,
            String lifeName,
            String requirement,
            boolean restricted,
            String status,
            LocalDate dueDate,
            boolean overdue,
            String requestedBy,
            LocalDateTime requestedAt,
            String resolvedBy,
            LocalDateTime resolvedAt,
            // Withheld on restricted (clinical) evidence from anyone who is not an underwriter.
            String note,
            Long lifeId,
            int reminderCount,
            LocalDateTime lastRemindedAt) {
    }

    public record DecisionView(
            int sequenceNo,
            String decision,
            BigDecimal loadingPercent,
            String exclusion,
            LocalDate postponeUntil,
            String reason,
            int offerVersion,
            BigDecimal offerPremium,
            String decidedBy,
            LocalDateTime decidedAt,
            // What it rested on: the life (customer reference), the benefit and the evidence as it stood.
            String lifeRef,
            String benefit,
            BigDecimal sumAssured,
            String evidenceBasis) {
    }

    /** One other contract the customer holds, counted in the aggregate exposure. */
    public record ExposureLine(String policyRef, String product, BigDecimal sumAssured, String status) {
    }

    public record LifeView(Long id, String fullName, String relationship, LocalDate dateOfBirth, int ageAtEntry,
            BigDecimal coverAmount) {
    }

    public record CaseDetail(
            CaseView underwritingCase,
            List<LifeView> lives,
            List<EvidenceView> evidence,
            List<DecisionView> decisions,
            List<ExposureLine> exposure,
            RuleSetView rules) {
    }

    public record RuleSetView(
            Long id,
            String product,
            String versionLabel,
            int minEntryAge,
            int maxEntryAge,
            BigDecimal autoMaxSumAssured,
            BigDecimal medicalEvidenceAbove,
            BigDecimal aggregateExposureLimit,
            boolean referTobacco,
            boolean referTreatment,
            String updatedBy,
            LocalDateTime updatedAt,
            List<String> referDisclosureCodes,
            String referOccupations,
            BigDecimal maxLoadingPercent,
            int decisionValidityDays,
            // A proposed change waiting for a second approver, or null.
            RuleChangeView pendingChange) {
    }

    /** A proposed rule set change and its outcome (NS-IL01-005): applied only once a second person approves it. */
    public record RuleChangeView(Long id, Long ruleSetId, String product, String versionLabel,
            com.fasterxml.jackson.databind.JsonNode proposal, com.fasterxml.jackson.databind.JsonNode previous,
            String status, String proposedBy, LocalDateTime proposedAt, String decidedBy, LocalDateTime decidedAt,
            String decisionNote) {
    }

    public record AuthorityView(Long roleId, String role, BigDecimal maxSumAssured, String updatedBy,
            LocalDateTime updatedAt, BigDecimal maxLoadingPercent, List<String> decisionTypes) {
    }

    public record Summary(long referred, long evidencePending, long offersPending, long acceptedToday,
            long decidedByRules, long total) {
    }
}
