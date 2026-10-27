package com.nsure.security.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.api.CommandResponse;
import com.nsure.audit.api.CommandResponses;
import com.nsure.audit.service.CommandOutcome;
import com.nsure.audit.service.CommandProcessingService;
import com.nsure.security.service.UserQueryService;
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
@RequestMapping("/users")
public class UserController {

    private final CommandProcessingService commands;
    private final CommandResponses responses;
    private final UserQueryService users;

    public UserController(
            CommandProcessingService commands, CommandResponses responses, UserQueryService users) {
        this.commands = commands;
        this.responses = responses;
        this.users = users;
    }

    @GetMapping
    public List<UserResponse> list() {
        return users.list();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return users.get(id);
    }

    @PostMapping
    public ResponseEntity<CommandResponse> create(
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        Long officeId = responses.optionalLong(body, "officeId");
        CommandOutcome outcome = commands.execute(
                responses.toCommand("CREATE", "USER", null, body, idempotencyKey, officeId, retry));
        return respond(outcome, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CommandResponse> update(
            @PathVariable Long id,
            @RequestBody(required = false) JsonNode body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        Long officeId = responses.optionalLong(body, "officeId");
        if (officeId == null) {
            officeId = users.officeIdOf(id);
        }
        CommandOutcome outcome = commands.execute(
                responses.toCommand("UPDATE", "USER", id, body, idempotencyKey, officeId, retry));
        return respond(outcome, HttpStatus.OK);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<CommandResponse> delete(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        CommandOutcome outcome = commands.execute(
                responses.toCommand("DELETE", "USER", id, null, idempotencyKey, null, retry));
        return respond(outcome, HttpStatus.OK);
    }

    private ResponseEntity<CommandResponse> respond(CommandOutcome outcome, HttpStatus success) {
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : success;
        return ResponseEntity.status(status).body(responses.toResponse(outcome));
    }
}
