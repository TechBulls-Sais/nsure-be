package com.nsure.audit.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.AuditSearchService;
import com.nsure.audit.CommandProcessingService;
import com.nsure.audit.CommandResult;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audits")
public class AuditController {

    private final AuditSearchService audits;
    private final CommandProcessingService commands;
    private final ObjectMapper objectMapper;

    public AuditController(AuditSearchService audits, CommandProcessingService commands, ObjectMapper objectMapper) {
        this.audits = audits;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public AuditPageResponse search(
            @RequestParam(required = false) String actionName,
            @RequestParam(required = false) String entityName,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) Long makerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audits.search(actionName, entityName, resourceId, makerId, from, to, status, page, size);
    }

    @GetMapping("/{id}")
    public AuditEntryResponse get(@PathVariable Long id) {
        return audits.get(id);
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<JsonNode> approve(@PathVariable Long id) throws JsonProcessingException {
        CommandResult result = commands.approve(id);
        return ResponseEntity.ok(objectMapper.readTree(result.json()));
    }

    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        String reason = null;
        if (body != null && body.has("reason") && !body.get("reason").isNull()) {
            if (!body.get("reason").isTextual()) {
                throw new IllegalArgumentException("reason must be a string");
            }
            reason = body.get("reason").asText();
        }
        commands.reject(id, reason);
        return Map.of("commandId", id, "status", "REJECTED");
    }
}
