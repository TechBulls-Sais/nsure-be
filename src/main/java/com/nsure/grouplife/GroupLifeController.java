package com.nsure.grouplife;

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

/** Group lifecycle: quotations, scheme benefits, renewal/suspension/closure, certificates and group credit. */
@RestController
@RequestMapping("/group-life")
public class GroupLifeController {

    private final GroupLifeService groupLife;

    public GroupLifeController(GroupLifeService groupLife) {
        this.groupLife = groupLife;
    }

    @GetMapping("/quotes")
    public List<Map<String, Object>> quotes() {
        return groupLife.quotes();
    }

    @PostMapping("/quotes")
    public Map<String, Object> quote(@RequestBody JsonNode body) {
        return groupLife.quote(body);
    }

    @PostMapping("/quotes/{ref}/revision")
    public Map<String, Object> revise(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.revise(ref, body);
    }

    /** {action SUBMIT|APPROVE|DECLINE|ACCEPT, note?} */
    @PostMapping("/quotes/{ref}/progress")
    public Map<String, Object> progress(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.progress(ref, body);
    }

    /** {inceptionDate, wordingConfirmed} */
    @PostMapping("/quotes/{ref}/activation")
    public Map<String, Object> activate(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.activate(ref, body);
    }

    @GetMapping("/schemes/{ref}/benefits")
    public List<Map<String, Object>> benefits(@PathVariable String ref) {
        return groupLife.benefits(ref);
    }

    @PutMapping("/schemes/{ref}/benefits")
    public Map<String, Object> saveBenefit(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.saveBenefit(ref, body);
    }

    /** {note}: a second person approves the scheme's pending benefit changes (publishes SchemePlanApproved). */
    @PostMapping("/schemes/{ref}/benefits/approval")
    public List<Map<String, Object>> approvePlan(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.approvePlan(ref, body);
    }

    @GetMapping("/schemes/{ref}/impact")
    public Map<String, Object> impact(@PathVariable String ref, @RequestParam String eventType,
            @RequestParam(required = false) String effectiveDate,
            @RequestParam(required = false) String targetSchemeRef,
            @RequestParam(required = false) String rateChangePercent) {
        return groupLife.impact(ref, eventType, effectiveDate, targetSchemeRef, rateChangePercent);
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(@RequestParam(required = false) String schemeRef) {
        return groupLife.events(schemeRef);
    }

    /** {eventType RENEWAL|SUSPENSION|CLOSURE|TRANSFER|REACTIVATION, effectiveDate?, rateChangePercent?, targetSchemeRef?, reason} */
    @PostMapping("/schemes/{ref}/events")
    public Map<String, Object> requestEvent(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.requestEvent(ref, body);
    }

    /** {approve, note} */
    @PostMapping("/events/{ref}/decision")
    public Map<String, Object> decideEvent(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.decideEvent(ref, body);
    }

    @GetMapping("/schemes/{ref}/timeline")
    public List<Map<String, Object>> timeline(@PathVariable String ref) {
        return groupLife.timeline(ref);
    }

    @GetMapping("/schemes/{ref}/resolved-rules")
    public Map<String, Object> resolvedRules(@PathVariable String ref) {
        return groupLife.resolvedRules(ref);
    }

    @GetMapping("/schemes/{ref}/bordereaux")
    public List<Map<String, Object>> bordereaux(@PathVariable String ref) {
        return groupLife.bordereaux(ref);
    }

    @GetMapping("/schemes/{ref}/certificates")
    public List<Map<String, Object>> certificates(@PathVariable String ref) {
        return groupLife.certificates(ref);
    }

    @GetMapping("/schemes/{ref}/credit-loans")
    public List<Map<String, Object>> creditLoans(@PathVariable String ref) {
        return groupLife.creditLoans(ref);
    }

    /** {batchReference, ratePerMille?, rows [{lenderLoanId, borrowerName, loanAmount, termMonths}]} */
    @PostMapping("/schemes/{ref}/credit-loans")
    public Map<String, Object> bordereau(@PathVariable String ref, @RequestBody JsonNode body) {
        return groupLife.bordereau(ref, body);
    }

    @GetMapping("/credit-loans/{id}/settlement")
    public Map<String, Object> settlementPreview(@PathVariable Long id,
            @RequestParam(required = false) String settlementDate) {
        return groupLife.settlementPreview(id, settlementDate);
    }

    @PostMapping("/credit-loans/{id}/settlement")
    public Map<String, Object> settleLoan(@PathVariable Long id, @RequestBody(required = false) JsonNode body) {
        return groupLife.settleLoan(id, body);
    }
}
