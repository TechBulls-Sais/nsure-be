package com.nsure.audit.evidence;

import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit evidence for auditors (READ_AUDIT): search it by record, actor and dates, export it as CSV (NS-SH11-007:
 * "audit evidence must be exportable in usable formats"), and check that the chain is intact (AT-SH11-03).
 */
@RestController
@RequestMapping("/audits/evidence")
public class EvidenceController {

    /** The most rows one export carries; narrow the dates for more. */
    static final int MAX_EXPORT = 10000;

    public record EvidenceView(long sequenceNo, LocalDateTime occurredAt, String actor, String action,
            String entityType, String entityRef, String reason, String correlationId, String hash) {

        static EvidenceView of(AuditEvidence e) {
            return new EvidenceView(e.getSequenceNo(), e.getOccurredAt(), e.getActor(), e.getAction(),
                    e.getEntityType(), e.getEntityRef(), e.getReason(), e.getCorrelationId(), e.getHash());
        }
    }

    private final EvidenceChain chain;
    private final PlatformSecurityContext security;

    public EvidenceController(EvidenceChain chain, PlatformSecurityContext security) {
        this.chain = chain;
        this.security = security;
    }

    /** Newest first; {@code from} and {@code to} are dates, both inclusive. */
    @GetMapping
    public List<EvidenceView> search(@RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityRef, @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "200") int limit) {
        security.authenticatedUser().validateHasPermissionTo("READ_AUDIT");
        return chain.search(entityType, entityRef, actor, start(from), end(to), limit, 500).stream()
                .map(EvidenceView::of).toList();
    }

    /**
     * The same search as a CSV file (up to {@value #MAX_EXPORT} rows), with the hash chain columns so the export can
     * be checked against the chain. The export itself is recorded as evidence.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityRef, @RequestParam(required = false) String actor,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_AUDIT");
        List<AuditEvidence> rows = chain.search(entityType, entityRef, actor, start(from), end(to), MAX_EXPORT,
                MAX_EXPORT);
        StringBuilder csv = new StringBuilder(
                "sequence_no,occurred_at,actor,action,entity_type,entity_ref,reason,correlation_id,hash\r\n");
        for (AuditEvidence e : rows) {
            csv.append(e.getSequenceNo()).append(',')
                    .append(cell(String.valueOf(e.getOccurredAt()))).append(',')
                    .append(cell(e.getActor())).append(',')
                    .append(cell(e.getAction())).append(',')
                    .append(cell(e.getEntityType())).append(',')
                    .append(cell(e.getEntityRef())).append(',')
                    .append(cell(e.getReason())).append(',')
                    .append(cell(e.getCorrelationId())).append(',')
                    .append(cell(e.getHash())).append("\r\n");
        }
        List<String> filters = new ArrayList<>();
        addFilter(filters, "type", entityType);
        addFilter(filters, "record", entityRef);
        addFilter(filters, "actor", actor);
        addFilter(filters, "from", from == null ? null : from.toString());
        addFilter(filters, "to", to == null ? null : to.toString());
        chain.record(user.getUsername(), "Exported audit evidence", "Audit evidence", null,
                rows.size() + " record(s)" + (filters.isEmpty() ? "" : "; " + String.join(", ", filters)));
        String name = "audit-evidence-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build()
                        .toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Recomputes every hash and link; names the first record that was changed or removed. */
    @GetMapping("/verify")
    public EvidenceChain.Verification verify() {
        security.authenticatedUser().validateHasPermissionTo("READ_AUDIT");
        return chain.verify();
    }

    private static LocalDateTime start(LocalDate from) {
        return from == null ? null : from.atStartOfDay();
    }

    private static LocalDateTime end(LocalDate to) {
        return to == null ? null : to.plusDays(1).atStartOfDay();
    }

    private static void addFilter(List<String> filters, String name, String value) {
        if (value != null && !value.isBlank()) {
            filters.add(name + " " + value.trim());
        }
    }

    /** One CSV cell: quoted, with a leading formula character neutralised so a spreadsheet shows it as text. */
    static String cell(String value) {
        if (value == null || "null".equals(value)) {
            return "";
        }
        String text = value;
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
