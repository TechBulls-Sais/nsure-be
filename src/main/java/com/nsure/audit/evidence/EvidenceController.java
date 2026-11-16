package com.nsure.audit.evidence;

import com.nsure.security.service.PlatformSecurityContext;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Audit evidence for auditors (READ_AUDIT): search it, and check that the chain is intact (AT-SH11-03). */
@RestController
@RequestMapping("/audits/evidence")
public class EvidenceController {

    public record EvidenceView(long sequenceNo, LocalDateTime occurredAt, String actor, String action,
            String entityType, String entityRef, String reason, String correlationId, String hash) {
    }

    private final EvidenceChain chain;
    private final PlatformSecurityContext security;

    public EvidenceController(EvidenceChain chain, PlatformSecurityContext security) {
        this.chain = chain;
        this.security = security;
    }

    @GetMapping
    public List<EvidenceView> search(@RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityRef, @RequestParam(required = false) String actor,
            @RequestParam(defaultValue = "200") int limit) {
        security.authenticatedUser().validateHasPermissionTo("READ_AUDIT");
        return chain.search(entityType, entityRef, actor, limit).stream()
                .map(e -> new EvidenceView(e.getSequenceNo(), e.getOccurredAt(), e.getActor(), e.getAction(),
                        e.getEntityType(), e.getEntityRef(), e.getReason(), e.getCorrelationId(), e.getHash()))
                .toList();
    }

    /** Recomputes every hash and link; names the first record that was changed or removed. */
    @GetMapping("/verify")
    public EvidenceChain.Verification verify() {
        security.authenticatedUser().validateHasPermissionTo("READ_AUDIT");
        return chain.verify();
    }
}
