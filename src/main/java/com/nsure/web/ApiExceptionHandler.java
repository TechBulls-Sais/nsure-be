package com.nsure.web;

import com.nsure.audit.AwaitingApprovalException;
import com.nsure.audit.IdempotencyConflictException;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.exception.UnAuthenticatedUserException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(UnAuthenticatedUserException.class)
    ResponseEntity<ApiError> unauthenticated(UnAuthenticatedUserException ex) {
        return error(401, "Unauthorized", ex.getMessage());
    }

    @ExceptionHandler(NoAuthorizationException.class)
    ResponseEntity<ApiError> forbidden(NoAuthorizationException ex) {
        return error(403, "Forbidden", ex.getMessage());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound(ResourceNotFoundException ex) {
        return error(404, "Not Found", ex.getMessage());
    }

    @ExceptionHandler(AwaitingApprovalException.class)
    ResponseEntity<Map<String, Object>> awaiting(AwaitingApprovalException ex) {
        return ResponseEntity.status(202).body(Map.of(
                "commandId", ex.getCommandId(),
                "status", "AWAITING_APPROVAL"));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotency(IdempotencyConflictException ex) {
        return error(409, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException ex) {
        return error(400, "Bad Request", ex.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception ex) {
        return error(400, "Bad Request", "Request is not valid");
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiError> conflict(IllegalStateException ex) {
        return error(409, "Conflict", ex.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integrity(DataIntegrityViolationException ex) {
        return error(409, "Conflict", "Duplicate or invalid reference");
    }

    private ResponseEntity<ApiError> error(int status, String error, String message) {
        return ResponseEntity.status(status).body(new ApiError(status, error, message));
    }
}
