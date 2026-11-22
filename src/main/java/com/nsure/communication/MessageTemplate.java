package com.nsure.communication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One version of a message template (NS-SH09B-003). Drafted by one user, approved by another; approving a version
 * retires the previously approved version of the same code. Approved content is never edited: a change is a new
 * version. The body holds {@code {{field}}} merge fields filled from the event payload.
 *
 * <p>An approved version can also be withdrawn without a replacement (D-22): one user asks with a reason, another
 * holder of APPROVE_TEMPLATE confirms, and it becomes WITHDRAWN, so no new message uses it. Until then it is still
 * used, and the request can be declined.
 */
@Entity
@Table(name = "com_template")
public class MessageTemplate {

    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";
    public static final String RETIRED = "RETIRED";
    public static final String WITHDRAWN = "WITHDRAWN";
    public static final String PROVIDER_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String PROVIDER_NOT_SUBMITTED = "NOT_SUBMITTED";
    public static final String PROVIDER_SUBMITTED = "SUBMITTED";
    public static final String PROVIDER_APPROVED = "APPROVED";
    public static final String PROVIDER_REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 20)
    private String audience;

    @Column(nullable = false)
    private int version;

    @Column(length = 200)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String body;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "withdrawal_reason", length = 500)
    private String withdrawalReason;

    @Column(name = "withdrawal_requested_by", length = 100)
    private String withdrawalRequestedBy;

    @Column(name = "withdrawal_requested_at")
    private LocalDateTime withdrawalRequestedAt;

    @Column(name = "withdrawn_by", length = 100)
    private String withdrawnBy;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    // BP-COMMS: the WhatsApp provider's own approval of the template (NOT_REQUIRED for Email and SMS).
    @Column(name = "provider_status", nullable = false, length = 20)
    private String providerStatus;

    @Column(name = "provider_note", length = 300)
    private String providerNote;

    protected MessageTemplate() {
    }

    MessageTemplate(String code, String eventType, String channel, String audience, int version, String subject,
            String body, String createdBy) {
        this.code = code;
        this.eventType = eventType;
        this.channel = channel;
        this.audience = audience;
        this.version = version;
        this.subject = subject;
        this.body = body;
        this.status = DRAFT;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
        this.providerStatus = "WHATSAPP".equals(channel) ? PROVIDER_NOT_SUBMITTED : PROVIDER_NOT_REQUIRED;
    }

    /** Sent to the WhatsApp provider for its review (BP-COMMS). */
    void submitToProvider(String by) {
        providerStatus = PROVIDER_SUBMITTED;
        providerNote = "Submitted to the WhatsApp provider by " + by + " on " + LocalDateTime.now().toLocalDate();
    }

    /** The provider's answer to a submission. */
    void providerDecision(boolean approved, String note) {
        providerStatus = approved ? PROVIDER_APPROVED : PROVIDER_REJECTED;
        providerNote = note == null || note.length() <= 300 ? note : note.substring(0, 300);
    }

    /** Whether the provider allows this template to be sent (always, for channels that need no provider approval). */
    boolean providerAllows() {
        return PROVIDER_NOT_REQUIRED.equals(providerStatus) || PROVIDER_APPROVED.equals(providerStatus);
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public String getProviderNote() {
        return providerNote;
    }

    void approve(String approver) {
        status = APPROVED;
        approvedBy = approver;
        approvedAt = LocalDateTime.now();
    }

    void retire() {
        status = RETIRED;
    }

    /** First step of a withdrawal: the reason and who asked. The version stays in use until someone else confirms. */
    void requestWithdrawal(String requester, String reason) {
        withdrawalRequestedBy = requester;
        withdrawalReason = reason;
        withdrawalRequestedAt = LocalDateTime.now();
    }

    /** The request was declined: the version stays approved, and the declined request is cleared. */
    void declineWithdrawal() {
        withdrawalRequestedBy = null;
        withdrawalReason = null;
        withdrawalRequestedAt = null;
    }

    /** Second step: another person confirms, and no new message uses this version. */
    void withdraw(String approver) {
        status = WITHDRAWN;
        withdrawnBy = approver;
        withdrawnAt = LocalDateTime.now();
    }

    public boolean isWithdrawalPending() {
        return APPROVED.equals(status) && withdrawalRequestedBy != null;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getEventType() {
        return eventType;
    }

    public String getChannel() {
        return channel;
    }

    public String getAudience() {
        return audience;
    }

    public int getVersion() {
        return version;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public String getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getWithdrawalReason() {
        return withdrawalReason;
    }

    public String getWithdrawalRequestedBy() {
        return withdrawalRequestedBy;
    }

    public LocalDateTime getWithdrawalRequestedAt() {
        return withdrawalRequestedAt;
    }

    public String getWithdrawnBy() {
        return withdrawnBy;
    }

    public LocalDateTime getWithdrawnAt() {
        return withdrawnAt;
    }
}
