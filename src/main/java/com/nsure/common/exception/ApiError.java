package com.nsure.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.CorrelationId;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * The error body (NS-API-001): a stable {@code code} a client can branch on, a safe {@code message}, any
 * {@code fieldErrors}, and the request's {@code correlationId} for support. {@code detail} repeats the message for
 * RFC 7807 readers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String type,
        String title,
        int status,
        String code,
        String detail,
        String message,
        List<FieldError> fieldErrors,
        String correlationId,
        Long commandId,
        JsonNode result) {

    public record FieldError(String field, String message) {
    }

    /** Stable codes, one per kind of failure. */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    public static final String UNAUTHENTICATED = "UNAUTHENTICATED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String CONFLICT = "CONFLICT";
    public static final String STALE_VERSION = "STALE_VERSION";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    public static final String BUSINESS_RULE = "BUSINESS_RULE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    public static ApiError of(int status, String message) {
        return of(status, codeFor(status), message, List.of());
    }

    public static ApiError of(int status, String code, String message, List<FieldError> fieldErrors) {
        return new ApiError("about:blank", title(status), status, code, message, message,
                fieldErrors == null ? List.of() : fieldErrors, CorrelationId.current(), null, null);
    }

    public static ApiError of(int status, String message, Long commandId, JsonNode result) {
        return of(status, codeFor(status), message, commandId, result);
    }

    public static ApiError of(int status, String code, String message, Long commandId, JsonNode result) {
        return new ApiError("about:blank", title(status), status, code, message, message, List.of(),
                CorrelationId.current(), commandId, result);
    }

    /** The default code for a status, when nothing more specific applies. */
    public static String codeFor(int status) {
        return switch (status) {
            case 400 -> INVALID_REQUEST;
            case 401 -> UNAUTHENTICATED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 422 -> BUSINESS_RULE;
            case 429 -> RATE_LIMITED;
            case 202 -> AWAITING_APPROVAL;
            default -> status >= 500 ? INTERNAL_ERROR : "ERROR";
        };
    }

    private static String title(int status) {
        HttpStatus resolved = HttpStatus.resolve(status);
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }
}
