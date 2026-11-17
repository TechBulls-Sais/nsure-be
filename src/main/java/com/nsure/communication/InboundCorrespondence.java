package com.nsure.communication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A reply or message received from a customer by Email or WhatsApp (Solution Blueprint §5). A sender matched to
 * exactly one customer by a verified contact is LINKED to a service case; an ambiguous or unknown sender waits in
 * TRIAGE as an identity-verification task (AT-SH09B-03): someone takes it, verifies who sent it by a recorded method,
 * and only then links it to a customer. Nothing about a policy is disclosed to an unverified sender.
 */
@Entity
@Table(name = "com_inbound")
public class InboundCorrespondence {

    public static final String LINKED = "LINKED";
    public static final String TRIAGE = "TRIAGE";

    /** Verification states: REQUIRED (a task), VERIFIED (by a person, with the method), MATCHED (verified contact). */
    public static final String REQUIRED = "REQUIRED";
    public static final String VERIFIED = "VERIFIED";
    public static final String MATCHED = "MATCHED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_message_id", nullable = false, unique = true, length = 80)
    private String providerMessageId;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 200)
    private String sender;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "customer_ref", length = 40)
    private String customerRef;

    @Column(name = "case_ref", length = 40)
    private String caseRef;

    @Column(length = 500)
    private String note;

    @Column(name = "handled_by", length = 100)
    private String handledBy;

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    @Column(name = "verification_status", length = 20)
    private String verificationStatus;

    @Column(name = "verification_method", length = 100)
    private String verificationMethod;

    @Column(name = "verification_note", length = 500)
    private String verificationNote;

    // The person working the verification task, and when it is due.
    @Column(name = "task_owner", length = 100)
    private String taskOwner;

    @Column(name = "task_due_at")
    private LocalDateTime taskDueAt;

    protected InboundCorrespondence() {
    }

    InboundCorrespondence(String providerMessageId, String channel, String sender, String body,
            LocalDateTime receivedAt) {
        this.providerMessageId = providerMessageId;
        this.channel = channel;
        this.sender = sender;
        this.body = body.length() <= 2000 ? body : body.substring(0, 2000);
        this.receivedAt = receivedAt;
        this.status = TRIAGE;
    }

    /** Linked because the sender is a verified contact of exactly one customer. */
    void matched(String customerRef, String caseRef, String actor) {
        linked(customerRef, caseRef, "Matched by a verified contact", actor);
        this.verificationStatus = MATCHED;
    }

    /** Linked by a person who verified the sender by {@code method}. */
    void verified(String customerRef, String caseRef, String method, String note, String actor) {
        linked(customerRef, caseRef, "Sender verified: " + method, actor);
        this.verificationStatus = VERIFIED;
        this.verificationMethod = method;
        this.verificationNote = note;
    }

    void own(String owner) {
        this.taskOwner = owner;
    }

    private void linked(String customerRef, String caseRef, String note, String actor) {
        this.status = LINKED;
        this.customerRef = customerRef;
        this.caseRef = caseRef;
        this.note = note;
        this.handledBy = actor;
        this.handledAt = LocalDateTime.now();
    }

    /** Waits as an identity-verification task, due a day after it arrived. */
    void triage(String note) {
        this.status = TRIAGE;
        this.note = note;
        this.verificationStatus = REQUIRED;
        this.taskDueAt = receivedAt.plusHours(24);
    }

    public Long getId() {
        return id;
    }

    public String getProviderMessageId() {
        return providerMessageId;
    }

    public String getChannel() {
        return channel;
    }

    public String getSender() {
        return sender;
    }

    public String getBody() {
        return body;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public String getNote() {
        return note;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public String getVerificationStatus() {
        return verificationStatus;
    }

    public String getVerificationMethod() {
        return verificationMethod;
    }

    public String getVerificationNote() {
        return verificationNote;
    }

    public String getTaskOwner() {
        return taskOwner;
    }

    public LocalDateTime getTaskDueAt() {
        return taskDueAt;
    }
}
