package com.nsure.mipas.control;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class ControlIntegrityCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ControlIntegrityCheck.class);

    private final ControlService controls;

    public ControlIntegrityCheck(ControlService controls) {
        this.controls = controls;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> missing = ControlKey.all().stream()
                .filter(key -> !controls.exists(key))
                .map(ControlKey::key)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Operational controls are not provisioned: " + missing
                            + ". The application will not start with an unknown control state.");
        }
        controls.all().stream()
                .filter(control -> control.isThresholdLike() && control.getNumericValue() == null)
                .findAny()
                .ifPresent(control -> {
                    throw new IllegalStateException(
                            "Control " + control.getControlKey() + " has no value. "
                                    + "It would fail closed and silently disable the feature it governs.");
                });
        controls.disabled().forEach(control -> log.warn(
                "OPERATIONAL CONTROL DISABLED: {} — {} (by {} on {}: {})",
                control.getControlKey(),
                control.getName(),
                control.getLastChangedBy(),
                control.getLastChangedAt(),
                control.getLastChangeReason()));
    }
}
