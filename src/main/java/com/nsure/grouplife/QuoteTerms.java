package com.nsure.grouplife;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The terms one quotation version was offered on (NS-GL01-001/002): the census version it was rated on, the
 * prospect sponsor, where its rates came from (approved rate table versions or a negotiated rate), the pricing
 * breakdown, the loadings and adjustment, how long it is valid, and the benefit design by category. Kept beside the
 * version and never rewritten once a later version supersedes it.
 */
@Entity(name = "SchemeQuoteTerms")
@Table(name = "grp_quote_terms")
public class QuoteTerms {

    static final String TABLE = "TABLE";
    static final String NEGOTIATED = "NEGOTIATED";
    static final String RATED = "RATED";
    static final String INDICATIVE = "INDICATIVE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "quote_id", nullable = false)
    Long quoteId;
    @Column(name = "census_id")
    Long censusId;
    @Column(name = "sponsor_ref", length = 20)
    String sponsorRef;
    @Column(name = "rate_source", nullable = false, length = 20)
    String rateSource;
    @Column(name = "pricing_status", nullable = false, length = 20)
    String pricingStatus;
    @Column(name = "technical_premium", precision = 19, scale = 2)
    BigDecimal technicalPremium;
    @Column(name = "adjustment_percent", precision = 7, scale = 2)
    BigDecimal adjustmentPercent;
    @Column(name = "expense_percent", precision = 7, scale = 2)
    BigDecimal expensePercent;
    @Column(name = "commission_percent", precision = 7, scale = 2)
    BigDecimal commissionPercent;
    @Column(name = "profit_percent", precision = 7, scale = 2)
    BigDecimal profitPercent;
    @Column(name = "validity_days", nullable = false)
    int validityDays;
    @Column(name = "rate_tables", length = 500)
    String rateTables;
    // Long text: longtext on MySQL, longvarchar on H2 (changeset 136).
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "pricing_detail")
    String pricingDetail;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "design")
    String design;
}
