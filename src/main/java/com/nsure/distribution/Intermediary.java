package com.nsure.distribution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** An agent or broker appointed to sell (SH06). Only an Active, unexpired appointment can take new business. */
@Entity
@Table(name = "dist_intermediary")
public class Intermediary {

    public static final String AGENT = "AGENT";
    public static final String BROKER = "BROKER";
    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";
    public static final String LAPSED = "LAPSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "intermediary_type", nullable = false, length = 20)
    private String type;

    @Column(name = "licence_number", nullable = false, unique = true, length = 60)
    private String licenceNumber;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "appointment_expiry", nullable = false)
    private LocalDate appointmentExpiry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Intermediary parent;

    @Column(length = 150)
    private String email;

    @Column(length = 40)
    private String mobile;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Intermediary() {
    }

    Intermediary(String name, String type, String licenceNumber, LocalDate appointmentExpiry, Intermediary parent,
            String email, String mobile, String actor) {
        this.ref = "TMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.name = name;
        this.type = type;
        this.licenceNumber = licenceNumber;
        this.status = ACTIVE;
        this.appointmentExpiry = appointmentExpiry;
        this.parent = parent;
        this.email = email;
        this.mobile = mobile;
        this.createdBy = actor;
        this.createdAt = LocalDateTime.now();
        this.updatedBy = actor;
        this.updatedAt = createdAt;
    }

    void numbered() {
        this.ref = String.format("INT-%05d", id);
    }

    /** Active and not past its appointment expiry on {@code day}: may take new assignments. */
    boolean canTakeBusiness(LocalDate day) {
        return ACTIVE.equals(status) && !appointmentExpiry.isBefore(day);
    }

    void changeStatus(String status, String reason, String actor) {
        this.status = status;
        this.statusReason = reason;
        touch(actor);
    }

    void update(String name, String licenceNumber, LocalDate appointmentExpiry, String email, String mobile,
            String actor) {
        this.name = name;
        this.licenceNumber = licenceNumber;
        this.appointmentExpiry = appointmentExpiry;
        this.email = email;
        this.mobile = mobile;
        touch(actor);
    }

    private void touch(String actor) {
        this.updatedBy = actor;
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getLicenceNumber() {
        return licenceNumber;
    }

    public String getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public LocalDate getAppointmentExpiry() {
        return appointmentExpiry;
    }

    public Intermediary getParent() {
        return parent;
    }

    public String getEmail() {
        return email;
    }

    public String getMobile() {
        return mobile;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
