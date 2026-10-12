package com.nsure.security.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.security.service.UserQueryService;
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
@RequestMapping("/users")
public class UserController {

    private final CommandHttp commands;
    private final UserQueryService users;

    public UserController(CommandHttp commands, UserQueryService users) {
        this.commands = commands;
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
    public ResponseEntity<JsonNode> create(@RequestBody JsonNode body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        return commands.execute("CREATE", "USER", null, body, idempotencyKey, retry);
    }

    @PutMapping("/{id}")
    public ResponseEntity<JsonNode> update(@PathVariable Long id,
                                           @RequestBody JsonNode body,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                           @RequestHeader(value = "Idempotency-Retry", required = false) String retry) {
        return commands.execute("UPDATE", "USER", id, body, idempotencyKey, retry);
    }
}
