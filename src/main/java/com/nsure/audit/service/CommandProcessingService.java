package com.nsure.audit.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.domain.CommandSource;
import com.nsure.audit.domain.CommandStatus;
import com.nsure.audit.exception.AwaitingApprovalException;
import com.nsure.audit.exception.IdempotencyConflictException;
import com.nsure.audit.repository.CommandSourceRepository;
import com.nsure.audit.service.CommandPayloadSanitizer.SanitizeResult;
import com.nsure.common.exception.CommandFailedException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandProcessingService {

    private static final Logger log = LoggerFactory.getLogger(CommandProcessingService.class);

    private final PlatformSecurityContext security;
    private final CommandSourceRepository commands;
    private final CommandSourceWriter writer;
    private final CommandHandlerRegistry handlers;
    private final MakerCheckerPolicy makerChecker;
    private final CommandPayloadSanitizer sanitizer;
    private final ObjectMapper mapper;
    private final EntityManager entityManager;

    public CommandProcessingService(
            PlatformSecurityContext security,
            CommandSourceRepository commands,
            CommandSourceWriter writer,
            CommandHandlerRegistry handlers,
            MakerCheckerPolicy makerChecker,
            CommandPayloadSanitizer sanitizer,
            ObjectMapper mapper,
            EntityManager entityManager) {
        this.security = security;
        this.commands = commands;
        this.writer = writer;
        this.handlers = handlers;
        this.makerChecker = makerChecker;
        this.sanitizer = sanitizer;
        this.mapper = mapper;
        this.entityManager = entityManager;
    }

    // READ_COMMITTED so this transaction can see the command row inserted by saveNew
    // (REQUIRES_NEW). MySQL's default REPEATABLE READ hides that committed insert and
    // the later status update fails as a stale row.
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public CommandOutcome execute(CommandRequest request) {
        AppUser user = security.authenticatedUser();
        String action = normalizeToken(request.actionName(), "actionName");
        String entity = normalizeToken(request.entityName(), "entityName");
        user.validateHasPermissionTo(action + "_" + entity);

        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        String originalJson = request.json() == null || request.json().isBlank() ? "{}" : request.json();
        SanitizeResult sanitized = sanitizer.sanitize(originalJson);

        Optional<CommandSource> existing = findExisting(action, entity, idempotencyKey);
        CommandSource audit;
        if (existing.isPresent()) {
            CommandSource prior = existing.get();
            CommandOutcome replay = existingOutcome(prior, request.idempotencyRetry());
            if (replay != null) {
                return replay;
            }
            detach(prior);
            prior.reopenForRetry(
                    sanitized.json(), request.resourceId(), request.officeId(), OffsetDateTime.now(ZoneOffset.UTC));
            audit = writer.saveNew(prior);
        } else {
            CommandSource created = new CommandSource();
            created.setActionName(action);
            created.setEntityName(entity);
            created.setResourceId(request.resourceId());
            created.setOfficeId(request.officeId());
            created.setCommandAsJson(sanitized.json());
            created.setMakerId(user.getId());
            created.setMadeOn(OffsetDateTime.now(ZoneOffset.UTC));
            created.setIdempotencyKey(idempotencyKey);
            created.setStatus(CommandStatus.UNDER_PROCESSING);
            audit = writer.saveNew(created);
        }

        boolean needsChecker = makerChecker.requiresChecker(user, entity, action, request.resourceId());
        CommandRequest normalized = new CommandRequest(
                action,
                entity,
                request.resourceId(),
                originalJson,
                idempotencyKey,
                request.officeId(),
                request.idempotencyRetry());

        try {
            if (needsChecker && sanitized.secretsRedacted()) {
                throw new IllegalArgumentException("Commands that contain secrets cannot be sent for approval");
            }
            CommandResult result = handlers.get(entity, action).handle(normalized);
            entityManager.flush();
            if (needsChecker) {
                audit.markAwaitingApproval();
                if (sanitized.truncated()) {
                    audit.setResultJson(truncationNote(sanitized));
                }
                writer.saveNew(audit);
                throw new AwaitingApprovalException(audit.getId());
            }
            String resultJson = withTruncation(result.json(), sanitized);
            audit.markProcessed(result.resourceId(), resultJson);
            writer.saveSame(audit);
            return new CommandOutcome(audit.getId(), result.resourceId(), CommandStatus.PROCESSED, resultJson, false);
        } catch (AwaitingApprovalException ex) {
            throw ex;
        } catch (NotFoundException ex) {
            recordFailure(audit, 404, ex.getMessage());
            throw new CommandFailedException(404, audit.getId(), ex.getMessage());
        } catch (NoAuthorizationException ex) {
            recordFailure(audit, 403, ex.getMessage());
            throw new CommandFailedException(403, audit.getId(), ex.getMessage());
        } catch (IllegalArgumentException ex) {
            recordFailure(audit, 400, ex.getMessage());
            throw new CommandFailedException(400, audit.getId(), ex.getMessage());
        } catch (DataIntegrityViolationException ex) {
            recordFailure(audit, 409, "Duplicate or invalid reference");
            throw new CommandFailedException(409, audit.getId(), "Duplicate or invalid reference");
        } catch (PersistenceException ex) {
            if (isConstraintViolation(ex)) {
                recordFailure(audit, 409, "Duplicate or invalid reference");
                throw new CommandFailedException(409, audit.getId(), "Duplicate or invalid reference");
            }
            log.warn("Command {} failed", audit.getId(), ex);
            recordFailure(audit, 500, ex.getMessage() == null ? "Unexpected error" : ex.getMessage());
            throw new CommandFailedException(500, audit.getId(), "Unexpected error");
        } catch (RuntimeException ex) {
            log.warn("Command {} failed", audit.getId(), ex);
            recordFailure(audit, 500, ex.getMessage() == null ? "Unexpected error" : ex.getMessage());
            throw new CommandFailedException(500, audit.getId(), "Unexpected error");
        }
    }

    private CommandOutcome existingOutcome(CommandSource prior, boolean retry) {
        return switch (prior.getStatus()) {
            case CommandStatus.PROCESSED -> new CommandOutcome(
                    prior.getId(), prior.getResourceId(), prior.getStatus(), prior.getResultJson(), true);
            case CommandStatus.AWAITING_APPROVAL -> throw new AwaitingApprovalException(prior.getId());
            case CommandStatus.UNDER_PROCESSING -> throw new IdempotencyConflictException(
                    prior.getId(), prior.getStatus(), "Command is still running", prior.getResultJson());
            case CommandStatus.ERROR, CommandStatus.REJECTED -> {
                if (retry) {
                    yield null;
                }
                String message = CommandStatus.REJECTED.equals(prior.getStatus())
                        ? "Command was rejected"
                        : "Command previously failed";
                throw new IdempotencyConflictException(prior.getId(), prior.getStatus(), message, prior.getResultJson());
            }
            default -> throw new IdempotencyConflictException(
                    prior.getId(), prior.getStatus(), "Command already exists", prior.getResultJson());
        };
    }

    private Optional<CommandSource> findExisting(String action, String entity, String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return commands.findByActionNameAndEntityNameAndIdempotencyKey(action, entity, idempotencyKey);
    }

    private void recordFailure(CommandSource audit, int status, String message) {
        detach(audit);
        if (CommandStatus.PROCESSED.equals(audit.getStatus())) {
            audit.setStatus(CommandStatus.UNDER_PROCESSING);
        }
        String safe = message == null || message.isBlank() ? "Unexpected error" : message;
        if (safe.length() > 2000) {
            safe = safe.substring(0, 2000);
        }
        try {
            audit.markError(status, mapper.writeValueAsString(Map.of("message", safe)));
        } catch (JsonProcessingException ex) {
            audit.markError(status, "{\"message\":\"Unexpected error\"}");
        }
        writer.saveNew(audit);
    }

    private boolean isConstraintViolation(Throwable ex) {
        while (ex != null) {
            if (ex instanceof ConstraintViolationException || ex instanceof DataIntegrityViolationException) {
                return true;
            }
            ex = ex.getCause();
        }
        return false;
    }

    private void detach(CommandSource audit) {
        try {
            if (entityManager.contains(audit)) {
                entityManager.detach(audit);
            }
        } catch (RuntimeException ex) {
            log.debug("Could not detach command {}", audit.getId(), ex);
        }
    }

    private String withTruncation(String resultJson, SanitizeResult sanitized) {
        if (!sanitized.truncated()) {
            return resultJson;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("note", "command payload truncated");
        body.put("sha256", sanitized.sha256());
        body.put("result", resultJson == null ? "" : resultJson);
        return write(body);
    }

    private String truncationNote(SanitizeResult sanitized) {
        return write(Map.of("note", "command payload truncated", "sha256", sanitized.sha256()));
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{\"note\":\"command payload truncated\"}";
        }
    }

    private String normalizeToken(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim().toUpperCase();
    }

    private String normalizeIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.length() > 64) {
            throw new IllegalArgumentException("Idempotency-Key must be at most 64 characters");
        }
        return trimmed;
    }
}
