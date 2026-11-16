package com.nsure.credit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** What the credit life API returns. */
public final class CreditViews {

    private CreditViews() {
    }

    public record LenderView(String ref, String name, String contactEmail, boolean active, long loans) {
    }

    public record RuleView(Long id, String product, int minEntryAge, int maxExitAge, int exceptionExitAge,
            int minTermMonths, int maxTermMonths, BigDecimal ratePerMille, int waitingMonths, String permanentDisability,
            boolean temporaryDisabilityOffered, boolean criticalIllnessOffered, String coverBasis, int staleBalanceDays,
            BigDecimal refundDeductionPercent, String updatedBy, LocalDateTime updatedAt) {
    }

    public record LoanView(
            String ref,
            String lenderRef,
            String lenderName,
            String lenderLoanId,
            String policyRef,
            String policyStatus,
            String customerRef,
            String borrowerName,
            BigDecimal principal,
            int termMonths,
            boolean secured,
            boolean pensioner,
            String premiumBasis,
            BigDecimal monthlyPremium,
            BigDecimal singlePremium,
            boolean permanentDisability,
            boolean temporaryDisability,
            boolean criticalIllness,
            int waitingMonths,
            LocalDate disbursementDate,
            LocalDate coverStart,
            LocalDate coverEnd,
            // QUOTED, AWAITING_PREMIUM, AWAITING_DISBURSEMENT, COVER_ACTIVE, COVER_OFF, SETTLED, CLAIMED or CANCELLED.
            String status,
            BigDecimal balance,
            LocalDate balanceAsOf,
            boolean balanceStale,
            String balanceException,
            LocalDate settledOn,
            String settlementReference,
            String claimRef,
            BigDecimal lenderAmount,
            BigDecimal estateAmount,
            String refinancedFrom,
            LocalDateTime createdAt) {
    }

    public record BalanceView(Long id, String sourceReference, LocalDate effectiveDate, BigDecimal balance,
            String status, String note, String receivedBy, LocalDateTime receivedAt) {
    }

    public record RefundView(String ref, String loanRef, String policyRef, String borrowerName, BigDecimal amount,
            String formula, String status, LocalDateTime createdAt, String paidBy, LocalDateTime paidAt,
            String paymentReference) {
    }

    /** Who a claim on the loan would pay, and whether the balance is current enough to say (NS-IL05-005). */
    public record ClaimAssessment(
            LocalDate eventDate,
            boolean coverActiveAtEvent,
            boolean inWaitingPeriod,
            BigDecimal sumAssured,
            BigDecimal balance,
            LocalDate balanceAsOf,
            boolean manualResolution,
            BigDecimal lenderAmount,
            BigDecimal estateAmount,
            List<String> notes) {
    }

    /** What an early settlement on a date would refund, before anything is closed. */
    public record SettlementPreview(LocalDate settlementDate, long monthsElapsed, long monthsRemaining,
            BigDecimal refund, String formula, boolean blocked, String blockedBecause) {
    }

    public record LoanDetail(LoanView loan, List<BalanceView> balances, RefundView refund,
            ClaimAssessment claimToday, SettlementPreview settlementToday) {
    }

    public record BatchRow(String lenderLoanId, String outcome, String note) {
    }

    public record BatchResult(String batchReference, int rows, int applied, int duplicates, int conflicts,
            int unknown, List<BatchRow> results) {
    }

    public record Summary(long loans, long coverActive, long awaiting, long exceptions, long refundsDue,
            BigDecimal outstandingBalance) {
    }
}
