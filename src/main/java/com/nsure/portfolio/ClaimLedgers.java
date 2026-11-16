package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** The small append-only records behind claim reserves, benefit limits and evidence checklists (SH05). */
public final class ClaimLedgers {

    private ClaimLedgers() {
    }

    /** One change to a claim's case reserve, with the balance after it (NS-SH05-003). */
    @Entity(name = "ClaimReserveMovement")
    @Table(name = "clm_reserve_movement")
    public static class ReserveMovement {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "claim_ref", nullable = false, length = 20)
        private String claimRef;

        @Column(name = "change_amount", nullable = false, precision = 19, scale = 2)
        private BigDecimal change;

        @Column(nullable = false, precision = 19, scale = 2)
        private BigDecimal balance;

        @Column(nullable = false, length = 300)
        private String reason;

        @Column(name = "created_by", nullable = false, length = 100)
        private String createdBy;

        @Column(name = "created_at", nullable = false)
        private LocalDateTime createdAt;

        protected ReserveMovement() {
        }

        ReserveMovement(String claimRef, BigDecimal change, BigDecimal balance, String reason, String createdBy) {
            this.claimRef = claimRef;
            this.change = change;
            this.balance = balance;
            this.reason = reason;
            this.createdBy = createdBy;
            this.createdAt = LocalDateTime.now();
        }

        public String getClaimRef() {
            return claimRef;
        }

        public BigDecimal getChange() {
            return change;
        }

        public BigDecimal getBalance() {
            return balance;
        }

        public String getReason() {
            return reason;
        }

        public String getCreatedBy() {
            return createdBy;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }
    }

    /**
     * One movement against a benefit limit (NS-SH05-005): RESERVED at approval, then RELEASED and CONSUMED when the
     * payout settles. A life's and the policy's use is the sum of reserved minus released plus consumed.
     */
    @Entity(name = "ClaimLimitEntry")
    @Table(name = "clm_limit_entry")
    public static class LimitEntry {

        static final String RESERVED = "RESERVED";
        static final String RELEASED = "RELEASED";
        static final String CONSUMED = "CONSUMED";

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "policy_ref", nullable = false, length = 20)
        private String policyRef;

        @Column(name = "life_key", nullable = false, length = 60)
        private String lifeKey;

        @Column(name = "claim_ref", nullable = false, length = 20)
        private String claimRef;

        @Column(nullable = false, length = 12)
        private String entry;

        @Column(nullable = false, precision = 19, scale = 2)
        private BigDecimal amount;

        @Column(name = "created_by", nullable = false, length = 100)
        private String createdBy;

        @Column(name = "created_at", nullable = false)
        private LocalDateTime createdAt;

        protected LimitEntry() {
        }

        LimitEntry(String policyRef, String lifeKey, String claimRef, String entry, BigDecimal amount,
                String createdBy) {
            this.policyRef = policyRef;
            this.lifeKey = lifeKey;
            this.claimRef = claimRef;
            this.entry = entry;
            this.amount = amount;
            this.createdBy = createdBy;
            this.createdAt = LocalDateTime.now();
        }

        public String getEntry() {
            return entry;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getClaimRef() {
            return claimRef;
        }
    }

    /** A document a claim type needs; product null means every product (NS-SH05-003). */
    @Entity(name = "ClaimEvidenceRequirement")
    @Table(name = "clm_evidence_requirement")
    public static class EvidenceRequirement {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(length = 100)
        private String product;

        @Column(name = "claim_type", nullable = false, length = 60)
        private String claimType;

        @Column(name = "document_type", nullable = false, length = 60)
        private String documentType;

        @Column(nullable = false)
        private int mandatory;

        @Column(name = "display_order", nullable = false)
        private int displayOrder;

        protected EvidenceRequirement() {
        }

        public String getProduct() {
            return product;
        }

        public String getClaimType() {
            return claimType;
        }

        public String getDocumentType() {
            return documentType;
        }

        public boolean isMandatory() {
            return mandatory == 1;
        }

        public int getDisplayOrder() {
            return displayOrder;
        }
    }
}
