package com.nsure.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Emergency access (Nsure Life NS-SH11-003): one break-glass permission given to one person for a bounded time, with a
 * written reason, by an identity administrator other than themselves. It lapses on its own, can be revoked early, and
 * is reviewed afterwards by a third person. While active it counts as a permission the user holds.
 */
@Entity
@Table(name = "emergency_access_grant")
public class EmergencyAccessGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(name = "permission_code", nullable = false, length = 100)
    private String permissionCode;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "granted_by", nullable = false, length = 100)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private LocalDateTime grantedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_by", length = 100)
    private String revokedBy;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "reviewed_by", length = 100)
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

    protected EmergencyAccessGrant() {
    }

    public EmergencyAccessGrant(AppUser user, String permissionCode, String reason, String grantedBy,
            LocalDateTime expiresAt) {
        this.user = user;
        this.permissionCode = permissionCode;
        this.reason = reason;
        this.grantedBy = grantedBy;
        this.grantedAt = LocalDateTime.now();
        this.expiresAt = expiresAt;
        user.attachEmergencyGrant(this);
    }

    /** In force now: started, not expired and not revoked. */
    public boolean isActive() {
        LocalDateTime now = LocalDateTime.now();
        return revokedAt == null && !now.isBefore(grantedAt) && now.isBefore(expiresAt);
    }

    public String status() {
        if (revokedAt != null) {
            return "REVOKED";
        }
        return isActive() ? "ACTIVE" : "EXPIRED";
    }

    public void revoke(String by) {
        this.revokedBy = by;
        this.revokedAt = LocalDateTime.now();
    }

    public void review(String by, String note) {
        this.reviewedBy = by;
        this.reviewedAt = LocalDateTime.now();
        this.reviewNote = note;
    }

    public Long getId() {
        return id;
    }

    public AppUser getUser() {
        return user;
    }

    public String getPermissionCode() {
        return permissionCode;
    }

    public String getReason() {
        return reason;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewNote() {
        return reviewNote;
    }
}
