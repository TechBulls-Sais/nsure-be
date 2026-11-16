package com.nsure.security.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.api.CommandResponse;
import com.nsure.audit.api.CommandResponses;
import com.nsure.audit.service.CommandOutcome;
import com.nsure.audit.service.CommandProcessingService;
import com.nsure.security.service.PermissionQueryService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The permission vocabulary. Codes are defined in source (PermissionCatalog, spec 04 A-4) and seeded by Liquibase,
 * so this API reads them and edits only their label, group and maker-checker flag.
 */
@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final PermissionQueryService permissions;
    private final CommandProcessingService commands;
    private final CommandResponses responses;

    public PermissionController(
            PermissionQueryService permissions, CommandProcessingService commands, CommandResponses responses) {
        this.permissions = permissions;
        this.commands = commands;
        this.responses = responses;
    }

    @GetMapping
    public List<PermissionResponse> list() {
        return permissions.list();
    }

    @GetMapping("/{id}")
    public PermissionResponse get(@PathVariable Long id) {
        return permissions.get(id);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CommandResponse> update(
            @PathVariable Long id,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        CommandOutcome outcome = commands.execute(
                responses.toCommand("UPDATE", "PERMISSION", id, body, idempotencyKey, retry));
        return ResponseEntity.ok(responses.toResponse(outcome));
    }
}
