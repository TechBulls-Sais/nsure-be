package com.nsure.controls;

import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's staff alerts: only those whose permission they hold. */
@RestController
public class StaffAlertController {

    private final StaffAlertService alerts;
    private final PlatformSecurityContext security;

    public StaffAlertController(StaffAlertService alerts, PlatformSecurityContext security) {
        this.alerts = alerts;
        this.security = security;
    }

    @GetMapping("/me/alerts")
    public List<AlertView> mine() {
        return alerts.visibleTo(security.authenticatedUser()).stream()
                .map(a -> new AlertView(a.getId(), a.getAlertType(), a.getTitle(), a.getBody(), a.getCreatedAt()))
                .toList();
    }

    public record AlertView(Long id, String type, String title, String body, LocalDateTime createdAt) {
    }
}
