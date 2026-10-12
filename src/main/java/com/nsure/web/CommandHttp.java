package com.nsure.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.CommandProcessingService;
import com.nsure.audit.CommandRequest;
import com.nsure.audit.ExecutedCommand;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class CommandHttp {

    private final CommandProcessingService commands;
    private final ObjectMapper objectMapper;

    public CommandHttp(CommandProcessingService commands, ObjectMapper objectMapper) {
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    public ResponseEntity<JsonNode> execute(String action, String entity, Long resourceId, JsonNode body,
                                            String idempotencyKey, String retryHeader) {
        String json = body == null || body.isNull() ? "{}" : body.toString();
        boolean retry = "true".equalsIgnoreCase(retryHeader);
        ExecutedCommand executed = commands.execute(
                new CommandRequest(action, entity, resourceId, json, idempotencyKey), retry);
        return ResponseEntity.status(executed.httpStatus()).body(read(executed.json()));
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json == null ? "{}" : json);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode().put("message", json);
        }
    }
}
