package com.nsure.audit.service;

import com.nsure.audit.domain.CommandSource;
import com.nsure.audit.domain.CommandStatus;
import com.nsure.audit.repository.CommandSourceRepository;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.OfficeRepository;
import com.nsure.security.service.OfficeAccessValidator;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MakerCheckerService {

    private final CommandSourceRepository commands;
    private final CommandHandlerRegistry handlers;
    private final PlatformSecurityContext security;
    private final MakerCheckerPolicy policy;
    private final OfficeRepository offices;
    private final OfficeAccessValidator access;
    private final EntityManager entityManager;

    public MakerCheckerService(
            CommandSourceRepository commands,
            CommandHandlerRegistry handlers,
            PlatformSecurityContext security,
            MakerCheckerPolicy policy,
            OfficeRepository offices,
            OfficeAccessValidator access,
            EntityManager entityManager) {
        this.commands = commands;
        this.handlers = handlers;
        this.security = security;
        this.policy = policy;
        this.offices = offices;
        this.access = access;
        this.entityManager = entityManager;
    }

    @Transactional
    public CommandOutcome approve(Long commandId) {
        CommandSource pending = loadPending(commandId);
        AppUser checker = security.authenticatedUser();
        policy.validateChecker(checker, pending);
        assertVisible(checker, pending);
        CommandResult result = handlers.get(pending.getEntityName(), pending.getActionName())
                .handle(new CommandRequest(
                        pending.getActionName(),
                        pending.getEntityName(),
                        pending.getResourceId(),
                        pending.getCommandAsJson(),
                        pending.getIdempotencyKey(),
                        pending.getOfficeId(),
                        false));
        entityManager.flush();
        pending.markChecked(checker.getId());
        pending.setResourceId(result.resourceId());
        pending.setResultJson(result.json());
        return new CommandOutcome(pending.getId(), result.resourceId(), pending.getStatus(), result.json(), false);
    }

    @Transactional
    public CommandOutcome reject(Long commandId) {
        CommandSource pending = loadPending(commandId);
        AppUser checker = security.authenticatedUser();
        policy.validateChecker(checker, pending);
        assertVisible(checker, pending);
        pending.markRejected(checker.getId());
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

    private void assertVisible(AppUser user, CommandSource command) {
        String hierarchy = access.hierarchyOf(user);
        if (command.getOfficeId() == null) {
            if (!access.isHeadOffice(hierarchy)) {
                throw new NoAuthorizationException("The user doesn't have enough permissions to access the resource.");
            }
            return;
        }
        Office office = offices.findById(command.getOfficeId())
                .orElseThrow(() -> new NoAuthorizationException(
                        "The user doesn't have enough permissions to access the resource."));
        access.validateAccess(office.getHierarchy(), hierarchy);
    }
}
