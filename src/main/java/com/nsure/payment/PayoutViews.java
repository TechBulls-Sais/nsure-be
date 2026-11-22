package com.nsure.payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Read models for payout instructions and their exception queue. Account numbers are masked. */
public final class PayoutViews {

    private PayoutViews() {
    }

    /**
     * One instruction with its steps and the rail's callbacks. {@code actions}: what a payments user may do now
     * (reconcile, resolve); {@code simulator}: what the SIMULATED bank may do next in a demo.
     */
    public record InstructionView(String ref, String sourceType, String sourceRef, String policyRef, int attempt,
            String idempotencyKey, String payeeName, String payoutMethod, String account, BigDecimal amount,
            String currency, String status, String provider, String providerReference, String replacesRef,
            String createdBy, LocalDateTime createdAt, String approvedBy, LocalDateTime approvedAt, String submittedBy,
            LocalDateTime submittedAt, LocalDateTime outcomeAt, String failureCode, String failureReason,
            String exceptionState, String exceptionNote, String exceptionResolvedBy,
            LocalDateTime exceptionResolvedAt, String bankStatus, List<StepView> steps, List<CallbackView> callbacks,
            List<String> actions, List<String> simulator) {
    }

    public record StepView(String from, String to, String note, String actor, LocalDateTime at) {
    }

    public record CallbackView(String eventId, String outcome, String reasonCode, String reasonText, String result,
            String note, LocalDateTime receivedAt) {
    }
}
