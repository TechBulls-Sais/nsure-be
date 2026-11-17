package com.nsure.common.exception;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.exception.AwaitingApprovalException;
import com.nsure.audit.exception.IdempotencyConflictException;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.exception.UnAuthenticatedUserException;
import java.util.List;
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
                .body(ApiError.of(409, ApiError.IDEMPOTENCY_CONFLICT, ex.getMessage(), ex.getCommandId(), read(ex.getResultJson())));
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

    /**
     * A business rule refused the request (NS-API-001): 422. A malformed request (unreadable JSON, a parameter of
     * the wrong type, a missing parameter or file) is 400, below.
     */
    /** A missing or malformed body field (NS-API-001 schema error): 400 with the field named in fieldErrors. */
    @ExceptionHandler(InvalidFieldException.class)
    public ResponseEntity<ApiError> invalidField(InvalidFieldException ex) {
        return invalid(ex.getField(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> businessRule(IllegalArgumentException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /**
     * A one-time code cannot be delivered on this installation (no messaging provider): the step is refused with 422,
     * not 503, so the screen shows the reason instead of "server not responding".
     */
    @ExceptionHandler(com.nsure.security.otp.CodeDeliveryUnavailableException.class)
    public ResponseEntity<ApiError> codeDeliveryUnavailable(com.nsure.security.otp.CodeDeliveryUnavailableException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    /** Someone else changed the record since the caller read it (NS-TXN-001): refresh and try again. */
    @ExceptionHandler(StaleVersionException.class)
    public ResponseEntity<ApiError> stale(StaleVersionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(409, ApiError.STALE_VERSION, ex.getMessage(), List.of()));
    }

    /** Two transactions raced on the same record and this one lost: the same answer as a stale version. */
    @ExceptionHandler({jakarta.persistence.OptimisticLockException.class,
            org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ApiError> raced(RuntimeException ex) {
        return stale(new StaleVersionException("This record was changed by someone else while you were working on it."
                + " Refresh to see the latest and try again."));
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> wrongType(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        return invalid(ex.getName(), ex.getName() + " has the wrong format.");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(org.springframework.web.bind.MissingServletRequestParameterException ex) {
        return invalid(ex.getParameterName(), ex.getParameterName() + " is required.");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(org.springframework.web.bind.MissingRequestHeaderException ex) {
        return invalid(ex.getHeaderName(), ex.getHeaderName() + " header is required.");
    }

    private ResponseEntity<ApiError> invalid(String field, String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiError.of(400, ApiError.INVALID_REQUEST, message, List.of(new ApiError.FieldError(field, message))));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "Files can be at most 10 MB.");
    }

    @ExceptionHandler({
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class})
    public ResponseEntity<ApiError> missingFile(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "Choose a file to upload.");
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
