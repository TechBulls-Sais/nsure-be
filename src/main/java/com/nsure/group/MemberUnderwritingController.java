package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Group member underwriting (GL03): the member's underwriting file, evidence requirements, the second underwriter's
 * authority, the member's answer to an offer, return to work, category transfer and offer expiry.
 */
@RestController
public class MemberUnderwritingController {

    private final MemberUnderwriting underwriting;

    public MemberUnderwritingController(MemberUnderwriting underwriting) {
        this.underwriting = underwriting;
    }

    @GetMapping("/group/members/{id}/underwriting-file")
    public Map<String, Object> file(@PathVariable Long id) {
        return underwriting.file(id);
    }

    @GetMapping("/group/members/{id}/evidence")
    public List<Map<String, Object>> evidence(@PathVariable Long id) {
        return underwriting.evidence(id);
    }

    /** {requirement, note?}: a requirement added for a risk exception. */
    @PostMapping("/group/members/{id}/evidence")
    public Map<String, Object> addEvidence(@PathVariable Long id, @RequestBody JsonNode body) {
        return underwriting.addEvidence(id, body);
    }

    /** {status RECEIVED|WAIVED, receivedOn?, note?} */
    @PostMapping("/group/member-evidence/{id}")
    public Map<String, Object> updateEvidence(@PathVariable Long id, @RequestBody JsonNode body) {
        return underwriting.updateEvidence(id, body);
    }

    /** {approve, note}: a second underwriter confirms a decision above the first one's authority. */
    @PostMapping("/group/members/{id}/underwriting/authority")
    public Map<String, Object> authority(@PathVariable Long id, @RequestBody JsonNode body) {
        return underwriting.confirmAuthority(id, body);
    }

    /** {accepted, note?}: the member's answer to an offer on modified terms. */
    @PostMapping("/group/members/{id}/underwriting/offer-response")
    public Map<String, Object> offerResponse(@PathVariable Long id, @RequestBody JsonNode body) {
        return underwriting.memberResponse(id, body);
    }

    /** {effectiveDate?, note?}: the employer confirms a member with deferred cover is actively at work. */
    @PostMapping("/group/members/{id}/actively-at-work")
    public GroupViews.MemberView backAtWork(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return underwriting.backAtWork(id, body);
    }

    /** {categoryCode, effectiveDate?, reason?} */
    @PostMapping("/group/members/{id}/category-transfer")
    public GroupViews.MemberView transfer(@PathVariable Long id, @RequestBody JsonNode body) {
        return underwriting.transferCategory(id, body);
    }

    @PostMapping("/group/underwriting/expiry-run")
    public Map<String, Object> expiry() {
        return underwriting.runExpiry();
    }
}
