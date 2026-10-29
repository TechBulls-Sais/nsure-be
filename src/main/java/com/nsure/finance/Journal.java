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

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "event_key", nullable = false, unique = true, length = 80)
    private String eventKey;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "source_ref", nullable = false, length = 60)
    private String sourceRef;

    @Column(name = "business_domain", nullable = false, length = 20)
    private String domain;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "accounting_date", nullable = false)
    private LocalDate accountingDate;

    @Column(name = "period_code", nullable = false, length = 7)
    private String periodCode;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(name = "rule_version")
    private Integer ruleVersion;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "reverses_ref", length = 20)
    private String reversesRef;

    @Column(name = "reversed_by_ref", length = 20)
    private String reversedByRef;

    @Column(name = "gl_batch_ref", length = 20)
    private String glBatchRef;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "journal", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo asc")
    private List<JournalLine> lines = new ArrayList<>();

    protected Journal() {
    }

    Journal(String ref, String eventKey, String eventType, String sourceRef, String domain, LocalDate eventDate,
            LocalDate accountingDate, String periodCode, BigDecimal amount, String description, Integer ruleVersion,
            String createdBy) {
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
}
