package com.nsure.security.otp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One issued one-time code: a challenge the caller answers once, before it expires and within its attempt limit.
 * Only hashes are kept. The code's hash is salted with the challenge token, which is itself stored only as a hash,
 * so a copy of this table cannot be used to work out a live code.
 */
@Entity
@Table(name = "one_time_code")
public class OneTimeCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String purpose;

    @Column(name = "challenge_hash", nullable = false, length = 64, unique = true)
    private String challengeHash;

    /** Who or what the code proves: the username signing in, or the phone a sign-up code was sent to. */
    @Column(nullable = false, length = 100)
    private String subject;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    protected OneTimeCode() {
    }

    OneTimeCode(String purpose, String challengeHash, String subject, String codeHash, LocalDateTime now,
            LocalDateTime expiresAt, int maxAttempts) {
        this.purpose = purpose;
        this.challengeHash = challengeHash;
        this.subject = subject;
        this.codeHash = codeHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
        this.maxAttempts = maxAttempts;
    }

    boolean usable(LocalDateTime now) {
        return usedAt == null && now.isBefore(expiresAt) && attempts < maxAttempts;
    }

    /** Counts a wrong answer; the last allowed wrong answer burns the code. */
    void failedAttempt(LocalDateTime now) {
        attempts++;
        if (attempts >= maxAttempts) {
            usedAt = now;
        }
    }

    void use(LocalDateTime now) {
        if (usedAt == null) {
            usedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public String getPurpose() {
        return purpose;
    }

    public String getSubject() {
        return subject;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public LocalDateTime getUsedAt() {
        return usedAt;
    }
}
