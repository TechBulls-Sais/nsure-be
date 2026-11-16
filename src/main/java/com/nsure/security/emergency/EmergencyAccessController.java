package com.nsure.security.emergency;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /admin/emergency-access}: grant, revoke and review time-limited break-glass access (NS-SH11-003). */
@RestController
@RequestMapping("/admin/emergency-access")
public class EmergencyAccessController {

    private final EmergencyAccessService service;

    public EmergencyAccessController(EmergencyAccessService service) {
        this.service = service;
    }

    @GetMapping
    public List<EmergencyAccessService.GrantView> list() {
        return service.list();
    }

    /** Body: {@code {"username": "...", "permission": "UPDATE_CONTROL", "reason": "...", "hours": 2}}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmergencyAccessService.GrantView grant(@RequestBody(required = false) JsonNode body) {
        return service.grant(text(body, "username"), text(body, "permission"), text(body, "reason"),
                body != null && body.hasNonNull("hours") && body.get("hours").canConvertToInt()
                        ? body.get("hours").intValue() : null);
    }

    @PostMapping("/{id}/revoke")
    public EmergencyAccessService.GrantView revoke(@PathVariable Long id) {
        return service.revoke(id);
    }

    /** Body: {@code {"note": "..."}}. */
    @PostMapping("/{id}/review")
    public EmergencyAccessService.GrantView review(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return service.review(id, text(body, "note"));
    }

    private static String text(JsonNode body, String field) {
        return body != null && body.hasNonNull(field) ? body.get(field).asText() : null;
    }
}
