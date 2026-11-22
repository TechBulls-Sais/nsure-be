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
            List<InstalmentView> instalments, List<ReceiptView> receipts, MandateView mandate,
            List<InstructionView> instructions) {
    }

    /** A payment mandate (NS-SH04-002); the account or wallet number is only ever shown masked. */
    public record MandateView(String ref, String policyRef, String channel, String institution, String accountMasked,
            String accountHolder, Integer debitDay, String status, String source, String createdBy,
            LocalDateTime createdAt, String cancelledBy, LocalDateTime cancelledAt, String cancelReason) {
    }

    public record InstructionView(String ref, String batchRef, String instalmentRef, String policyRef, String channel,
            String institution, String accountMasked, BigDecimal amount, String currency, LocalDate periodStart,
            LocalDate periodEnd, LocalDate collectionDate, String status, String providerReference, String resultCode,
            String resultText, String receiptRef, Long transactionId, String reversalRef, LocalDateTime submittedAt,
            LocalDateTime respondedAt, LocalDateTime returnedAt) {
    }

    /** A collection batch with its control totals; {@code notes} explains what preparation left out. */
    public record CollectionBatchView(String ref, LocalDate collectionDate, String provider, String status,
            int instructionCount, BigDecimal totalAmount, String fileRef, String preparedBy, LocalDateTime preparedAt,
            String approvedBy, LocalDateTime approvedAt, String submittedBy, LocalDateTime submittedAt,
            LocalDateTime completedAt, String note, java.util.Map<String, Long> byStatus,
            List<InstructionView> instructions, List<String> notes) {
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

    /** A staff-keyed reversal waiting for, or decided by, a second person (NS-SH04-005). */
    public record ReversalRequestView(String ref, String receiptRef, String policyRef, String kind, BigDecimal amount,
            String reason, String status, String requestedBy, LocalDateTime requestedAt, String decidedBy,
            LocalDateTime decidedAt, String decisionNote, String reversalRef, LocalDate paidTo, String policyStatus) {
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
