package com.nsure.mipas.control;

import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.web.ResourceNotFoundException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlService {

    private final OperationalControlRepository controls;
    private final OperationalControlHistoryRepository history;
    private final PlatformSecurityContext security;

    public ControlService(OperationalControlRepository controls,
                          OperationalControlHistoryRepository history,
                          PlatformSecurityContext security) {
        this.controls = controls;
        this.history = history;
        this.security = security;
    }

    public boolean exists(ControlKey key) {
        return controls.findByControlKey(key.key()).isPresent();
    }

    public boolean isEnabled(ControlKey key) {
        OperationalControl control = required(key);
        return control.isEnabled();
    }

    public BigDecimal valueOf(ControlKey key) {
        OperationalControl control = required(key);
        if (!control.isThresholdLike() || control.getNumericValue() == null) {
            throw new IllegalStateException(
                    "Control " + key.key() + " has no numeric value. It fails closed.");
        }
        return control.getNumericValue();
    }

    public List<OperationalControl> all() {
        return controls.findAll().stream()
                .sorted((left, right) -> left.getControlKey().compareTo(right.getControlKey()))
                .toList();
    }

    public List<OperationalControl> disabled() {
        return all().stream().filter(control -> !control.isEnabled()).toList();
    }

    public List<OperationalControlHistory> history(String key) {
        ControlKey.fromKey(key);
        return history.findByControlKeyOrderByChangedAtDesc(key);
    }

    @Transactional
    public OperationalControl update(String key, boolean enabled, BigDecimal numericValue, String reason) {
        ControlKey.fromKey(key);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A written reason is required to change a control.");
        }
        OperationalControl control = controls.findByControlKey(key)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown operational control"));
        BigDecimal nextValue = control.isThresholdLike() ? numericValue : control.getNumericValue();
        if (control.isThresholdLike()) {
            if (nextValue == null) {
                throw new IllegalArgumentException("This control needs a numeric value.");
            }
            if (control.getMinValue() != null && nextValue.compareTo(control.getMinValue()) < 0) {
                throw new IllegalArgumentException("Value is below the minimum of " + control.getMinValue() + ".");
            }
            if (control.getMaxValue() != null && nextValue.compareTo(control.getMaxValue()) > 0) {
                throw new IllegalArgumentException("Value is above the maximum of " + control.getMaxValue() + ".");
            }
        }
        Long actorId = security.authenticatedUser().getId();
        LocalDateTime now = LocalDateTime.now();
        history.save(new OperationalControlHistory(control, enabled, nextValue, actorId, now, reason.trim()));
        control.apply(enabled, nextValue, actorId, reason.trim(), now);
        return control;
    }

    private OperationalControl required(ControlKey key) {
        return controls.findByControlKey(key.key()).orElseThrow(() -> new IllegalStateException(
                "Operational control " + key.key() + " is not provisioned. The application fails closed."));
    }
}
