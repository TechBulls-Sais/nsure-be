package com.nsure.audit.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.service.CommandOutcome;
import com.nsure.audit.service.CommandRequest;
import org.springframework.stereotype.Component;

@Component
public class CommandResponses {

    private final ObjectMapper mapper;

    public CommandResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CommandRequest toCommand(
            String action,
            String entity,
            Long resourceId,
            JsonNode body,
            String idempotencyKey,
            Long officeId,
            String retryHeader) {
        return new CommandRequest(
                action,
                entity,
                resourceId,
                write(body),
                idempotencyKey,
                officeId,
                "true".equalsIgnoreCase(retryHeader));
    }

    public Long optionalLong(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field) || !body.get(field).isNumber()) {
            return null;
        }
        return body.get(field).longValue();
    }

    public CommandResponse toResponse(CommandOutcome outcome) {
        return new CommandResponse(outcome.commandId(), outcome.resourceId(), outcome.status(), read(outcome.resultJson()));
    }

    public JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return mapper.getNodeFactory().textNode(json);
        }
    }

    private String write(JsonNode body) {
        if (body == null || body.isNull()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Request body is not valid JSON");
        }
    }
}
