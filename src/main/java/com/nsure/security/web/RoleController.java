package com.nsure.security.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.security.service.RoleQueryService;
import com.nsure.web.CommandHttp;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/roles")
public class RoleController {

    private final CommandHttp commands;
    private final RoleQueryService roles;

    public RoleController(CommandHttp commands, RoleQueryService roles) {
        this.commands = commands;
        this.roles = roles;
    }

    @GetMapping
    public List<RoleResponse> list() {
        return roles.list();
    }

    @GetMapping("/{id}")
    public RoleResponse get(@PathVariable Long id) {
        return roles.get(id);
    }

    @PostMapping
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        return commands.execute("CREATE", "ROLE", null, body, idempotencyKey, retry);
    }

    @PutMapping("/{id}")
    public ResponseEntity<JsonNode> update(@PathVariable Long id,
                                           @RequestBody JsonNode body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        return commands.execute("UPDATE", "ROLE", id, body, idempotencyKey, retry);
    }

    @PutMapping("/{id}/permissions")
    public ResponseEntity<JsonNode> permissions(@PathVariable Long id,
                                                @RequestBody JsonNode body,
                                                @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        return commands.execute("PERMISSIONS", "ROLE", id, body, idempotencyKey, retry);
    }
}
