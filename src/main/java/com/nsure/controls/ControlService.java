package com.nsure.controls;

import com.nsure.security.exception.NoAuthorizationException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ControlService {

    private final OperationalControlRepository controls;

    public ControlService(OperationalControlRepository controls) {
        this.controls = controls;
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

    private OperationalControl required(String key) {
        return controls.findByControlKey(key).orElseThrow(() -> new IllegalStateException(
                "Operational control '" + key + "' is not provisioned."));
    }
}
