package com.nsure.audit.service;

import com.nsure.audit.api.AuditPage;
import com.nsure.audit.api.AuditResponse;
import com.nsure.audit.api.CommandResponses;
import com.nsure.audit.domain.CommandSource;
import com.nsure.audit.repository.CommandSourceRepository;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The command audit trail, read by holders of READ_AUDIT. */
@Service
@Transactional(readOnly = true)
public class AuditQueryService {

    private final CommandSourceRepository commands;
    private final PlatformSecurityContext security;
    private final CommandResponses responses;

    public AuditQueryService(
            CommandSourceRepository commands,
            PlatformSecurityContext security,
            CommandResponses responses) {
        this.commands = commands;
        this.security = security;
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
        return toResponse(command);
    }

    private AuditResponse toResponse(CommandSource command) {
        return new AuditResponse(
                command.getId(),
                command.getActionName(),
                command.getEntityName(),
                command.getResourceId(),
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
