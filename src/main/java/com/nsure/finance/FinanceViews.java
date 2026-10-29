package com.nsure.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class FinanceViews {

    private FinanceViews() {
    }

    public record AccountView(String code, String name, String accountType) {
    }

    public record RuleView(String eventType, String debitAccount, String creditAccount, String amountField,
            String domain, String description, String status, int version, String updatedBy,
            LocalDateTime updatedAt) {
    }

    public record LineView(int lineNo, String accountCode, String accountName, BigDecimal debit, BigDecimal credit) {
    }

    public record JournalView(String ref, String eventType, String sourceRef, String domain, LocalDate eventDate,
            LocalDate accountingDate, String periodCode, String currency, BigDecimal amount, String description,
            Integer ruleVersion, String status, String reversesRef, String reversedByRef, String glBatchRef,
            String createdBy, LocalDateTime createdAt, List<LineView> lines) {
    }

    public record PeriodView(String code, String status, String note, String updatedBy, LocalDateTime updatedAt,
            long journalCount, long unexportedCount) {
    }

    public record ExceptionView(Long id, String eventKey, String eventType, String sourceRef, BigDecimal amount,
            String reason, String status, LocalDateTime createdAt, String resolvedBy, LocalDateTime resolvedAt,
            String journalRef) {
    }

    public record GlBatchView(String ref, String status, int journalCount, int lineCount, BigDecimal debitTotal,
            BigDecimal creditTotal, String createdBy, LocalDateTime createdAt, String glReference,
            String acknowledgedBy, LocalDateTime acknowledgedAt, String note) {
    }

    public record TrialBalanceRow(String code, String name, String accountType, BigDecimal debit, BigDecimal credit,
            BigDecimal balance) {
    }

    public record TrialBalanceView(String period, List<TrialBalanceRow> rows, BigDecimal totalDebit,
            BigDecimal totalCredit, boolean balanced, LocalDateTime asOf) {
    }

    public record FinanceSummary(long journals, BigDecimal premiumIncome, BigDecimal claimsPaid,
            BigDecimal commissionExpense, BigDecimal reinsuranceCeded, BigDecimal suspense, long openExceptions,
            long unexportedJournals, long submittedBatches, LocalDateTime asOf) {
    }
}
