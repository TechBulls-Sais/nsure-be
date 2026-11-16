package com.nsure.collections;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class CollectionsViews {

    private CollectionsViews() {
    }

    /** A policy's billing account (NS-SH04-004): paid-to and arrears come from allocations, never from a notice. */
    public record AccountView(String policyRef, String status, LocalDate paidTo, LocalDate nextDue,
            BigDecimal arrears, int overdueInstalments, List<String> unresolvedPayments,
            List<InstalmentView> instalments, List<ReceiptView> receipts) {
    }

    public record InstalmentView(String ref, LocalDate periodStart, LocalDate periodEnd, LocalDate dueDate,
            String purpose, BigDecimal billed, BigDecimal adjustments, BigDecimal allocated, BigDecimal outstanding,
            String status, List<AllocationView> allocations, long version) {
    }

    public record AllocationView(String ref, String sourceType, String sourceRef, BigDecimal amount,
            String allocatedBy, LocalDateTime allocatedAt, String reversalRef) {
    }

    public record ReceiptView(String receiptRef, LocalDate periodStart, LocalDate periodEnd, BigDecimal amount,
            String method, String status, LocalDateTime paidAt, String reversalRef, String reversalKind,
            String reversalReason) {
    }

    public record ReversalView(String ref, String receiptRef, String policyRef, String kind, BigDecimal amount,
            String reason, String reversedBy, LocalDateTime reversedAt, LocalDate paidTo, String policyStatus) {
    }

    public record BillingRunView(int issued, List<String> instalments) {
    }

    public record StatementView(String ref, String provider, LocalDate periodFrom, LocalDate periodTo,
            String uploadedBy, LocalDateTime uploadedAt, long lines, long matched, long openExceptions) {
    }

    public record ReconItemView(Long id, String statementRef, String side, Integer lineNo, String providerRef,
            BigDecimal amount, String currency, LocalDate valueDate, String status, String matchedType,
            String matchedRef, BigDecimal expectedAmount, String owner, long ageDays, String resolvedBy,
            LocalDateTime resolvedAt, String resolution) {
    }
}
