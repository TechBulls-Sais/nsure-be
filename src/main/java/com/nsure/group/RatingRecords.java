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
import java.time.LocalDateTime;

/** Group rating records: versioned rate tables with their age bands, and the census a quotation is rated on. */
public final class RatingRecords {

    private RatingRecords() {
    }

    /**
     * One version of a group rate table for a benefit and rating basis (section 08 pricing): DRAFT until a second
     * person approves it; approving supersedes the earlier approved version. Quotations record the version they used.
     */
    @Entity(name = "GroupRateTable")
    @Table(name = "grp_rate_table")
    public static class RateTable {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "table_code", nullable = false, length = 30)
        String code;
        @Column(name = "version_no", nullable = false)
        int versionNo;
        @Column(nullable = false, length = 150)
        String label;
        @Column(name = "benefit_type", nullable = false, length = 30)
        String benefitType;
        @Column(name = "rating_basis", nullable = false, length = 20)
        String ratingBasis;
        @Column(name = "table_status", nullable = false, length = 20)
        String status;
        @Column(length = 500)
        String note;
        @Column(name = "created_by", nullable = false, length = 100)
        String createdBy;
        @Column(name = "created_at", nullable = false)
        LocalDateTime createdAt;
        @Column(name = "approved_by", length = 100)
        String approvedBy;
        @Column(name = "approved_at")
        LocalDateTime approvedAt;
    }

    /** The rate for ages from-to inclusive (age last birthday at the quotation date). */
    @Entity(name = "GroupRateBand")
    @Table(name = "grp_rate_band")
    public static class RateBand {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "table_id", nullable = false)
        RateTable table;
        @Column(name = "age_from", nullable = false)
        int ageFrom;
        @Column(name = "age_to", nullable = false)
        int ageTo;
        @Column(nullable = false, precision = 12, scale = 4)
        BigDecimal rate;
    }

    /** One numbered census a quotation was given, with its totals and whether every row passed validation. */
    @Entity(name = "QuoteCensus")
    @Table(name = "grp_quote_census")
    public static class QuoteCensus {
        public static final String VALID = "VALID";
        public static final String HAS_ERRORS = "HAS_ERRORS";
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "quote_ref", nullable = false, length = 20)
        String quoteRef;
        @Column(name = "census_version", nullable = false)
        int version;
        @Column(name = "file_hash", nullable = false, length = 64)
        String fileHash;
        @Column(name = "total_rows", nullable = false)
        int totalRows;
        @Column(name = "valid_rows", nullable = false)
        int validRows;
        @Column(name = "invalid_rows", nullable = false)
        int invalidRows;
        @Column(nullable = false)
        int lives;
        @Column(name = "salary_roll", nullable = false, precision = 19, scale = 2)
        BigDecimal salaryRoll;
        @Column(name = "loan_balance", nullable = false, precision = 19, scale = 2)
        BigDecimal loanBalance;
        @Column(name = "average_age", precision = 5, scale = 1)
        BigDecimal averageAge;
        @Column(length = 1000)
        String warnings;
        @Column(name = "census_status", nullable = false, length = 20)
        String status;
        @Column(name = "submitted_by", nullable = false, length = 100)
        String submittedBy;
        @Column(name = "submitted_at", nullable = false)
        LocalDateTime submittedAt;

        public Long getId() {
            return id;
        }

        public int getVersion() {
            return version;
        }

        public int getLives() {
            return lives;
        }

        public BigDecimal getSalaryRoll() {
            return salaryRoll;
        }

        public String getStatus() {
            return status;
        }

        public int getInvalidRows() {
            return invalidRows;
        }
    }

    /** A census row as received, with its validation outcome. */
    @Entity(name = "QuoteCensusRow")
    @Table(name = "grp_quote_census_row")
    public static class QuoteCensusRow {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false)
        @JoinColumn(name = "census_id", nullable = false)
        QuoteCensus census;
        @Column(name = "line_no", nullable = false)
        int lineNo;
        @Column(name = "member_key", length = 40)
        String memberKey;
        @Column(name = "date_of_birth", length = 20)
        String dateOfBirth;
        @Column(name = "category_code", length = 20)
        String categoryCode;
        @Column(length = 30)
        String salary;
        @Column(name = "loan_balance", length = 30)
        String loanBalance;
        @Column(name = "row_status", nullable = false, length = 10)
        String status;
        @Column(length = 500)
        String error;
    }
}
