package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * An uploaded file held in quarantine (NS-SH09A-004, AT-SH09A-02). It is not a document of record: it is never served
 * by the normal download endpoints and never counts towards an evidence checklist. It leaves quarantine only by being
 * RELEASED (a scanner verdict of clean, or a person with RELEASE_DOCUMENT who is not the uploader), which files it as a
 * claim document, or REJECTED with a reason the uploader sees. The bytes stay where they were written; only the state
 * decides who may open them.
 */
@Entity
@Table(name = "doc_quarantine")
public class QuarantinedUpload {

    public static final String PENDING = "PENDING";
    public static final String RELEASED = "RELEASED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // CLAIM: the only upload channel today.
    @Column(name = "owner_kind", nullable = false, length = 20)
    private String ownerKind;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(name = "owner_ref", nullable = false, length = 40)
    private String ownerRef;

    @Column(name = "document_type", nullable = false, length = 60)
    private String documentType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    // STAFF or PORTAL (DocumentCustody channels).
    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    // UploadScanner.CLEAN, INFECTED or NOT_SCANNED; never CLEAN unless a scanner said so.
    @Column(name = "scan_result", nullable = false, length = 20)
    private String scanResult;

    @Column(name = "scan_detail", length = 300)
    private String scanDetail;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    // The claim document filed on release.
    @Column(name = "document_id")
    private Long documentId;

    protected QuarantinedUpload() {
    }

    QuarantinedUpload(String ownerKind, Long ownerId, String ownerRef, String documentType, String fileName,
            String contentType, long sizeBytes, String storagePath, String sha256, String uploadedBy, String channel,
            UploadScanner.Verdict verdict) {
        this.ownerKind = ownerKind;
        this.ownerId = ownerId;
        this.ownerRef = ownerRef;
        this.documentType = documentType;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storagePath = storagePath;
        this.sha256 = sha256;
        this.uploadedBy = uploadedBy;
        this.channel = channel;
        this.uploadedAt = LocalDateTime.now();
        this.status = PENDING;
        this.scanResult = verdict.result();
        this.scanDetail = cut(verdict.detail(), 300);
    }

    void released(String by, String reason, Long documentId) {
        decide(RELEASED, by, reason);
        this.documentId = documentId;
    }

    void rejected(String by, String reason) {
        decide(REJECTED, by, reason);
    }

    private void decide(String outcome, String by, String reason) {
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = LocalDateTime.now();
        this.decisionReason = cut(reason, 500);
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getOwnerKind() {
        return ownerKind;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getOwnerRef() {
        return ownerRef;
    }

    public String getDocumentType() {
        return documentType;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public String getSha256() {
        return sha256;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public String getChannel() {
        return channel;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getScanResult() {
        return scanResult;
    }

    public String getScanDetail() {
        return scanDetail;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public LocalDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public Long getDocumentId() {
        return documentId;
    }
}
