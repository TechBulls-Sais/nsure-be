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
 * exactly one customer is LINKED to a service case; an ambiguous or unknown sender waits in TRIAGE for a person.
 */
@Entity
@Table(name = "com_inbound")
public class InboundCorrespondence {

    public static final String LINKED = "LINKED";
    public static final String TRIAGE = "TRIAGE";

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

    void linked(String customerRef, String caseRef, String note, String actor) {
        this.status = LINKED;
        this.customerRef = customerRef;
        this.caseRef = caseRef;
        this.note = note;
        this.handledBy = actor;
        this.handledAt = LocalDateTime.now();
    }

    void triage(String note) {
        this.status = TRIAGE;
        this.note = note;
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
}
