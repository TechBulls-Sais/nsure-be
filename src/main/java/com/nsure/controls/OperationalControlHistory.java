package com.nsure.controls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

/**
 * One change to an operational control. Append-only (spec 12 C-5): the entity is {@link Immutable}, has no setters
 * and no repository method updates or deletes it. A control change is a governance event.
 */
@Entity
@Immutable
@Table(name = "operational_control_history")
public class OperationalControlHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_key", nullable = false, length = 60, updatable = false)
    private String controlKey;

    @Column(name = "previous_enabled", updatable = false)
    private Boolean previousEnabled;

    @Column(name = "new_enabled", updatable = false)
    private Boolean newEnabled;

    @Column(name = "previous_value", precision = 15, scale = 2, updatable = false)
    private BigDecimal previousValue;

    @Column(name = "new_value", precision = 15, scale = 2, updatable = false)
    private BigDecimal newValue;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @Column(nullable = false, length = 1000, updatable = false)
    private String reason;

    protected OperationalControlHistory() {
    }

    OperationalControlHistory(String controlKey, Boolean previousEnabled, Boolean newEnabled,
            BigDecimal previousValue, BigDecimal newValue, Long changedBy, String reason) {
        this.controlKey = controlKey;
        this.previousEnabled = previousEnabled;
        this.newEnabled = newEnabled;
        this.previousValue = previousValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.changedAt = LocalDateTime.now();
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public String getControlKey() {
        return controlKey;
    }

    public Boolean getPreviousEnabled() {
        return previousEnabled;
    }

    public Boolean getNewEnabled() {
        return newEnabled;
    }

    public BigDecimal getPreviousValue() {
        return previousValue;
    }

    public BigDecimal getNewValue() {
        return newValue;
    }

    public Long getChangedBy() {
        return changedBy;
    }

    public LocalDateTime getChangedAt() {
        return changedAt;
    }

    public String getReason() {
        return reason;
    }
}
