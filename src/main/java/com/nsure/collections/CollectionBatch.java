package com.nsure.collections;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One collection run sent to a rail (NS-SH04-002): prepared by one person, approved by another, then submitted.
 * Prepared → Approved → Submitted → Completed (every instruction answered), or Cancelled before submission.
 */
@Entity
@Table(name = "col_batch")
public class CollectionBatch {

    public static final String PREPARED = "PREPARED";
    public static final String APPROVED = "APPROVED";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "collection_date", nullable = false)
    private LocalDate collectionDate;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "instruction_count", nullable = false)
    private int instructionCount;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "file_ref", length = 60)
    private String fileRef;

    @Column(name = "prepared_by", nullable = false, length = 100)
    private String preparedBy;

    @Column(name = "prepared_at", nullable = false)
    private LocalDateTime preparedAt;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "submitted_by", length = 100)
    private String submittedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(length = 500)
    private String note;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected CollectionBatch() {
    }

    CollectionBatch(String ref, LocalDate collectionDate, String provider, String actor) {
        this.ref = ref;
        this.collectionDate = collectionDate;
        this.provider = provider;
        this.status = PREPARED;
        this.instructionCount = 0;
        this.totalAmount = BigDecimal.ZERO.setScale(2);
        this.preparedBy = actor;
        this.preparedAt = LocalDateTime.now();
    }

    void totals(int count, BigDecimal total) {
        this.instructionCount = count;
        this.totalAmount = total;
    }

    void approve(String actor) {
        this.status = APPROVED;
        this.approvedBy = actor;
        this.approvedAt = LocalDateTime.now();
    }

    void submitted(String fileRef, String actor) {
        this.status = SUBMITTED;
        this.fileRef = fileRef;
        this.submittedBy = actor;
        this.submittedAt = LocalDateTime.now();
    }

    void completed() {
        this.status = COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    void cancel(String reason) {
        this.status = CANCELLED;
        this.note = reason;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public LocalDate getCollectionDate() {
        return collectionDate;
    }

    public String getProvider() {
        return provider;
    }

    public String getStatus() {
        return status;
    }

    public int getInstructionCount() {
        return instructionCount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getFileRef() {
        return fileRef;
    }

    public String getPreparedBy() {
        return preparedBy;
    }

    public LocalDateTime getPreparedAt() {
        return preparedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public LocalDateTime getApprovedAt() {
        return approvedAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public String getNote() {
        return note;
    }

    public long getRowVersion() {
        return rowVersion;
    }
}
