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

/** Group lifecycle records: quotations, scheme benefits, scheme events and group credit loans. */
public final class GroupLifeRecords {

    private GroupLifeRecords() {
    }

    /** One version of a scheme quotation; a revision adds a version and never overwrites an offer (NS-GL01-001). */
    @Entity(name = "SchemeQuote")
    @Table(name = "grp_quote")
    public static class Quote {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "version_no", nullable = false)
        int versionNo;
        @Column(name = "sponsor_name", nullable = false, length = 100)
        String sponsorName;
        @Column(nullable = false, length = 80)
        String sector;
        @Column(nullable = false)
        int lives;
        @Column(name = "salary_roll", nullable = false, precision = 19, scale = 2)
        BigDecimal salaryRoll;
        @Column(nullable = false, length = 500)
        String benefits;
        @Column(name = "rating_basis", nullable = false, length = 30)
        String ratingBasis;
        @Column(name = "salary_multiple", nullable = false, precision = 7, scale = 2)
        BigDecimal salaryMultiple;
        @Column(name = "rate_per_mille", nullable = false, precision = 9, scale = 4)
        BigDecimal ratePerMille;
        @Column(name = "annual_premium", nullable = false, precision = 19, scale = 2)
        BigDecimal annualPremium;
        @Column(name = "free_cover_limit", precision = 19, scale = 2)
        BigDecimal freeCoverLimit;
        @Column(name = "valid_until", nullable = false)
        LocalDate validUntil;
        @Column(nullable = false, length = 20)
        String status;
        @Column(length = 1000)
        String notes;
        @Column(name = "scheme_ref", length = 20)
        String schemeRef;
        @Column(name = "created_by", nullable = false, length = 100)
        String createdBy;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "decision_note", length = 500)
        String decisionNote;
    }

    /** A benefit a scheme provides, each with its own definition (NS-GL02-003). */
    @Entity(name = "SchemeBenefit")
    @Table(name = "grp_scheme_benefit")
    public static class Benefit {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "benefit_type", nullable = false, length = 30)
        String benefitType;
        @Column(nullable = false, length = 30)
        String basis;
        @Column(precision = 7, scale = 2)
        BigDecimal multiple;
        @Column(name = "fixed_amount", precision = 19, scale = 2)
        BigDecimal fixedAmount;
        @Column(name = "waiting_months")
        Integer waitingMonths;
        @Column(length = 800)
        String terms;
        @Column(name = "updated_by", nullable = false, length = 100)
        String updatedBy;
        @Column(name = "updated_at", nullable = false)
        LocalDateTime updatedAt;
        /** PENDING after a change until a second person approves the plan; APPROVED then. */
        @Column(name = "approval_status", nullable = false, length = 20)
        String approvalStatus = "APPROVED";
        @Column(name = "approved_by", length = 100)
        String approvedBy;
        @Column(name = "approved_at")
        LocalDateTime approvedAt;
    }

    /**
     * A renewal, suspension, closure, transfer or reactivation, requested with its impact and decided by a second
     * person. The full impact it was raised with (members, unpaid invoices, claims) is kept beside the summary.
     */
    @Entity(name = "SchemeEvent")
    @Table(name = "grp_scheme_event")
    public static class SchemeEvent {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "record_ref", nullable = false, length = 20)
        String ref;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "event_type", nullable = false, length = 20)
        String eventType;
        @Column(nullable = false, length = 20)
        String status;
        @Column(name = "effective_date", nullable = false)
        LocalDate effectiveDate;
        @Column(name = "rate_change_percent", precision = 7, scale = 2)
        BigDecimal rateChangePercent;
        @Column(nullable = false, length = 2000)
        String impact;
        @Column(nullable = false, length = 500)
        String reason;
        @Column(name = "requested_by", nullable = false, length = 100)
        String requestedBy;
        @Column(name = "requested_at", nullable = false)
        LocalDateTime requestedAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "decision_note", length = 500)
        String decisionNote;
        @Column(name = "target_scheme_ref", length = 20)
        String targetSchemeRef;
        // A clob in Liquibase: longtext on MySQL, so mapped as long text rather than Hibernate's tinytext default.
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR)
        @Column(name = "impact_detail")
        String impactDetail;
    }

    /** A borrower's loan under a lender's group credit scheme, with its certificate (NS-GL06-001). */
    @Entity(name = "GroupCreditLoan")
    @Table(name = "grp_credit_loan")
    public static class CreditLoan {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "scheme_ref", nullable = false, length = 20)
        String schemeRef;
        @Column(name = "certificate_no", nullable = false, length = 40)
        String certificateNo;
        @Column(name = "lender_loan_id", nullable = false, length = 60)
        String lenderLoanId;
        @Column(name = "borrower_name", nullable = false, length = 150)
        String borrowerName;
        @Column(name = "loan_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal loanAmount;
        @Column(name = "cover_amount", nullable = false, precision = 19, scale = 2)
        BigDecimal coverAmount;
        @Column(name = "term_months", nullable = false)
        int termMonths;
        @Column(nullable = false, precision = 19, scale = 2)
        BigDecimal premium;
        @Column(nullable = false, length = 20)
        String status;
        @Column(length = 300)
        String note;
        @Column(name = "batch_ref", nullable = false, length = 60)
        String batchRef;
        @Column(name = "start_date", nullable = false)
        LocalDate startDate;
        @Column(name = "settled_on")
        LocalDate settledOn;
        @Column(precision = 19, scale = 2)
        BigDecimal refund;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        // GL06 (WP g): eligibility data, elections, premium basis, lender balance and cover end (NS-GL06-001..004).
        @Column(name = "loan_class", length = 20)
        String loanClass;
        @Column(name = "borrower_id_number", length = 30)
        String borrowerIdNumber;
        @Column(name = "date_of_birth")
        LocalDate dateOfBirth;
        @Column(name = "disbursement_date")
        LocalDate disbursementDate;
        @Column(length = 100)
        String benefits;
        @Column(name = "premium_basis", length = 20)
        String premiumBasis;
        @Column(name = "rate_per_mille", precision = 9, scale = 4)
        BigDecimal ratePerMille;
        @Column(precision = 19, scale = 2)
        BigDecimal balance;
        @Column(name = "balance_date")
        LocalDate balanceDate;
        @Column(name = "arrears_months")
        Integer arrearsMonths;
        @Column(name = "cover_end_date")
        LocalDate coverEndDate;
        @Column(name = "ci_paid", precision = 19, scale = 2)
        BigDecimal ciPaid;
        @Column(name = "interface_version", length = 10)
        String interfaceVersion;
    }
}
