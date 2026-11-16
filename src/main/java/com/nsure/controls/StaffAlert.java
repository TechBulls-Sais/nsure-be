package com.nsure.controls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

/**
 * A notice for staff, shown to every user holding {@code requiredPermission}: a control switched off (spec 12 C-11),
 * or straight-through processing that has silently stopped (C-18). One row per dedup key.
 */
@Entity
@Immutable
@Table(name = "staff_alert")
public class StaffAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_type", nullable = false, length = 40)
    private String alertType;

    @Column(name = "required_permission", nullable = false, length = 100)
    private String requiredPermission;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String body;

    @Column(name = "dedup_key", nullable = false, unique = true, length = 120)
    private String dedupKey;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected StaffAlert() {
    }

    StaffAlert(String alertType, String requiredPermission, String title, String body, String dedupKey,
            String createdBy) {
        this.alertType = alertType;
        this.requiredPermission = requiredPermission;
        this.title = title;
        this.body = body;
        this.dedupKey = dedupKey;
        this.createdBy = createdBy;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getAlertType() {
        return alertType;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
