package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One document filed on a policy, such as a generated schedule. The bytes live on disk at {@code storagePath}. */
@Entity
@Table(name = "ins_policy_document")
public class PolicyDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "document_type", nullable = false, length = 60)
    private String documentType;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    // Relative to nsure.documents.dir; always a generated name, never the uploader's file name.
    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath;

    @Column(name = "uploaded_by", nullable = false, length = 100)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    // The schedule layout the file was made with (PolicySchedulePdf.LAYOUT_VERSION); 1 is the old layout.
    @Column(name = "layout_version", nullable = false)
    private int layoutVersion;

    // Blueprint §8: the content hash of the file as issued or uploaded, checked on every download.
    @Column(name = "sha256", length = 64)
    private String sha256;

    // CURRENT, or SUPERSEDED by a controlled replacement; an issued document is never overwritten or deleted.
    @Column(name = "status", nullable = false, length = 20)
    private String status = DocumentCustody.CURRENT;

    @Column(name = "superseded_by")
    private Long supersededBy;

    @Column(name = "superseded_reason", length = 500)
    private String supersededReason;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    // NS-SH09A-003: what the schedule was generated from. The approved wording clause versions bound at issue
    // ("WRD-0001 v1, WRD-0002 v1"), the policy's row version, and the frozen data snapshot (JSON, stored beside the
    // PDF) with its hash. A later wording revision changes none of these, nor the issued bytes (AT-SH09A-01).
    @Column(name = "wording_versions", length = 500)
    private String wordingVersions;

    @Column(name = "data_version")
    private Long dataVersion;

    @Column(name = "snapshot_path", length = 255)
    private String snapshotPath;

    @Column(name = "snapshot_sha256", length = 64)
    private String snapshotSha256;

    protected PolicyDocument() {
    }

    public PolicyDocument(Long policyId, String documentType, String fileName, String contentType, long sizeBytes,
            String storagePath, String uploadedBy, int layoutVersion) {
        this.layoutVersion = layoutVersion;
        this.policyId = policyId;
        this.documentType = documentType;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storagePath = storagePath;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getPolicyId() {
        return policyId;
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

    public String getUploadedBy() {
        return uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }

    /** Records what the document was generated from (NS-SH09A-003). */
    void generatedFrom(String wordingVersions, Long dataVersion, String snapshotPath, String snapshotSha256) {
        this.wordingVersions = wordingVersions;
        this.dataVersion = dataVersion;
        this.snapshotPath = snapshotPath;
        this.snapshotSha256 = snapshotSha256;
    }

    public String getWordingVersions() {
        return wordingVersions;
    }

    public Long getDataVersion() {
        return dataVersion;
    }

    public String getSnapshotPath() {
        return snapshotPath;
    }

    public String getSnapshotSha256() {
        return snapshotSha256;
    }

    /** Records the content hash of the stored file. */
    public void seal(String sha256) {
        this.sha256 = sha256;
    }

    /** Marks this document as replaced by {@code replacementId}, keeping the file and the reason. */
    public void supersede(Long replacementId, String reason) {
        this.status = DocumentCustody.SUPERSEDED;
        this.supersededBy = replacementId;
        this.supersededReason = reason;
        this.supersededAt = LocalDateTime.now();
    }

    public void linkReplacement(Long replacementId) {
        this.supersededBy = replacementId;
    }

    public boolean isCurrent() {
        return DocumentCustody.CURRENT.equals(status);
    }

    public String getSha256() {
        return sha256;
    }

    public String getStatus() {
        return status;
    }

    public Long getSupersededBy() {
        return supersededBy;
    }

    public String getSupersededReason() {
        return supersededReason;
    }

    public LocalDateTime getSupersededAt() {
        return supersededAt;
    }
}
