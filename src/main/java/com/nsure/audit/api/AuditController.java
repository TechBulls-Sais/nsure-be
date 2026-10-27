package com.nsure.audit.api;

import com.nsure.audit.service.AuditQueryService;
import com.nsure.audit.service.MakerCheckerService;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audits")
public class AuditController {

    private final AuditQueryService audits;
    private final MakerCheckerService makerChecker;
    private final CommandResponses responses;

    public AuditController(AuditQueryService audits, MakerCheckerService makerChecker, CommandResponses responses) {
        this.audits = audits;
        this.makerChecker = makerChecker;
        this.responses = responses;
    }

    @GetMapping
    public AuditPage search(
            @RequestParam(required = false) String actionName,
            @RequestParam(required = false) String entityName,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) Long makerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return audits.search(actionName, entityName, resourceId, makerId, status, from, to, page, size);
    }

    @GetMapping("/{id}")
    public AuditResponse get(@PathVariable Long id) {
        return audits.get(id);
    }

    @PostMapping("/{id}/approve")
    public CommandResponse approve(@PathVariable Long id) {
        return responses.toResponse(makerChecker.approve(id));
    }

    @PostMapping("/{id}/reject")
    public CommandResponse reject(@PathVariable Long id) {
        return responses.toResponse(makerChecker.reject(id));
    }
}
