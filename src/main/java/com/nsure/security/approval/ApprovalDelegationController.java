package com.nsure.security.approval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Time-bound delegation of claim approval authority (NS-SH10-003). */
@RestController
@RequestMapping("/admin/approval-delegations")
public class ApprovalDelegationController {

    private final ApprovalAuthorityService authorities;

    public ApprovalDelegationController(ApprovalAuthorityService authorities) {
        this.authorities = authorities;
    }

    @GetMapping
    public Map<String, Object> list() {
        return authorities.delegations();
    }

    @PostMapping
    public Map<String, Object> delegate(@RequestBody JsonNode body) {
        return authorities.delegate(body);
    }

    @PostMapping("/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id) {
        return authorities.revokeDelegation(id);
    }
}
