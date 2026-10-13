package com.nsure.mipas.control;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "operational_control_history")
public class OperationalControlHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_key", nullable = false, length = 60)
    private String controlKey;

    @Column(name = "previous_enabled")
    private Boolean previousEnabled;

    @Column(name = "new_enabled")
    private Boolean newEnabled;

    @Column(name = "previous_value", precision = 15, scale = 2)
    private BigDecimal previousValue;

    @Column(name = "new_value", precision = 15, scale = 2)
    private BigDecimal newValue;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(nullable = false, length = 500)
    private String reason;

    protected OperationalControlHistory() {
    }

    public OperationalControlHistory(OperationalControl before, boolean newEnabled, BigDecimal newValue,
                                     Long changedBy, LocalDateTime changedAt, String reason) {
        this.controlKey = before.getControlKey();
        this.previousEnabled = before.isEnabled();
        this.newEnabled = newEnabled;
        this.previousValue = before.getNumericValue();
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.changedAt = changedAt;
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
