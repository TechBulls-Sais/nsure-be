package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

/**
 * Spec 01 BR-5 / 04 A-15: the OTP-verified declaration for one purchase, stamped with when and the four consent
 * flags. Evidence, not a status flag: one row per verified declaration, read-only to everyone (no update or delete
 * path exists; the entity is {@link Immutable}, and on MySQL a trigger refuses updates and deletes).
 */
@Entity
@Immutable
@Table(name = "declaration_consent_log")
public class DeclarationConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_ref", nullable = false, length = 20, updatable = false)
    private String customerRef;

    @Column(name = "policy_ref", nullable = false, length = 20, updatable = false)
    private String policyRef;

    @Column(name = "accuracy_confirmed", nullable = false, updatable = false)
    private boolean accuracyConfirmed;

    @Column(name = "terms_accepted", nullable = false, updatable = false)
    private boolean termsAccepted;

    @Column(name = "data_protection_accepted", nullable = false, updatable = false)
    private boolean dataProtectionAccepted;

    @Column(name = "false_information_acknowledged", nullable = false, updatable = false)
    private boolean falseInformationAcknowledged;

    @Column(name = "otp_verified_at", nullable = false, updatable = false)
    private LocalDateTime otpVerifiedAt;

    @Column(name = "recorded_by", nullable = false, length = 100, updatable = false)
    private String recordedBy;

    protected DeclarationConsent() {
    }

    DeclarationConsent(String customerRef, String policyRef, String recordedBy) {
        this.customerRef = customerRef;
        this.policyRef = policyRef;
        this.accuracyConfirmed = true;
        this.termsAccepted = true;
        this.dataProtectionAccepted = true;
        this.falseInformationAcknowledged = true;
        this.otpVerifiedAt = LocalDateTime.now();
        this.recordedBy = recordedBy;
    }

    public Long getId() {
        return id;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getPolicyRef() {
        return policyRef;
    }

    public boolean isAccuracyConfirmed() {
        return accuracyConfirmed;
    }

    public boolean isTermsAccepted() {
        return termsAccepted;
    }

    public boolean isDataProtectionAccepted() {
        return dataProtectionAccepted;
    }

    public boolean isFalseInformationAcknowledged() {
        return falseInformationAcknowledged;
    }

    public LocalDateTime getOtpVerifiedAt() {
        return otpVerifiedAt;
    }

    public String getRecordedBy() {
        return recordedBy;
    }
}
