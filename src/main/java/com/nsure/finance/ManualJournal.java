package com.nsure.finance;

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
 * A manual journal request (Solution Blueprint §7): proposed by one person with a reason, posted to the subledger only
 * when someone else approves it. Until then it touches no balance.
 */
@Entity
@Table(name = "fin_manual_journal")
public class ManualJournal {

    public static final String PENDING = "PENDING";
    public static final String POSTED = "POSTED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 30)
    private String ref;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "accounting_date", nullable = false)
    private LocalDate accountingDate;

    @Column(name = "lines_json", nullable = false, length = 4000)
    private String linesJson;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "made_by", nullable = false, length = 100)
    private String madeBy;

    @Column(name = "made_at", nullable = false)
    private LocalDateTime madeAt;

    @Column(name = "checked_by", length = 100)
    private String checkedBy;

    @Column(name = "checked_at")
    private LocalDateTime checkedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Column(name = "journal_ref", length = 30)
    private String journalRef;

    protected ManualJournal() {
    }

    ManualJournal(String ref, String description, String reason, LocalDate accountingDate, String linesJson,
            BigDecimal amount, String madeBy) {
        this.ref = ref;
        this.description = description;
        this.reason = reason;
        this.accountingDate = accountingDate;
        this.linesJson = linesJson;
        this.amount = amount;
        this.status = PENDING;
        this.madeBy = madeBy;
        this.madeAt = LocalDateTime.now();
    }

    void posted(String journalRef, String checker) {
        this.status = POSTED;
        this.journalRef = journalRef;
        this.checkedBy = checker;
        this.checkedAt = LocalDateTime.now();
    }

    void rejected(String note, String checker) {
        this.status = REJECTED;
        this.decisionNote = note;
        this.checkedBy = checker;
        this.checkedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getDescription() {
        return description;
    }

    public String getReason() {
        return reason;
    }

    public LocalDate getAccountingDate() {
        return accountingDate;
    }

    public String getLinesJson() {
        return linesJson;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getStatus() {
        return status;
    }

    public String getMadeBy() {
        return madeBy;
    }

    public LocalDateTime getMadeAt() {
        return madeAt;
    }

    public String getCheckedBy() {
        return checkedBy;
    }

    public LocalDateTime getCheckedAt() {
        return checkedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public String getJournalRef() {
        return journalRef;
    }
}
