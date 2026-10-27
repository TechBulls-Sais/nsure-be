package com.nsure.controls;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "operational_control")
public class OperationalControl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_key", nullable = false, unique = true, length = 60)
    private String controlKey;

    @Column(nullable = false, length = 140)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(name = "control_type", nullable = false, length = 20)
    private String controlType;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "numeric_value", precision = 15, scale = 2)
    private BigDecimal numericValue;

    @Column(name = "fail_mode", nullable = false, length = 10)
    private String failMode;

    @Column(name = "requires_reason", nullable = false)
    private boolean requiresReason;

    @Column(name = "last_changed_by")
    private Long lastChangedBy;

    @Column(name = "last_changed_at")
    private LocalDateTime lastChangedAt;

    @Column(name = "last_change_reason", length = 1000)
    private String lastChangeReason;

    public String getControlKey() {
        return controlKey;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getControlType() {
        return controlType;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public BigDecimal getNumericValue() {
        return numericValue;
    }

    public String getFailMode() {
        return failMode;
    }

    public boolean isRequiresReason() {
        return requiresReason;
    }

    public Long getLastChangedBy() {
        return lastChangedBy;
    }

    public LocalDateTime getLastChangedAt() {
        return lastChangedAt;
    }

    public String getLastChangeReason() {
        return lastChangeReason;
    }
}
