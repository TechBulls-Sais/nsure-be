package com.nsure.finance;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A balanced subledger journal. The event key is unique, so one business event posts at most once. The lines and
 * amounts never change after posting; only the reversal and GL batch references are filled in later.
 */
@Entity
@Table(name = "fin_journal")
public class Journal {

    public static final String POSTED = "POSTED";
    public static final String REVERSED = "REVERSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Optimistic locking (NS-TXN-001): a write based on a stale copy is refused with 409, never overwrites.
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20, updatable = false)
    private String ref;

    @Column(name = "event_key", nullable = false, unique = true, length = 80, updatable = false)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 60, updatable = false)
    private String eventType;

    @Column(name = "source_ref", nullable = false, length = 60, updatable = false)
    private String sourceRef;

    @Column(name = "business_domain", nullable = false, length = 20, updatable = false)
    private String domain;

    @Column(name = "event_date", nullable = false, updatable = false)
    private LocalDate eventDate;

    @Column(name = "accounting_date", nullable = false, updatable = false)
    private LocalDate accountingDate;

    @Column(name = "period_code", nullable = false, length = 7, updatable = false)
    private String periodCode;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(nullable = false, precision = 15, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 300, updatable = false)
    private String description;

    @Column(name = "rule_version", updatable = false)
    private Integer ruleVersion;

    /** The exact posting-rule version this journal was posted with (Blueprint §7: each entry retains its rule). */
    @Column(name = "posting_rule_id", updatable = false)
    private Long postingRuleId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "reverses_ref", length = 20)
    private String reversesRef;

    @Column(name = "reversed_by_ref", length = 20)
    private String reversedByRef;

    @Column(name = "gl_batch_ref", length = 20)
    private String glBatchRef;

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // NS-SH08-001: the dimensions a journal carries (from the business event and its rule).
    @Column(name = "policy_ref", length = 40, updatable = false)
    private String policyRef;

    @Column(length = 100, updatable = false)
    private String product;

    @Column(name = "intermediary_ref", length = 20, updatable = false)
    private String intermediaryRef;

    @Column(name = "treaty_ref", length = 20, updatable = false)
    private String treatyRef;

    @Column(name = "scheme_ref", length = 20, updatable = false)
    private String schemeRef;

    @Column(name = "cost_centre", length = 20, updatable = false)
    private String costCentre;

    @OneToMany(mappedBy = "journal", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo asc")
    private List<JournalLine> lines = new ArrayList<>();

    protected Journal() {
    }

    Journal(String ref, String eventKey, String eventType, String sourceRef, String domain, LocalDate eventDate,
            LocalDate accountingDate, String periodCode, BigDecimal amount, String description, Integer ruleVersion,
            Long postingRuleId, String createdBy) {
        this.postingRuleId = postingRuleId;
        this.ref = ref;
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.sourceRef = sourceRef;
        this.domain = domain;
        this.eventDate = eventDate;
        this.accountingDate = accountingDate;
        this.periodCode = periodCode;
        this.currency = "BWP";
        this.amount = amount;
        this.description = description.length() <= 300 ? description : description.substring(0, 300);
        this.ruleVersion = ruleVersion;
        this.status = POSTED;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    Journal dimensions(String policyRef, String product, String intermediaryRef, String treatyRef, String schemeRef,
            String costCentre) {
        this.policyRef = clip(policyRef, 40);
        this.product = clip(product, 100);
        this.intermediaryRef = clip(intermediaryRef, 20);
        this.treatyRef = clip(treatyRef, 20);
        this.schemeRef = clip(schemeRef, 20);
        this.costCentre = clip(costCentre, 20);
        return this;
    }

    private static String clip(String value, int max) {
        return value == null || value.isBlank() ? null : value.length() <= max ? value : value.substring(0, max);
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getProduct() {
        return product;
    }

    public String getIntermediaryRef() {
        return intermediaryRef;
    }

    public String getTreatyRef() {
        return treatyRef;
    }

    public String getSchemeRef() {
        return schemeRef;
    }

    public String getCostCentre() {
        return costCentre;
    }

    void addLine(String accountCode, BigDecimal debit, BigDecimal credit) {
        lines.add(new JournalLine(this, lines.size() + 1, accountCode, debit, credit));
    }

    /** Debits equal credits and neither is zero: the only journals the subledger accepts (NS-SH08-003). */
    boolean isBalanced() {
        BigDecimal debits = lines.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = lines.stream().map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
        return debits.signum() > 0 && debits.compareTo(credits) == 0;
    }

    void markReversed(String reversalRef) {
        this.status = REVERSED;
        this.reversedByRef = reversalRef;
    }

    void markReversal(String originalRef) {
        this.reversesRef = originalRef;
    }

    void assignGlBatch(String batchRef) {
        this.glBatchRef = batchRef;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getEventKey() {
        return eventKey;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getDomain() {
        return domain;
    }

    public LocalDate getEventDate() {
        return eventDate;
    }

    public LocalDate getAccountingDate() {
        return accountingDate;
    }

    public String getPeriodCode() {
        return periodCode;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public Integer getRuleVersion() {
        return ruleVersion;
    }

    public Long getPostingRuleId() {
        return postingRuleId;
    }

    public String getStatus() {
        return status;
    }

    public String getReversesRef() {
        return reversesRef;
    }

    public String getReversedByRef() {
        return reversedByRef;
    }

    public String getGlBatchRef() {
        return glBatchRef;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<JournalLine> getLines() {
        return lines;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
