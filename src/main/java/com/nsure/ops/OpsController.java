package com.nsure.ops;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Partner portals, workflow definitions and calendar, the document register, and payouts (queue: WorkQueueController). */
@RestController
@RequestMapping("/ops")
public class OpsController {

    private final OpsService ops;

    public OpsController(OpsService ops) {
        this.ops = ops;
    }

    @GetMapping("/portal")
    public Map<String, Object> portal() {
        return ops.portal();
    }

    @PostMapping("/portal/requests")
    public Map<String, Object> portalRequest(@RequestBody JsonNode body) {
        return ops.portalRequest(body);
    }

    @GetMapping("/portal-requests")
    public List<Map<String, Object>> portalRequests() {
        return ops.portalRequests();
    }

    @PostMapping("/portal-requests/{ref}/close")
    public Map<String, Object> closePortalRequest(@PathVariable String ref, @RequestBody JsonNode body) {
        return ops.closePortalRequest(ref, body);
    }

    @GetMapping("/portal-accounts")
    public List<Map<String, Object>> portalAccounts() {
        return ops.portalAccounts();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/portal-accounts/{username}")
    public List<Map<String, Object>> removePortalAccount(@PathVariable String username,
            @RequestBody(required = false) JsonNode body) {
        return ops.removePortalAccount(username, body);
    }

    @PutMapping("/portal-accounts")
    public List<Map<String, Object>> savePortalAccount(@RequestBody JsonNode body) {
        return ops.savePortalAccount(body);
    }

    @GetMapping("/workflow")
    public Map<String, Object> workflow() {
        return ops.workflow();
    }

    @PutMapping("/workflow/definitions/{code}")
    public Map<String, Object> saveDefinition(@PathVariable String code, @RequestBody JsonNode body) {
        return ops.saveDefinition(code, body);
    }

    @PutMapping("/workflow/calendar")
    public Map<String, Object> saveCalendar(@RequestBody JsonNode body) {
        return ops.saveCalendar(body);
    }

    /** Filters: q (details and document text), customerRef, ownerRef (policy or claim), documentClass, from, to. */
    @GetMapping("/documents")
    public Map<String, Object> documents(@RequestParam(required = false) String q,
            @RequestParam(required = false) String customerRef, @RequestParam(required = false) String ownerRef,
            @RequestParam(required = false) String documentClass,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate from,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate to) {
        return ops.documents(q, customerRef, ownerRef, documentClass, from, to);
    }

    @GetMapping("/documents/disposals")
    public List<Map<String, Object>> disposals() {
        return ops.disposals();
    }

    @PostMapping("/documents/disposals")
    public Map<String, Object> requestDisposal(@RequestBody JsonNode body) {
        return ops.requestDisposal(body);
    }

    @PostMapping("/documents/disposals/{id}/approve")
    public Map<String, Object> approveDisposal(@PathVariable Long id) {
        return ops.decideDisposal(id, true);
    }

    @PostMapping("/documents/disposals/{id}/reject")
    public Map<String, Object> rejectDisposal(@PathVariable Long id) {
        return ops.decideDisposal(id, false);
    }

    @PutMapping("/documents/hold")
    public Map<String, Object> legalHold(@RequestParam String docKey, @RequestBody JsonNode body) {
        return ops.legalHold(docKey, body);
    }

    @PutMapping("/documents/retention")
    public Map<String, Object> saveRetention(@RequestBody JsonNode body) {
        return ops.saveRetention(body);
    }

    @GetMapping("/payouts")
    public List<Map<String, Object>> payouts() {
        return ops.payouts();
    }
}
