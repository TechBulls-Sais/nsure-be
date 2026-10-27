package com.nsure.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String title,
        int status,
        String detail,
        String message,
        Long commandId,
        JsonNode result) {

    public static ApiError of(int status, String message) {
        return new ApiError("about:blank", title(status), status, message, message, null, null);
    }

    public static ApiError of(int status, String message, Long commandId, JsonNode result) {
        return new ApiError("about:blank", title(status), status, message, message, commandId, result);
    }

    private static String title(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }
}
