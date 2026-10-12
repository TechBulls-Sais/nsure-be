package com.nsure.audit;

import com.nsure.config.MakerCheckerProperties;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.PermissionCodes;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.OfficeAccess;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.web.ResourceNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandProcessingService {

    private final PlatformSecurityContext security;
    private final CommandSourceRepository commands;
    private final CommandSourceStore store;
    private final CommandWorker worker;
    private final CommandHandlerRegistry handlers;
    private final MakerCheckerPolicy makerChecker;
    private final MakerCheckerProperties makerCheckerProperties;
    private final CommandJsonSanitizer sanitizer;
    private final OfficeAccess officeAccess;

    public CommandProcessingService(PlatformSecurityContext security,
                                    CommandSourceRepository commands,
                                    CommandSourceStore store,
                                    CommandWorker worker,
                                    CommandHandlerRegistry handlers,
                                    MakerCheckerPolicy makerChecker,
                                    MakerCheckerProperties makerCheckerProperties,
                                    CommandJsonSanitizer sanitizer,
                                    OfficeAccess officeAccess) {
        this.security = security;
        this.commands = commands;
        this.store = store;
        this.worker = worker;
        this.handlers = handlers;
        this.makerChecker = makerChecker;
        this.makerCheckerProperties = makerCheckerProperties;
        this.sanitizer = sanitizer;
        this.officeAccess = officeAccess;
    }

    public ExecutedCommand execute(CommandRequest request, boolean idempotencyRetry) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(request.permissionCode());

        String sanitized = sanitizer.sanitize(request.json());
        if (request.idempotencyKey() != null) {
            Optional<CommandSource> existing = commands.findByActionNameAndEntityNameAndIdempotencyKey(
                    request.actionName(), request.entityName(), request.idempotencyKey());
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), user, request, sanitized, idempotencyRetry);
            }
        }

        boolean needsChecker = makerChecker.requiresApproval(user, request.permissionCode(), request.resourceId());
        CommandSource draft = newCommand(request, user, sanitized);
        try {
            draft = store.insert(draft);
        } catch (DataIntegrityViolationException ex) {
            if (request.idempotencyKey() == null) {
                throw new IllegalStateException("Duplicate or invalid reference");
            }
            CommandSource raced = commands.findByActionNameAndEntityNameAndIdempotencyKey(
                            request.actionName(), request.entityName(), request.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("Duplicate or invalid reference"));
            return resolveExisting(raced, user, request, sanitized, idempotencyRetry);
        }
        return worker.execute(draft.getId(), request, needsChecker);
    }

    @Transactional
    public CommandResult approve(Long commandId) {
        CommandSource pending = commands.lockById(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Command " + commandId + " not found"));
        AppUser checker = security.authenticatedUser();
        authorizeChecker(checker, pending);
        officeAccess.validateResourceOffice(pending.getOfficeId(), checker);
        if (!CommandStatus.AWAITING_APPROVAL.equals(pending.getStatus())) {
            throw new IllegalStateException("Command is not awaiting approval");
        }
        if (!makerCheckerProperties.isAllowSameUser() && checker.getId().equals(pending.getMakerId())) {
            throw new IllegalStateException("Maker cannot check their own command");
        }
        CommandResult result = handlers.get(pending.getEntityName(), pending.getActionName())
                .handle(new CommandRequest(
                        pending.getActionName(),
                        pending.getEntityName(),
                        pending.getResourceId(),
                        pending.getCommandAsJson(),
                        null));
        pending.markChecked(checker.getId());
        pending.setResourceId(result.resourceId());
        pending.setResultJson(result.json());
        return result;
    }

    @Transactional
    public void reject(Long commandId, String reason) {
        CommandSource pending = commands.lockById(commandId)
                .orElseThrow(() -> new ResourceNotFoundException("Command " + commandId + " not found"));
        AppUser checker = security.authenticatedUser();
        authorizeChecker(checker, pending);
        officeAccess.validateResourceOffice(pending.getOfficeId(), checker);
        if (!CommandStatus.AWAITING_APPROVAL.equals(pending.getStatus())) {
            throw new IllegalStateException("Command is not awaiting approval");
        }
        if (!makerCheckerProperties.isAllowSameUser() && checker.getId().equals(pending.getMakerId())) {
            throw new IllegalStateException("Maker cannot check their own command");
        }
        pending.markRejected(checker.getId(), reason);
    }

    private ExecutedCommand resolveExisting(CommandSource existing, AppUser user, CommandRequest request,
                                            String sanitized, boolean idempotencyRetry) {
        if (!existing.getMakerId().equals(user.getId())) {
            throw new IdempotencyConflictException("Idempotency key belongs to another user");
        }
        return switch (existing.getStatus()) {
            case CommandStatus.UNDER_PROCESSING ->
                    throw new IdempotencyConflictException("Command " + existing.getId() + " is still running");
            case CommandStatus.PROCESSED -> replay(existing);
            case CommandStatus.AWAITING_APPROVAL -> throw new AwaitingApprovalException(existing.getId());
            case CommandStatus.REJECTED ->
                    throw new IdempotencyConflictException("Command " + existing.getId() + " was rejected");
            case CommandStatus.ERROR -> {
                if (!idempotencyRetry) {
                    String stored = existing.getResultJson() == null ? "Command failed" : existing.getResultJson();
                    throw new IdempotencyConflictException(stored);
                }
                store.prepareRetry(existing.getId(), sanitized);
                boolean needsChecker = makerChecker.requiresApproval(user, request.permissionCode(), request.resourceId());
                yield worker.execute(existing.getId(), request, needsChecker);
            }
            default -> throw new IllegalStateException("Unknown command status");
        };
    }

    private ExecutedCommand replay(CommandSource existing) {
        int status = existing.getResultStatus() == null ? 200 : existing.getResultStatus();
        String json = existing.getResultJson() == null ? "{}" : existing.getResultJson();
        return new ExecutedCommand(status, json, existing.getId(), existing.getResourceId());
    }

    private CommandSource newCommand(CommandRequest request, AppUser user, String sanitized) {
        CommandSource audit = new CommandSource();
        audit.setActionName(request.actionName());
        audit.setEntityName(request.entityName());
        audit.setResourceId(request.resourceId());
        audit.setOfficeId(user.getOfficeId());
        audit.setCommandAsJson(sanitized);
        audit.setMakerId(user.getId());
        audit.setMadeOn(OffsetDateTime.now(ZoneOffset.UTC));
        audit.setIdempotencyKey(request.idempotencyKey());
        audit.setStatus(CommandStatus.UNDER_PROCESSING);
        return audit;
    }

    private void authorizeChecker(AppUser checker, CommandSource pending) {
        String required = PermissionCodes.checkerCode(pending.getPermissionCode());
        if (checker.hasPermissionTo(PermissionCodes.CHECKER_SUPER_USER) || checker.hasPermissionTo(required)) {
            return;
        }
        throw new NoAuthorizationException("User has no authority to: " + required);
    }
}
