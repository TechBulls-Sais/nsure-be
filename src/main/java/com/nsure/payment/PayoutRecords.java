package com.nsure.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * The append-only records around payout instructions: each step an instruction took, each callback the rail sent
 * (kept by its event id, so a repeated callback is recognised and has no second effect, AT-SH05-02), and the
 * simulated bank's own book of the payments it was given.
 */
public final class PayoutRecords {

    private PayoutRecords() {
    }

    /** One change of an instruction's state, with who or what caused it. */
    @Entity(name = "PayoutStep")
    @Table(name = "pay_instruction_step")
    public static class Step {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "instruction_ref", nullable = false, length = 20)
        private String instructionRef;

        @Column(name = "from_status", length = 20)
        private String fromStatus;

        @Column(name = "to_status", nullable = false, length = 20)
        private String toStatus;

        @Column(length = 500)
        private String note;

        @Column(nullable = false, length = 100)
        private String actor;

        @Column(name = "occurred_at", nullable = false)
        private LocalDateTime occurredAt;

        protected Step() {
        }

        Step(String instructionRef, String fromStatus, String toStatus, String note, String actor) {
            this.instructionRef = instructionRef;
            this.fromStatus = fromStatus;
            this.toStatus = toStatus;
            this.note = note == null || note.length() <= 500 ? note : note.substring(0, 499) + "…";
            this.actor = actor;
            this.occurredAt = LocalDateTime.now();
        }

        public String getInstructionRef() {
            return instructionRef;
        }

        public String getFromStatus() {
            return fromStatus;
        }

        public String getToStatus() {
            return toStatus;
        }

        public String getNote() {
            return note;
        }

        public String getActor() {
            return actor;
        }

        public LocalDateTime getOccurredAt() {
            return occurredAt;
        }
    }

    /** A callback (or status enquiry answer) from the rail. {@code result}: APPLIED, DUPLICATE, IGNORED or CONFLICT. */
    @Entity(name = "PayoutCallback")
    @Table(name = "pay_callback")
    public static class Callback {

        public static final String APPLIED = "APPLIED";
        public static final String DUPLICATE = "DUPLICATE";
        public static final String IGNORED = "IGNORED";
        public static final String CONFLICT = "CONFLICT";

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "event_id", nullable = false, unique = true, length = 80)
        private String eventId;

        @Column(name = "instruction_ref", nullable = false, length = 20)
        private String instructionRef;

        @Column(name = "provider_reference", length = 60)
        private String providerReference;

        @Column(nullable = false, length = 20)
        private String outcome;

        @Column(name = "reason_code", length = 20)
        private String reasonCode;

        @Column(name = "reason_text", length = 300)
        private String reasonText;

        @Column(name = "call_result", nullable = false, length = 20)
        private String result;

        @Column(length = 300)
        private String note;

        @Column(name = "received_at", nullable = false)
        private LocalDateTime receivedAt;

        protected Callback() {
        }

        Callback(String eventId, String instructionRef, String providerReference, String outcome, String reasonCode,
                String reasonText, String result, String note) {
            this.eventId = eventId;
            this.instructionRef = instructionRef;
            this.providerReference = providerReference;
            this.outcome = outcome;
            this.reasonCode = reasonCode;
            this.reasonText = reasonText;
            this.result = result;
            this.note = note;
            this.receivedAt = LocalDateTime.now();
        }

        public String getEventId() {
            return eventId;
        }

        public String getInstructionRef() {
            return instructionRef;
        }

        public String getProviderReference() {
            return providerReference;
        }

        public String getOutcome() {
            return outcome;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getReasonText() {
            return reasonText;
        }

        public String getResult() {
            return result;
        }

        public String getNote() {
            return note;
        }

        public LocalDateTime getReceivedAt() {
            return receivedAt;
        }
    }

    /**
     * The simulated bank's record of a payment it accepted (SIMULATED — never a real provider). It stands in for the
     * bank's side so a status enquiry has something to answer from when a callback was lost.
     */
    @Entity(name = "SimulatedBankPayment")
    @Table(name = "pay_sim_bank")
    public static class BankPayment {

        public static final String ACCEPTED = "ACCEPTED";
        public static final String SETTLED = "SETTLED";
        public static final String REJECTED = "REJECTED";
        public static final String RETURNED = "RETURNED";

        @Id
        @Column(name = "provider_reference", length = 60)
        private String providerReference;

        @Column(name = "instruction_ref", nullable = false, length = 20)
        private String instructionRef;

        @Column(nullable = false, precision = 15, scale = 2)
        private BigDecimal amount;

        @Column(name = "account_ref", nullable = false, length = 60)
        private String accountRef;

        @Column(nullable = false, length = 20)
        private String status;

        @Column(name = "reason_code", length = 20)
        private String reasonCode;

        @Column(name = "reason_text", length = 300)
        private String reasonText;

        @Column(name = "last_event_id", length = 80)
        private String lastEventId;

        @Column(name = "updated_at", nullable = false)
        private LocalDateTime updatedAt;

        protected BankPayment() {
        }

        BankPayment(String providerReference, String instructionRef, BigDecimal amount, String accountRef) {
            this.providerReference = providerReference;
            this.instructionRef = instructionRef;
            this.amount = amount;
            this.accountRef = accountRef;
            this.status = ACCEPTED;
            this.updatedAt = LocalDateTime.now();
        }

        void decide(String status, String reasonCode, String reasonText, String eventId) {
            this.status = status;
            this.reasonCode = reasonCode;
            this.reasonText = reasonText;
            this.lastEventId = eventId;
            this.updatedAt = LocalDateTime.now();
        }

        public String getProviderReference() {
            return providerReference;
        }

        public String getInstructionRef() {
            return instructionRef;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getAccountRef() {
            return accountRef;
        }

        public String getStatus() {
            return status;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getReasonText() {
            return reasonText;
        }

        public String getLastEventId() {
            return lastEventId;
        }
    }
}
