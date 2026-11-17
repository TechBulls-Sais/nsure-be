package com.nsure.audit.service;

import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.audit.domain.CommandSource;
import com.nsure.audit.domain.CommandStatus;
import com.nsure.audit.repository.CommandSourceRepository;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approves or rejects a command waiting for a checker. The checker needs {@code CHECKER_<code>} and must not be the
 * maker (no self-approval for anyone, Nsure Life NS-UX-004 / AT-SH10-01).
 */
@Service
public class MakerCheckerService {

    private final CommandSourceRepository commands;
    private final CommandHandlerRegistry handlers;
    private final PlatformSecurityContext security;
    private final MakerCheckerPolicy policy;
    private final EntityManager entityManager;
    private final EvidenceChain evidence;
    private final Outbox outbox;

    public MakerCheckerService(
            CommandSourceRepository commands,
            CommandHandlerRegistry handlers,
            PlatformSecurityContext security,
            MakerCheckerPolicy policy,
            EntityManager entityManager,
            EvidenceChain evidence,
            Outbox outbox) {
        this.evidence = evidence;
        this.outbox = outbox;
        this.commands = commands;
        this.handlers = handlers;
        this.security = security;
        this.policy = policy;
        this.entityManager = entityManager;
    }

    @Transactional
    public CommandOutcome approve(Long commandId) {
        CommandSource pending = loadPending(commandId);
        AppUser checker = security.authenticatedUser();
        policy.validateChecker(checker, pending);
        CommandResult result = handlers.get(pending.getEntityName(), pending.getActionName())
                .handle(new CommandRequest(
                        pending.getActionName(),
                        pending.getEntityName(),
                        pending.getResourceId(),
                        pending.getCommandAsJson(),
                        pending.getIdempotencyKey(),
                        false));
        entityManager.flush();
        pending.markChecked(checker.getId());
        evidence.record(checker.getUsername(), "Approved " + pending.getPermissionCode(), pending.getEntityName(),
                result.resourceId() == null ? null : String.valueOf(result.resourceId()),
                "Command " + pending.getId() + ", made by user " + pending.getMakerId());
        pending.setResourceId(result.resourceId());
        pending.setResultJson(result.json());
        // Spec SH10 ApprovalGranted: references only, never the command body (which may hold a password hash).
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("commandId", pending.getId());
        event.put("permissionCode", pending.getPermissionCode());
        event.put("entity", pending.getEntityName());
        event.put("resourceId", result.resourceId());
        event.put("makerId", pending.getMakerId());
        event.put("checker", checker.getUsername());
        outbox.publish(Events.APPROVAL_GRANTED, "COMMAND-" + pending.getId(), event, checker.getUsername());
        return new CommandOutcome(pending.getId(), result.resourceId(), pending.getStatus(), result.json(), false);
    }

    @Transactional
    public CommandOutcome reject(Long commandId) {
        CommandSource pending = loadPending(commandId);
        AppUser checker = security.authenticatedUser();
        policy.validateChecker(checker, pending);
        pending.markRejected(checker.getId());
        evidence.record(checker.getUsername(), "Rejected " + pending.getPermissionCode(), pending.getEntityName(),
                pending.getResourceId() == null ? null : String.valueOf(pending.getResourceId()),
                "Command " + pending.getId() + ", made by user " + pending.getMakerId());
        pending.setResultJson("{\"rejected\":true}");
        return new CommandOutcome(
                pending.getId(), pending.getResourceId(), pending.getStatus(), pending.getResultJson(), false);
    }

    private CommandSource loadPending(Long commandId) {
        CommandSource pending = commands.findById(commandId)
                .orElseThrow(() -> new NotFoundException("Command not found"));
        if (!CommandStatus.AWAITING_APPROVAL.equals(pending.getStatus())) {
            throw new ConflictException("Command is not awaiting approval");
        }
        return pending;
    }
}
