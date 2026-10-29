package com.nsure.communication;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A customer's explicit choice for one channel (NS-SH09B-002). Without a row, Email defaults to opted in at the
 * customer's registered address and WhatsApp to not opted in.
 */
@Entity
@Table(name = "com_preference")
public class ChannelPreference {

    public static final String OPTED_IN = "OPTED_IN";
    public static final String OPTED_OUT = "OPTED_OUT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_ref", nullable = false, length = 20)
    private String customerRef;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 150)
    private String contact;

    /** 'Y' when the contact matches one the customer registered (verified at onboarding), else 'N'. */
    @Column(nullable = false, length = 1)
    private String verified;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ChannelPreference() {
    }

    ChannelPreference(String customerRef, String channel) {
        this.customerRef = customerRef;
        this.channel = channel;
    }

    void set(String status, String contact, boolean verified, String actor) {
        this.status = status;
        this.contact = contact;
        this.verified = verified ? "Y" : "N";
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getChannel() {
        return channel;
    }

    public String getStatus() {
        return status;
    }

    public String getContact() {
        return contact;
    }

    public String getVerified() {
        return verified;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
