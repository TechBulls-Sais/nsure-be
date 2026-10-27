package com.nsure.common.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.exception.AwaitingApprovalException;
import com.nsure.audit.exception.IdempotencyConflictException;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.exception.UnAuthenticatedUserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final ObjectMapper mapper;

    public ApiExceptionHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @ExceptionHandler(NoAuthorizationException.class)
    public ResponseEntity<ApiError> forbidden(NoAuthorizationException ex) {
        return problem(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    /** A @PreAuthorize refusal; without this the catch-all below would turn it into a 500. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(UnAuthenticatedUserException.class)
    public ResponseEntity<ApiError> unauthenticated(UnAuthenticatedUserException ex) {
        return problem(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(AwaitingApprovalException.class)
    public ResponseEntity<ApiError> awaiting(AwaitingApprovalException ex) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(202, ex.getMessage(), ex.getCommandId(), null));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> idempotency(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(409, ex.getMessage(), ex.getCommandId(), read(ex.getResultJson())));
    }

    @ExceptionHandler(CommandFailedException.class)
    public ResponseEntity<ApiError> commandFailed(CommandFailedException ex) {
        return ResponseEntity.status(ex.getStatus())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(ex.getStatus(), ex.getMessage(), ex.getCommandId(), null));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
    }

    /** A path or method no endpoint serves is the caller's mistake, not a server fault. */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiError> noEndpoint(org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "No endpoint " + ex.getResourcePath());
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        log.error("Unhandled error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }

    private ResponseEntity<ApiError> problem(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(status.value(), message));
    }

    private JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return mapper.getNodeFactory().textNode(json);
        }
    }
}
