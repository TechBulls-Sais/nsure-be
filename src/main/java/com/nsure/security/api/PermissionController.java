package com.nsure.security.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.api.CommandResponse;
import com.nsure.audit.api.CommandResponses;
import com.nsure.audit.service.CommandOutcome;
import com.nsure.audit.service.CommandProcessingService;
import com.nsure.security.service.PermissionQueryService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping
    public ResponseEntity<CommandResponse> create(
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        CommandOutcome outcome = commands.execute(
                responses.toCommand("CREATE", "PERMISSION", null, body, idempotencyKey, null, retry));
        return respond(outcome, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CommandResponse> update(
            @PathVariable Long id,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        CommandOutcome outcome = commands.execute(
                responses.toCommand("UPDATE", "PERMISSION", id, body, idempotencyKey, null, retry));
        return respond(outcome, HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<CommandResponse> delete(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        CommandOutcome outcome = commands.execute(
                responses.toCommand("DELETE", "PERMISSION", id, null, idempotencyKey, null, retry));
        return respond(outcome, HttpStatus.OK);
    }

    private ResponseEntity<CommandResponse> respond(CommandOutcome outcome, HttpStatus success) {
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : success;
        return ResponseEntity.status(status).body(responses.toResponse(outcome));
    }
}
