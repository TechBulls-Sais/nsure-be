package com.nsure.security.approval;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Claim approval limits per role (spec 04 §5.3). Read with READ_ROLE, change with UPDATE_ROLE. */
@RestController
@RequestMapping("/admin/approval-authorities")
public class ApprovalAuthorityController {

    private final ApprovalAuthorityService service;

    public ApprovalAuthorityController(ApprovalAuthorityService service) {
        this.service = service;
    }

    @GetMapping
    public List<ApprovalAuthorityService.AuthorityView> list() {
        return service.list();
    }

    @PutMapping("/{roleId}")
    public ApprovalAuthorityService.AuthorityView set(@PathVariable Long roleId, @RequestBody JsonNode body) {
        JsonNode amount = body == null ? null : body.get("maxPayoutAmount");
        if (amount == null || amount.isNull() || !(amount.isNumber() || amount.isTextual())) {
            throw new IllegalArgumentException("maxPayoutAmount is required");
        }
        try {
            return service.set(roleId, new BigDecimal(amount.asText().trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("maxPayoutAmount must be a number");
        }
    }

    @DeleteMapping("/{roleId}")
    public ResponseEntity<Void> remove(@PathVariable Long roleId) {
        service.remove(roleId);
        return ResponseEntity.noContent().build();
    }
}
