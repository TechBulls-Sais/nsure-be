package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * An immutable snapshot of a product configuration as it was saved (NS-SH02-003, NS-DAT-004). The configuration row
 * shows the latest state; every earlier state stays here with who saved it, when, and its status at the time.
 */
@Entity
@Table(name = "ins_product_config_version")
public class ProductConfigVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_ref", nullable = false, length = 20, updatable = false)
    private String configRef;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int versionNo;

    @Column(nullable = false, length = 4000, updatable = false)
    private String snapshot;

    @Column(name = "content_hash", nullable = false, length = 64, updatable = false)
    private String contentHash;

    @Column(nullable = false, length = 40, updatable = false)
    private String status;

    @Column(name = "saved_by", nullable = false, length = 100, updatable = false)
    private String savedBy;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private LocalDateTime savedAt;

    protected ProductConfigVersion() {
    }

    ProductConfigVersion(String configRef, int versionNo, String snapshot, String contentHash, String status,
            String savedBy) {
        this.configRef = configRef;
        this.versionNo = versionNo;
        this.snapshot = snapshot;
        this.contentHash = contentHash;
        this.status = status;
        this.savedBy = savedBy;
        this.savedAt = LocalDateTime.now().withNano(0);
    }

    public String getConfigRef() {
        return configRef;
    }

    public int getVersionNo() {
        return versionNo;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public String getContentHash() {
        return contentHash;
    }

    public String getStatus() {
        return status;
    }

    public String getSavedBy() {
        return savedBy;
    }

    public LocalDateTime getSavedAt() {
        return savedAt;
    }
}
