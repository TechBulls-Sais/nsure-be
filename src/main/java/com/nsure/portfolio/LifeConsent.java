package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One insured life's own consent decision (NS-IL04-006); the newest row for a life and scope applies. */
@Entity(name = "LifeConsent")
@Table(name = "pol_life_consent")
public class LifeConsent {

    public static final String MEDICAL_TO_POLICYHOLDER = "MEDICAL_TO_POLICYHOLDER";
    public static final String GRANTED = "GRANTED";
    public static final String WITHDRAWN = "WITHDRAWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "life_id", nullable = false)
    private Long lifeId;

    @Column(nullable = false, length = 40)
    private String scope;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 300)
    private String evidence;

    @Column(name = "recorded_by", nullable = false, length = 100)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    protected LifeConsent() {
    }

    LifeConsent(String policyRef, Long lifeId, String scope, String status, String evidence, String recordedBy) {
        this.policyRef = policyRef;
        this.lifeId = lifeId;
        this.scope = scope;
        this.status = status;
        this.evidence = evidence;
        this.recordedBy = recordedBy;
        this.recordedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public Long getLifeId() {
        return lifeId;
    }

    public String getScope() {
        return scope;
    }

    public String getStatus() {
        return status;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public LocalDateTime getRecordedAt() {
        return recordedAt;
    }
}
