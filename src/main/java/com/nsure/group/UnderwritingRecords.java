package com.nsure.group;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Group member underwriting records (GL03): evidence requirements and per-benefit decisions. */
public final class UnderwritingRecords {

    private UnderwritingRecords() {
    }

    /**
     * One evidence requirement for a member's referral (NS-GL03-003): what is needed and why. Medical evidence is
     * restricted to assessors; the employer sees only that something is outstanding. Received evidence stays valid
     * for the insurer's period and may be reused within it (NS-GL03-006).
     */
    @Entity(name = "GroupMemberEvidence")
    @Table(name = "grp_member_evidence")
    public static class MemberEvidence {
        public static final String REQUESTED = "REQUESTED";
        public static final String RECEIVED = "RECEIVED";
        public static final String WAIVED = "WAIVED";
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "member_id", nullable = false)
        SchemeMember member;
        /** MEDICAL_QUESTIONNAIRE, MEDICAL_EXAMINATION, BLOOD_PROFILE, FINANCIAL_EVIDENCE, AAW_CONFIRMATION, HEALTH_DECLARATION. */
        @Column(nullable = false, length = 30)
        String requirement;
        /** EXCESS_COVER, LATE_ENTRANT, NOT_AT_WORK or RISK_EXCEPTION. */
        @Column(nullable = false, length = 30)
        String reason;
        @Column(nullable = false, length = 3)
        String restricted;
        @Column(name = "evidence_status", nullable = false, length = 20)
        String status;
        @Column(length = 500)
        String note;
        @Column(name = "requested_by", nullable = false, length = 100)
        String requestedBy;
        @Column(name = "requested_at", nullable = false)
        LocalDateTime requestedAt;
        @Column(name = "received_by", length = 100)
        String receivedBy;
        @Column(name = "received_on")
        LocalDate receivedOn;
        @Column(name = "valid_until")
        LocalDate validUntil;
    }

    /**
     * One decision on one benefit of a member's referred cover (NS-GL03-004): accepted and pending amounts, loading,
     * exclusions, effective date; above the authority limit it waits for a second underwriter, and modified terms wait
     * for the member's acceptance until the offer expires. Never deleted: the member's decision history.
     */
    @Entity(name = "GroupMemberDecision")
    @Table(name = "grp_member_decision")
    public static class MemberDecision {
        public static final String AWAITING_AUTHORITY = "AWAITING_AUTHORITY";
        public static final String OFFERED = "OFFERED";
        public static final String FINAL = "FINAL";
        public static final String EXPIRED = "EXPIRED";
        public static final String REJECTED = "REJECTED_BY_AUTHORITY";
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "member_id", nullable = false)
        SchemeMember member;
        @Column(name = "benefit_type", nullable = false, length = 30)
        String benefitType;
        /** ACCEPT or DECLINE. */
        @Column(nullable = false, length = 20)
        String decision;
        @Column(name = "requested_amount", nullable = false, precision = 15, scale = 2)
        BigDecimal requestedAmount;
        @Column(name = "accepted_amount", nullable = false, precision = 15, scale = 2)
        BigDecimal acceptedAmount;
        @Column(name = "pending_amount", nullable = false, precision = 15, scale = 2)
        BigDecimal pendingAmount;
        @Column(name = "loading_percent", precision = 7, scale = 2)
        BigDecimal loadingPercent;
        @Column(length = 500)
        String exclusions;
        @Column(name = "effective_date", nullable = false)
        LocalDate effectiveDate;
        @Column(length = 500)
        String reason;
        @Column(name = "decision_status", nullable = false, length = 30)
        String status;
        @Column(name = "decided_by", nullable = false, length = 100)
        String decidedBy;
        @Column(name = "decided_at", nullable = false)
        LocalDateTime decidedAt;
        @Column(name = "authorised_by", length = 100)
        String authorisedBy;
        @Column(name = "authorised_at")
        LocalDateTime authorisedAt;
        @Column(name = "offer_expires_on")
        LocalDate offerExpiresOn;
        /** ACCEPTED or DECLINED by the member. */
        @Column(name = "member_response", length = 20)
        String memberResponse;
        @Column(name = "member_response_note", length = 500)
        String memberResponseNote;
        @Column(name = "member_response_at")
        LocalDateTime memberResponseAt;
    }
}
