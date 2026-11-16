package com.nsure.security.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** One signed-in browser session: only the token's hash is kept, so a database copy cannot be replayed. */
@Entity
@Table(name = "auth_session")
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Fingerprint of the password hash at sign-in: a password change ends every session made before it. */
    @Column(name = "credential_stamp", nullable = false, length = 64)
    private String credentialStamp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected AuthSession() {
    }

    AuthSession(String tokenHash, Long userId, String credentialStamp, LocalDateTime now, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.userId = userId;
        this.credentialStamp = credentialStamp;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
    }

    void touch(LocalDateTime now) {
        this.lastSeenAt = now;
    }

    void revoke(LocalDateTime now) {
        if (revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCredentialStamp() {
        return credentialStamp;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }
}
