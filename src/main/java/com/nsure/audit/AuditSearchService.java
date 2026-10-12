package com.nsure.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.web.AuditEntryResponse;
import com.nsure.audit.web.AuditPageResponse;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.service.OfficeAccess;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.web.ResourceNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditSearchService {

    private final PlatformSecurityContext security;
    private final OfficeAccess officeAccess;
    private final CommandSourceRepository commands;
    private final ObjectMapper objectMapper;

    public AuditSearchService(PlatformSecurityContext security,
                              OfficeAccess officeAccess,
                              CommandSourceRepository commands,
                              ObjectMapper objectMapper) {
        this.security = security;
        this.officeAccess = officeAccess;
        this.commands = commands;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AuditPageResponse search(String actionName, String entityName, Long resourceId, Long makerId,
                                    LocalDate from, LocalDate to, String status, int page, int size) {
        AppUser actor = security.authenticatedUser();
        actor.validateHasReadPermission("AUDIT");
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("page must be >= 0 and size must be between 1 and 200");
        }
        if (status != null && !CommandStatus.isKnown(status)) {
            throw new IllegalArgumentException("Unknown status");
        }
        boolean headOffice = officeAccess.isHeadOffice(actor);
        String hierarchy = officeAccess.hierarchyOf(actor.getOfficeId());
        Page<CommandSource> found = commands.findAll(
                filter(actionName, entityName, resourceId, makerId, from, to, status, headOffice, hierarchy),
                PageRequest.of(page, size, Sort.by(Sort.Order.desc("madeOn"), Sort.Order.desc("id"))));
        List<AuditEntryResponse> content = found.stream().map(this::toResponse).toList();
        return new AuditPageResponse(content, found.getTotalElements(), page, size);
    }

    @Transactional(readOnly = true)
    public AuditEntryResponse get(Long id) {
        AppUser actor = security.authenticatedUser();
        actor.validateHasReadPermission("AUDIT");
        CommandSource command = commands.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Command " + id + " not found"));
        officeAccess.validateResourceOffice(command.getOfficeId(), actor);
        return toResponse(command);
    }

    private Specification<CommandSource> filter(String actionName, String entityName, Long resourceId, Long makerId,
                                                LocalDate from, LocalDate to, String status,
                                                boolean headOffice, String hierarchy) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actionName != null && !actionName.isBlank()) {
                predicates.add(builder.equal(root.get("actionName"), actionName.trim().toUpperCase(Locale.ROOT)));
            }
            if (entityName != null && !entityName.isBlank()) {
                predicates.add(builder.equal(root.get("entityName"), entityName.trim().toUpperCase(Locale.ROOT)));
            }
            if (resourceId != null) {
                predicates.add(builder.equal(root.get("resourceId"), resourceId));
            }
            if (makerId != null) {
                predicates.add(builder.equal(root.get("makerId"), makerId));
            }
            if (status != null && !status.isBlank()) {
                predicates.add(builder.equal(root.get("status"), status.trim()));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(
                        root.get("madeOn"), from.atStartOfDay().atOffset(ZoneOffset.UTC)));
            }
            if (to != null) {
                predicates.add(builder.lessThan(
                        root.get("madeOn"), to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
            }
            if (!headOffice) {
                if (hierarchy == null) {
                    predicates.add(builder.disjunction());
                } else {
                    Subquery<Long> officeIds = query.subquery(Long.class);
                    Root<Office> office = officeIds.from(Office.class);
                    officeIds.select(office.get("id"))
                            .where(builder.like(office.get("hierarchy"), hierarchy + "%"));
                    predicates.add(root.get("officeId").in(officeIds));
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private AuditEntryResponse toResponse(CommandSource command) {
        return new AuditEntryResponse(
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
                read(command.getCommandAsJson()),
                read(command.getResultJson()),
                command.getResultStatus());
    }

    private JsonNode read(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.getNodeFactory().textNode(json);
        }
    }
}
