package com.nsure.portfolio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** A one-time code sent for a declaration (spec 06 §3): stored only as a SHA-256 hash, valid for 600 seconds. */
@Entity
@Table(name = "declaration_otp")
public class DeclarationOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_ref", nullable = false, length = 20)
    private String customerRef;

    @Column(name = "policy_ref", nullable = false, length = 20)
    private String policyRef;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    protected DeclarationOtp() {
    }

    DeclarationOtp(String customerRef, String policyRef, String codeHash, LocalDateTime expiresAt) {
        this.customerRef = customerRef;
        this.policyRef = policyRef;
        this.codeHash = codeHash;
        this.createdAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
    }

    boolean usable() {
        return usedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }

    void use() {
        this.usedAt = LocalDateTime.now();
    }

    String getCodeHash() {
        return codeHash;
    }

    LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
