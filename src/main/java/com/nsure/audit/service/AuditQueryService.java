package com.nsure.audit.service;

import com.nsure.audit.api.AuditPage;
import com.nsure.audit.api.AuditResponse;
import com.nsure.audit.api.CommandResponses;
import com.nsure.audit.domain.CommandSource;
import com.nsure.audit.repository.CommandSourceRepository;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.OfficeRepository;
import com.nsure.security.service.OfficeAccessValidator;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final CommandSourceRepository commands;
    private final PlatformSecurityContext security;
    private final OfficeAccessValidator access;
    private final OfficeRepository offices;
    private final CommandResponses responses;

    public AuditQueryService(
            CommandSourceRepository commands,
            PlatformSecurityContext security,
            OfficeAccessValidator access,
            OfficeRepository offices,
            CommandResponses responses) {
        this.commands = commands;
        this.security = security;
        this.access = access;
        this.offices = offices;
        this.responses = responses;
    }

    public AuditPage search(
            String actionName,
            String entityName,
            Long resourceId,
            Long makerId,
            String status,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        AppUser caller = security.authenticatedUser();
        caller.validateHasReadPermission("AUDIT");
        String hierarchy = access.hierarchyOf(caller);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Page<CommandSource> found = commands.search(
                upper(actionName),
                upper(entityName),
                resourceId,
                makerId,
                upper(status),
                from == null ? null : from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to == null ? null : to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC),
                access.isHeadOffice(hierarchy),
                hierarchy,
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "madeOn")));
        return new AuditPage(
                found.getContent().stream().map(this::toResponse).toList(),
                found.getTotalElements(),
                safePage,
                safeSize);
    }

    public AuditResponse get(Long id) {
        AppUser caller = security.authenticatedUser();
        caller.validateHasReadPermission("AUDIT");
        CommandSource command = commands.findById(id).orElseThrow(() -> new NotFoundException("Audit not found"));
        assertVisible(caller, command);
        return toResponse(command);
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

    private AuditResponse toResponse(CommandSource command) {
        return new AuditResponse(
                command.getId(),
                command.getActionName(),
                command.getEntityName(),
                command.getResourceId(),
                command.getOfficeId(),
                command.getMakerId(),
                command.getMadeOn(),
                command.getCheckerId(),
                command.getCheckedOn(),
                command.getStatus(),
                command.getResultStatus(),
                command.getIdempotencyKey(),
                responses.read(command.getCommandAsJson()),
                responses.read(command.getResultJson()));
    }

    private String upper(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }
}
