package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** The contract's terms frozen once when cover starts (NS-IL03-001); never updated afterwards. */
@Entity(name = "InceptionSnapshot")
@Table(name = "pol_inception_snapshot")
public class InceptionSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_ref", nullable = false, length = 20, updatable = false)
    private String policyRef;

    @Column(nullable = false, length = 4000, updatable = false)
    private String snapshot;

    @Column(nullable = false, length = 64, updatable = false)
    private String sha256;

    @Column(name = "taken_at", nullable = false, updatable = false)
    private LocalDateTime takenAt;

    protected InceptionSnapshot() {
    }

    InceptionSnapshot(String policyRef, String snapshot, String sha256) {
        this.policyRef = policyRef;
        this.snapshot = snapshot;
        this.sha256 = sha256;
        this.takenAt = LocalDateTime.now();
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public String getSnapshot() {
        return snapshot;
    }

    public String getSha256() {
        return sha256;
    }

    public LocalDateTime getTakenAt() {
        return takenAt;
    }
}
