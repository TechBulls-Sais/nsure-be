package com.nsure.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** IL05 individual credit life records. Fields are read and written by {@link CreditService}. */
public final class CreditRecords {

    private CreditRecords() {
    }

    @Entity(name = "Lender")
    @Table(name = "crd_lender")
    public static class Lender {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(nullable = false, length = 150)
        String name;
        @Column(name = "contact_email", length = 150)
        String contactEmail;
        @Column(nullable = false)
        boolean active;
        @Column(name = "created_by", nullable = false, length = 100)
        String createdBy;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
    }

    /** The product's credit rules: who may be covered, for how long, at what rate, and how a refund is worked out. */
    @Entity(name = "CreditRule")
    @Table(name = "crd_product_rule")
    public static class Rule {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(nullable = false, length = 60)
        String product;
        @Column(name = "min_entry_age", nullable = false)
        int minEntryAge;
        @Column(name = "max_exit_age", nullable = false)
        int maxExitAge;
        @Column(name = "exception_exit_age", nullable = false)
        int exceptionExitAge;
        @Column(name = "min_term_months", nullable = false)
        int minTermMonths;
        @Column(name = "max_term_months", nullable = false)
        int maxTermMonths;
        @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
        BigDecimal ratePerMille;
        @Column(name = "waiting_months", nullable = false)
        int waitingMonths;
        @Column(name = "permanent_disability", nullable = false, length = 20)
        String permanentDisability;
        @Column(name = "temporary_disability_offered", nullable = false)
        boolean temporaryDisabilityOffered;
        @Column(name = "critical_illness_offered", nullable = false)
        boolean criticalIllnessOffered;
        @Column(name = "cover_basis", nullable = false, length = 30)
        String coverBasis;
        @Column(name = "stale_balance_days", nullable = false)
        int staleBalanceDays;
        @Column(name = "refund_deduction_percent", nullable = false, precision = 7, scale = 2)
        BigDecimal refundDeductionPercent;
        @Column(name = "updated_by", length = 100)
        String updatedBy;
        @Column(name = "updated_at")
        LocalDateTime updatedAt;
    }

    /** A loan and the credit life policy that covers it. The balance is the lender's figure, never the premium due. */
    @Entity(name = "Loan")
    @Table(name = "crd_loan")
    public static class Loan {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "lender_ref", nullable = false, length = 20)
        String lenderRef;
        @Column(name = "lender_loan_id", nullable = false, length = 60)
        String lenderLoanId;
        @Column(name = "policy_ref", length = 20)
        String policyRef;
        @Column(name = "customer_ref", nullable = false, length = 40)
        String customerRef;
        @Column(name = "borrower_name", nullable = false, length = 150)
        String borrowerName;
        @Column(nullable = false, length = 60)
        String product;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal principal;
        @Column(name = "term_months", nullable = false)
        int termMonths;
        @Column(nullable = false)
        boolean secured;
        @Column(nullable = false)
        boolean pensioner;
        @Column(name = "premium_basis", nullable = false, length = 20)
        String premiumBasis;
        @Column(name = "monthly_premium", nullable = false, precision = 19, scale = 2)
        BigDecimal monthlyPremium;
        @Column(name = "single_premium", precision = 19, scale = 2)
        BigDecimal singlePremium;
        @Column(name = "permanent_disability", nullable = false)
        boolean permanentDisability;
        @Column(name = "temporary_disability", nullable = false)
        boolean temporaryDisability;
        @Column(name = "critical_illness", nullable = false)
        boolean criticalIllness;
        @Column(name = "waiting_months", nullable = false)
        int waitingMonths;
        @Column(name = "disbursement_date")
        LocalDate disbursementDate;
        @Column(name = "disbursement_reference", length = 80)
        String disbursementReference;
        @Column(name = "cover_start")
        LocalDate coverStart;
        /** OPEN until the loan ends; then SETTLED, CLAIMED or CANCELLED. What "open" means is derived from the policy. */
        @Column(nullable = false, length = 30)
        String status;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal balance;
        @Column(name = "balance_as_of")
        LocalDate balanceAsOf;
        @Column(name = "balance_exception", length = 500)
        String balanceException;
        @Column(name = "settled_on")
        LocalDate settledOn;
        @Column(name = "settlement_reference", length = 80)
        String settlementReference;
        @Column(name = "claim_ref", length = 20)
        String claimRef;
        @Column(name = "claim_event_date")
        LocalDate claimEventDate;
        @Column(name = "claim_cause", length = 30)
        String claimCause;
        @Column(name = "claim_accidental")
        Boolean claimAccidental;
        /** Who confirmed the lender's payment details before the claim was allocated (NS-IL05-005), and when. */
        @Column(name = "payee_verified_by", length = 100)
        String payeeVerifiedBy;
        @Column(name = "payee_verified_at")
        LocalDateTime payeeVerifiedAt;
        @Column(name = "lender_amount", precision = 19, scale = 2)
        BigDecimal lenderAmount;
        @Column(name = "estate_amount", precision = 19, scale = 2)
        BigDecimal estateAmount;
        @Column(name = "refinanced_from", length = 20)
        String refinancedFrom;
        @Column(name = "created_by", nullable = false, length = 100)
        String createdBy;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        @Version
        @Column(name = "row_version", nullable = false)
        long version;
    }

    /** One balance message from the lender, kept whether it was applied or held as a conflict. */
    @Entity(name = "LoanBalanceUpdate")
    @Table(name = "crd_balance_update")
    public static class BalanceUpdate {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "loan_ref", nullable = false, length = 20)
        String loanRef;
        @Column(name = "source_reference", nullable = false, length = 80)
        String sourceReference;
        @Column(name = "effective_date", nullable = false)
        LocalDate effectiveDate;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal balance;
        @Column(nullable = false, length = 20)
        String status;
        @Column(length = 500)
        String note;
        @Column(name = "received_by", nullable = false, length = 100)
        String receivedBy;
        @Column(name = "received_at", nullable = false)
        LocalDateTime receivedAt;
    }

    /** The unearned premium owed back on an early settlement: at most one per loan. */
    @Entity(name = "CreditRefund")
    @Table(name = "crd_refund")
    public static class Refund {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "loan_ref", nullable = false, length = 20)
        String loanRef;
        @Column(name = "policy_ref", length = 20)
        String policyRef;
        @Column(name = "borrower_name", length = 150)
        String borrowerName;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal amount;
        @Column(nullable = false, length = 500)
        String formula;
        @Column(nullable = false, length = 20)
        String status;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        @Column(name = "paid_by", length = 100)
        String paidBy;
        @Column(name = "paid_at")
        LocalDateTime paidAt;
        @Column(name = "payment_reference", length = 60)
        String paymentReference;
    }
}
