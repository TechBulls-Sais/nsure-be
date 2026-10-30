package com.nsure.configuration;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An insurer configuration release (guide §12): DRAFT → IN_REVIEW → APPROVED → ACTIVE → RETIRED, or REJECTED from
 * review. Only a draft is editable; its content checksum is frozen on submission and re-checked at approval and
 * activation, so approved content cannot change underneath its approval.
 */
@Entity
@Table(name = "config_release")
public class ConfigRelease {

    public static final String DRAFT = "DRAFT";
    public static final String IN_REVIEW = "IN_REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String ACTIVE = "ACTIVE";
    public static final String RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "release_code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(name = "release_status", nullable = false, length = 20)
    private String status;

    @Column(length = 64)
    private String checksum;

    @Column(name = "schema_version", length = 80)
    private String schemaVersion;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "activated_by", length = 100)
    private String activatedBy;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @OneToMany(mappedBy = "release", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("product asc")
    private List<PolicyFormVersion> forms = new ArrayList<>();

    protected ConfigRelease() {
    }

    ConfigRelease(String code, String description, String actor) {
        this.code = code;
        this.description = description;
        this.status = DRAFT;
        this.createdBy = actor;
        this.createdAt = LocalDateTime.now();
    }

    void describe(String description) {
        this.description = description;
    }

    void submit(String checksum, String schemaVersion, String actor) {
        this.status = IN_REVIEW;
        this.checksum = checksum;
        this.schemaVersion = schemaVersion;
        this.submittedBy = actor;
        this.submittedAt = LocalDateTime.now();
    }

    void approve(String actor) {
        this.status = APPROVED;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void reject(String reason, String actor) {
        this.status = REJECTED;
        this.rejectionReason = reason;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void activate(String actor) {
        this.status = ACTIVE;
        this.activatedBy = actor;
        this.activatedAt = LocalDateTime.now();
    }

    void retire() {
        this.status = RETIRED;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public String getChecksum() {
        return checksum;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public LocalDateTime getActivatedAt() {
        return activatedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public List<PolicyFormVersion> getForms() {
        return forms;
    }
}
