package com.nsure.controls;

import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.security.domain.AppUser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operational control registry (spec 12 §2). Domain services ask {@link #isEnabled} and {@link #valueOf}; an
 * unknown or unprovisioned control is an error, so a control can never be silently off. Changes go through
 * {@link #change}: permission {@code UPDATE_CONTROL}, a written reason, min/max validation, an append-only history
 * row, and an alert to every holder of {@code READ_CONTROL} when a control is switched off (C-11).
 */
@Service
public class ControlService {

    private final OperationalControlRepository controls;
    private final OperationalControlHistoryRepository history;
    private final StaffAlertService alerts;
    private final Outbox outbox;

    public ControlService(OperationalControlRepository controls, OperationalControlHistoryRepository history,
            StaffAlertService alerts, Outbox outbox) {
        this.controls = controls;
        this.history = history;
        this.alerts = alerts;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public boolean exists(ControlKey key) {
        return controls.findByControlKey(key.key()).isPresent();
    }

    @Transactional(readOnly = true)
    public List<OperationalControl> all() {
        return controls.findAllByOrderByControlKeyAsc();
    }

    @Transactional(readOnly = true)
    public OperationalControl get(String key) {
        ControlKey.require(key);
        return required(key);
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String key) {
        ControlKey.require(key);
        OperationalControl control = required(key);
        return control.isEnabled();
    }

    @Transactional(readOnly = true)
    public BigDecimal valueOf(String key) {
        ControlKey parsed = ControlKey.require(key);
        OperationalControl control = required(key);
        if (parsed.requiresValue() && control.getNumericValue() == null) {
            throw new IllegalStateException("Control " + key + " has no value and fails closed.");
        }
        return control.getNumericValue();
    }

    @Transactional(readOnly = true)
    public List<OperationalControlHistory> history(String key) {
        ControlKey.require(key);
        return history.findByControlKeyOrderByChangedAtDescIdDesc(key);
    }

    /**
     * Enables, disables or re-values a control. {@code enabled} or {@code value} may be null to leave that part as
     * it is. The reason is mandatory: a control change is a governance event (C-5).
     */
    @Transactional
    public OperationalControl change(String key, Boolean enabled, BigDecimal value, String reason, AppUser actor) {
        actor.validateHasPermissionTo("UPDATE_CONTROL");
        ControlKey.require(key);
        OperationalControl control = required(key);
        String why = reason == null ? "" : reason.trim();
        if (control.isRequiresReason() && why.isEmpty()) {
            throw new IllegalArgumentException("Enter a reason for changing " + control.getName()
                    + ". Every control change is kept in its history.");
        }
        if (why.length() > 1000) {
            throw new IllegalArgumentException("Keep the reason to 1000 characters.");
        }
        boolean newEnabled = enabled == null ? control.isEnabled() : enabled;
        BigDecimal newValue = control.getNumericValue();
        if (!control.isThreshold()) {
            if (value != null) {
                throw new IllegalArgumentException(control.getName() + " is an on/off control and takes no value.");
            }
        } else if (value != null) {
            BigDecimal scaled = value.setScale(2, RoundingMode.HALF_UP);
            if (control.getMinValue() != null && scaled.compareTo(control.getMinValue()) < 0
                    || control.getMaxValue() != null && scaled.compareTo(control.getMaxValue()) > 0) {
                throw new IllegalArgumentException(control.getName() + " must be between "
                        + plain(control.getMinValue()) + " and " + plain(control.getMaxValue()) + ".");
            }
            newValue = scaled;
        }
        if (control.isThreshold() && newValue == null) {
            // C-19/C-20: a threshold with no value would fail closed and silently disable what it governs.
            throw new IllegalArgumentException(control.getName() + " needs a value.");
        }
        boolean wasEnabled = control.isEnabled();
        BigDecimal previousValue = control.getNumericValue();
        if (wasEnabled == newEnabled && Objects.equals(
                previousValue == null ? null : previousValue.stripTrailingZeros(),
                newValue == null ? null : newValue.stripTrailingZeros())) {
            throw new IllegalArgumentException("Nothing to change: " + control.getName() + " is already like this.");
        }
        OperationalControlHistory row = history.save(new OperationalControlHistory(
                key, wasEnabled, newEnabled, previousValue, newValue, actor.getId(), why));
        control.change(newEnabled, newValue, actor.getId(), why);
        if (wasEnabled && !newEnabled) {
            alerts.raise(StaffAlertService.CONTROL_DISABLED, "READ_CONTROL",
                    "Control switched off: " + control.getName(),
                    control.getName() + " (" + key + ") was switched off by " + actor.getUsername() + ". Reason: " + why
                            + ". While it is off: " + control.getDescription(),
                    "control-off:" + key + ":" + row.getId(), actor.getUsername());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("controlKey", key);
            payload.put("name", control.getName());
            payload.put("changedBy", actor.getUsername());
            payload.put("reason", why);
            outbox.publish(Events.CONTROL_DISABLED, key, payload, actor.getUsername());
        }
        return control;
    }

    private OperationalControl required(String key) {
        return controls.findByControlKey(key).orElseThrow(() -> new IllegalStateException(
                "Operational control '" + key + "' is not provisioned."));
    }

    private static String plain(BigDecimal value) {
        return value == null ? "any" : value.stripTrailingZeros().toPlainString();
    }
}
