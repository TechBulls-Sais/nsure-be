package com.nsure.finance;

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

/** A journal line: never updated once posted (NS-SH08-004); a correction is a reversal journal. */
@Entity
@Table(name = "fin_journal_line")
public class JournalLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_id", nullable = false, updatable = false)
    private Journal journal;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Column(name = "account_code", nullable = false, length = 20, updatable = false)
    private String accountCode;

    @Column(nullable = false, precision = 15, scale = 2, updatable = false)
    private BigDecimal debit;

    @Column(nullable = false, precision = 15, scale = 2, updatable = false)
    private BigDecimal credit;

    protected JournalLine() {
    }

    JournalLine(Journal journal, int lineNo, String accountCode, BigDecimal debit, BigDecimal credit) {
        this.journal = journal;
        this.lineNo = lineNo;
        this.accountCode = accountCode;
        this.debit = debit;
        this.credit = credit;
    }

    public int getLineNo() {
        return lineNo;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public BigDecimal getDebit() {
        return debit;
    }

    public BigDecimal getCredit() {
        return credit;
    }
}
