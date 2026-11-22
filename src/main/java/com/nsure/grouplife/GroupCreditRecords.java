package com.nsure.grouplife;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Group credit life records (GL06): the lender agreement and loan classes, each loan's cover history and premium
 * schedule, lender remittance statements, lender interface files, retained exceptions, credit claims and refunds.
 */
public final class GroupCreditRecords {

    private GroupCreditRecords() {
    }

    /** The lender's scheme agreement (NS-GL06-001): limits, premium basis, remittance cycle and refund formula. */
    @Entity(name = "GroupLenderAgreement")
    @Table(name = "grp_lender_agreement")
    public static class LenderAgreement {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "lender_ref", length = 20)
        String lenderRef;
        @Column(name = "agreement_no", nullable = false, length = 40)
        String agreementNo;
        @Column(name = "effective_from", nullable = false)
        LocalDate effectiveFrom;
        @Column(name = "premium_basis", nullable = false, length = 20)
        String premiumBasis;
        @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
        BigDecimal ratePerMille;
        @Column(name = "balance_basis", nullable = false, length = 20)
        String balanceBasis;
        @Column(name = "premium_timing", nullable = false, length = 20)
        String premiumTiming;
        @Column(name = "remittance_day", nullable = false)
        int remittanceDay;
        @Column(name = "death_cap", nullable = false, precision = 19, scale = 2)
        BigDecimal deathCap;
        @Column(name = "ci_cap", nullable = false, precision = 19, scale = 2)
        BigDecimal ciCap;
        @Column(name = "td_monthly_cap", nullable = false, precision = 19, scale = 2)
        BigDecimal tdMonthlyCap;
        @Column(name = "td_max_months", nullable = false)
        int tdMaxMonths;
        @Column(name = "over_cap_rule", nullable = false, length = 10)
        String overCapRule;
        @Column(name = "min_age", nullable = false)
        int minAge;
        @Column(name = "max_age", nullable = false)
        int maxAge;
        @Column(name = "max_age_at_end", nullable = false)
        int maxAgeAtEnd;
        @Column(name = "max_term", nullable = false)
        int maxTerm;
        @Column(name = "notification_days", nullable = false)
        int notificationDays;
        @Column(name = "claim_basis", nullable = false, length = 30)
        String claimBasis;
        @Column(name = "refund_method", nullable = false, length = 20)
        String refundMethod;
        @Column(name = "refund_deduction_percent", nullable = false, precision = 7, scale = 2)
        BigDecimal refundDeductionPercent;
        @Column(name = "commission_percent", nullable = false, precision = 7, scale = 2)
        BigDecimal commissionPercent;
        @Column(name = "ceded_percent", nullable = false, precision = 7, scale = 2)
        BigDecimal cededPercent;
        @Column(name = "stale_balance_days", nullable = false)
        int staleBalanceDays;
        @Column(name = "updated_by", nullable = false, length = 100)
        String updatedBy;
        @Column(name = "updated_at", nullable = false)
        LocalDateTime updatedAt;
    }

    /** A loan class under the agreement: its maximum term and amount, the benefits it may elect, an optional rate. */
    @Entity(name = "GroupLoanClass")
    @Table(name = "grp_loan_class")
    public static class LoanClass {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "class_code", nullable = false, length = 20)
        String code;
        @Column(nullable = false, length = 80)
        String name;
        @Column(name = "max_term", nullable = false)
        int maxTerm;
        @Column(name = "max_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal maxAmount;
        @Column(nullable = false, length = 100)
        String benefits;
        @Column(name = "rate_per_mille", precision = 9, scale = 4)
        BigDecimal ratePerMille;
    }

    /** One entry in a loan's cover history (NS-GL06-001): activation, referral, balance, settlement, claim. */
    @Entity(name = "GroupCreditLoanEvent")
    @Table(name = "grp_credit_loan_event")
    public static class LoanEvent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "loan_id", nullable = false)
        Long loanId;
        @Column(name = "event_type", nullable = false, length = 30)
        String type;
        @Column(name = "effective_date", nullable = false)
        LocalDate effectiveDate;
        @Column(precision = 19, scale = 2)
        BigDecimal balance;
        @Column(name = "cover_amount", precision = 19, scale = 2)
        BigDecimal coverAmount;
        @Column(length = 300)
        String detail;
        @Column(name = "source_ref", length = 60)
        String sourceRef;
        @Column(nullable = false, length = 100)
        String actor;
        @Column(name = "recorded_at", nullable = false)
        LocalDateTime recordedAt;
    }

    /** A premium instalment due on a loan (NS-GL06-003): single, annual or monthly, on the agreed balance basis. */
    @Entity(name = "GroupCreditPremium")
    @Table(name = "grp_credit_premium")
    public static class CreditPremium {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "loan_id", nullable = false)
        Long loanId;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "installment_no", nullable = false)
        int installmentNo;
        @Column(name = "due_date", nullable = false)
        LocalDate dueDate;
        @Column(name = "period_from", nullable = false)
        LocalDate periodFrom;
        @Column(name = "period_to", nullable = false)
        LocalDate periodTo;
        @Column(name = "basis_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal basisAmount;
        @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
        BigDecimal ratePerMille;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal amount;
        @Column(name = "premium_status", nullable = false, length = 20)
        String status;
        @Column(name = "remittance_ref", length = 20)
        String remittanceRef;
    }

    /** The lender's premium statement for a remittance period: the premium receivable, kept apart from loan balances. */
    @Entity(name = "GroupCreditRemittance")
    @Table(name = "grp_credit_remittance")
    public static class CreditRemittance {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "billing_period", nullable = false, length = 7)
        String period;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal amount;
        @Column(name = "paid_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal paidAmount;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal outstanding;
        @Column(nullable = false)
        int installments;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal commission;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal ceded;
        @Column(name = "remittance_status", nullable = false, length = 20)
        String status;
        @Column(length = 1000)
        String reconciliation;
        @Column(name = "issued_by", nullable = false, length = 100)
        String issuedBy;
        @Column(name = "issued_at", nullable = false)
        LocalDateTime issuedAt;
    }

    /** A lender interface file (NS-GL06-004): idempotent on its reference, with its control totals and result. */
    @Entity(name = "GroupCreditFile")
    @Table(name = "grp_credit_file")
    public static class CreditFile {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "file_reference", nullable = false, length = 60)
        String fileReference;
        @Column(name = "file_kind", nullable = false, length = 20)
        String kind;
        @Column(name = "schema_version", nullable = false, length = 10)
        String schemaVersion;
        @Column(name = "file_hash", nullable = false, length = 64)
        String fileHash;
        @Column(name = "row_count", nullable = false)
        int rowCount;
        @Column(name = "control_total", precision = 19, scale = 2)
        BigDecimal controlTotal;
        @Column(name = "file_status", nullable = false, length = 20)
        String status;
        @Column(name = "file_result", length = 4000)
        String result;
        @Column(name = "received_by", nullable = false, length = 100)
        String receivedBy;
        @Column(name = "received_at", nullable = false)
        LocalDateTime receivedAt;
    }

    /** A retained lender-data exception: unknown loan, arrears, balance anomaly, stale or missing balance. */
    @Entity(name = "GroupCreditException")
    @Table(name = "grp_credit_exception")
    public static class CreditException {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "loan_id")
        Long loanId;
        @Column(name = "lender_loan_id", length = 60)
        String lenderLoanId;
        @Column(name = "exception_type", nullable = false, length = 30)
        String type;
        @Column(nullable = false, length = 500)
        String detail;
        @Column(name = "file_reference", length = 60)
        String fileReference;
        @Column(name = "exception_status", nullable = false, length = 20)
        String status;
        @Column(name = "raised_at", nullable = false)
        LocalDateTime raisedAt;
        @Column(name = "resolved_by", length = 100)
        String resolvedBy;
        @Column(name = "resolved_at")
        LocalDateTime resolvedAt;
        @Column(name = "resolution_note", length = 300)
        String resolutionNote;
    }

    /** A claim on a borrower's cover (NS-GL06-005): benefit under the caps, lender/borrower split, recovery. */
    @Entity(name = "GroupCreditClaim")
    @Table(name = "grp_credit_claim")
    public static class CreditClaim {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "loan_id", nullable = false)
        Long loanId;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(nullable = false, length = 30)
        String benefit;
        @Column(name = "event_date", nullable = false)
        LocalDate eventDate;
        @Column(name = "claim_status", nullable = false, length = 20)
        String status;
        @Column(name = "balance_at_event", nullable = false, precision = 19, scale = 2)
        BigDecimal balanceAtEvent;
        @Column(name = "benefit_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal benefitAmount;
        @Column(name = "lender_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal lenderAmount;
        @Column(name = "borrower_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal borrowerAmount;
        @Column(name = "monthly_amount", precision = 19, scale = 2)
        BigDecimal monthlyAmount;
        Integer months;
        @Column(length = 500)
        String calculation;
        @Column(name = "recovery_amount", precision = 19, scale = 2)
        BigDecimal recoveryAmount;
        @Column(name = "recovery_status", length = 20)
        String recoveryStatus;
        @Column(length = 500)
        String note;
        @Column(name = "notified_by", nullable = false, length = 100)
        String notifiedBy;
        @Column(name = "notified_at", nullable = false)
        LocalDateTime notifiedAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "payment_reference", length = 80)
        String paymentReference;
        @Column(name = "paid_at")
        LocalDateTime paidAt;
    }

    /** An unearned-premium refund (NS-GL06-006): one per loan, approved by a second person, then paid. */
    @Entity(name = "GroupCreditRefund")
    @Table(name = "grp_credit_refund")
    public static class CreditRefund {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "loan_id", nullable = false)
        Long loanId;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(nullable = false, length = 20)
        String reason;
        @Column(name = "effective_date", nullable = false)
        LocalDate effectiveDate;
        @Column(name = "months_unearned", nullable = false)
        int monthsUnearned;
        @Column(name = "gross_unearned", nullable = false, precision = 19, scale = 2)
        BigDecimal grossUnearned;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal deduction;
        @Column(name = "refund_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal refundAmount;
        @Column(name = "commission_reversal", nullable = false, precision = 19, scale = 2)
        BigDecimal commissionReversal;
        @Column(name = "reinsurance_reversal", nullable = false, precision = 19, scale = 2)
        BigDecimal reinsuranceReversal;
        @Column(nullable = false, length = 300)
        String formula;
        @Column(name = "refund_status", nullable = false, length = 20)
        String status;
        @Column(name = "source_ref", length = 60)
        String sourceRef;
        @Column(name = "requested_by", nullable = false, length = 100)
        String requestedBy;
        @Column(name = "requested_at", nullable = false)
        LocalDateTime requestedAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "decision_note", length = 300)
        String decisionNote;
        @Column(name = "payment_reference", length = 80)
        String paymentReference;
        @Column(name = "paid_at")
        LocalDateTime paidAt;
    }
}
