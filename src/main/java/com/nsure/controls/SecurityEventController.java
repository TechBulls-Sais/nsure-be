package com.nsure.controls;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Security events with an owner and an escalation route (SH11). */
@RestController
@RequestMapping("/admin/security-events")
public class SecurityEventController {

    private final SecurityEventService events;

    public SecurityEventController(SecurityEventService events) {
        this.events = events;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return events.list();
    }

    @PostMapping("/{id}/own")
    public Map<String, Object> own(@PathVariable Long id) {
        return events.own(id);
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return events.resolve(id, body == null || !body.hasNonNull("resolution") ? null
                : body.get("resolution").asText());
    }
}
