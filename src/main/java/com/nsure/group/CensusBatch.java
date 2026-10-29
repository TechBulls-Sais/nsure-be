package com.nsure.group;

import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * A census file staged for a scheme (GL04): Validated rows wait in staging without touching live membership until
 * an explicit commit. Unique per scheme and external batch id, so a resubmitted file is the same batch (NS-GL04-003).
 */
@Entity
@Table(name = "grp_census_batch")
public class CensusBatch {

    public static final String VALIDATED = "VALIDATED";
    public static final String COMMITTED = "COMMITTED";
    public static final String PARTIAL = "PARTIAL";
    public static final String ATOMIC = "ATOMIC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheme_id", nullable = false)
    private GroupScheme scheme;

    @Column(name = "external_batch_id", nullable = false, length = 80)
    private String externalBatchId;

    @Column(name = "batch_mode", nullable = false, length = 10)
    private String mode;

    @Column(name = "file_hash", nullable = false, length = 64)
    private String fileHash;

    @Column(name = "batch_status", nullable = false, length = 20)
    private String status;

    @Column(name = "total_rows", nullable = false)
    private int totalRows;

    @Column(name = "valid_rows", nullable = false)
    private int validRows;

    @Column(name = "invalid_rows", nullable = false)
    private int invalidRows;

    @Column(name = "accepted_rows", nullable = false)
    private int acceptedRows;

    @Column(name = "rejected_rows", nullable = false)
    private int rejectedRows;

    @Column(name = "submitted_by", nullable = false, length = 100)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "committed_by", length = 100)
    private String committedBy;

    @Column(name = "committed_at")
    private LocalDateTime committedAt;

    protected CensusBatch() {
    }

    CensusBatch(String ref, GroupScheme scheme, String externalBatchId, String mode, String fileHash,
            String submittedBy) {
        this.ref = ref;
        this.scheme = scheme;
        this.externalBatchId = externalBatchId;
        this.mode = mode;
        this.fileHash = fileHash;
        this.status = VALIDATED;
        this.submittedBy = submittedBy;
        this.submittedAt = LocalDateTime.now();
    }

    void validated(int total, int valid) {
        this.totalRows = total;
        this.validRows = valid;
        this.invalidRows = total - valid;
    }

    void committed(int accepted, int rejected, String actor) {
        this.acceptedRows = accepted;
        this.rejectedRows = rejected;
        this.status = COMMITTED;
        this.committedBy = actor;
        this.committedAt = LocalDateTime.now();
    }

    boolean isCommitted() {
        return COMMITTED.equals(status);
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public GroupScheme getScheme() {
        return scheme;
    }

    public String getExternalBatchId() {
        return externalBatchId;
    }

    public String getMode() {
        return mode;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getStatus() {
        return status;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getValidRows() {
        return validRows;
    }

    public int getInvalidRows() {
        return invalidRows;
    }

    public int getAcceptedRows() {
        return acceptedRows;
    }

    public int getRejectedRows() {
        return rejectedRows;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public String getCommittedBy() {
        return committedBy;
    }

    public LocalDateTime getCommittedAt() {
        return committedAt;
    }
}
