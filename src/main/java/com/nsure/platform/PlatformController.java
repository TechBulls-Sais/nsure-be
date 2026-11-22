package com.nsure.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Shared services: party merges, the wording library, data extracts and governed assistants. */
@RestController
@RequestMapping("/platform")
public class PlatformController {

    private final PlatformService platform;
    private final AssistantGovernance governance;

    public PlatformController(PlatformService platform, AssistantGovernance governance) {
        this.platform = platform;
        this.governance = governance;
    }

    @GetMapping("/parties/duplicates")
    public List<Map<String, Object>> duplicates() {
        return platform.duplicates();
    }

    @GetMapping("/parties/merges")
    public List<Map<String, Object>> merges() {
        return platform.merges();
    }

    @PostMapping("/parties/merges")
    public Map<String, Object> merge(@RequestBody JsonNode body) {
        return platform.merge(body);
    }

    @GetMapping("/wordings")
    public List<Map<String, Object>> wordings() {
        return platform.wordings();
    }

    @PostMapping("/wordings")
    public Map<String, Object> saveWording(@RequestBody JsonNode body) {
        return platform.saveWording(body);
    }

    @PostMapping("/wordings/{ref}/versions/{version}/approval")
    public Map<String, Object> approveWording(@PathVariable String ref, @PathVariable int version) {
        return platform.approveWording(ref, version);
    }

    @GetMapping("/metrics")
    public List<Map<String, Object>> metrics() {
        return platform.metrics();
    }

    /** {changeNote, fields to change}: proposes the next version of a metric definition. */
    @PostMapping("/metrics/{key}/versions")
    public Map<String, Object> proposeMetric(@PathVariable String key, @RequestBody JsonNode body) {
        return platform.proposeMetric(key, body);
    }

    @PostMapping("/metrics/{key}/versions/{version}/approval")
    public Map<String, Object> approveMetric(@PathVariable String key, @PathVariable int version) {
        return platform.approveMetric(key, version);
    }

    @GetMapping("/extracts")
    public List<Map<String, Object>> extractRuns() {
        return platform.extractRuns();
    }

    @PostMapping("/extracts")
    public Map<String, Object> extract(@RequestBody JsonNode body) {
        return platform.extract(body);
    }

    @GetMapping("/assistants")
    public List<Map<String, Object>> useCases() {
        return platform.useCases();
    }

    @PutMapping("/assistants/{code}")
    public List<Map<String, Object>> switchUseCase(@PathVariable String code, @RequestBody JsonNode body) {
        return platform.switchUseCase(code, body);
    }

    @GetMapping("/assistants/log")
    public List<Map<String, Object>> interactions() {
        return platform.interactions();
    }

    @PostMapping("/assistants/application-check/{ref}")
    public Map<String, Object> applicationCheck(@PathVariable String ref) {
        return platform.applicationCheck(ref);
    }

    @PostMapping("/assistants/claim-summary/{ref}")
    public Map<String, Object> claimSummary(@PathVariable String ref) {
        return platform.claimSummary(ref);
    }

    @PostMapping("/assistants/draft-message")
    public Map<String, Object> draftMessage(@RequestBody JsonNode body) {
        return platform.draftMessage(body);
    }

    @PostMapping("/assistants/risk-indicators/{customerRef}")
    public Map<String, Object> riskIndicators(@PathVariable String customerRef) {
        return platform.riskIndicators(customerRef);
    }

    /** {decision ACCEPT | OVERRIDE | REJECT, reason, finalText}: the human review of one output. */
    @PostMapping("/assistants/log/{id}/review")
    public Map<String, Object> review(@PathVariable Long id, @RequestBody JsonNode body) {
        return platform.review(id, body);
    }

    /** Sends a reviewed draft message as the reviewer's final text. */
    @PostMapping("/assistants/log/{id}/send")
    public Map<String, Object> sendDraft(@PathVariable Long id) {
        return platform.sendDraft(id);
    }

    @GetMapping("/assistants/changes")
    public List<Map<String, Object>> changes() {
        return governance.changes();
    }

    /** {toVersion, changeNote, validationSummary?}: a version change of an assistant's rules, for approval. */
    @PostMapping("/assistants/{code}/changes")
    public Map<String, Object> requestChange(@PathVariable String code, @RequestBody JsonNode body) {
        return governance.requestChange(code, body);
    }

    /** {approve, note}. */
    @PostMapping("/assistants/changes/{id}/decision")
    public Map<String, Object> decideChange(@PathVariable Long id, @RequestBody JsonNode body) {
        return governance.decideChange(id, body);
    }

    @GetMapping("/assistants/monitoring")
    public List<Map<String, Object>> monitoring() {
        return governance.monitoring();
    }

    @GetMapping("/ai-coverage")
    public List<Map<String, Object>> aiCoverage() {
        return governance.dispositions();
    }

    @GetMapping("/scope-exceptions")
    public List<Map<String, Object>> scopeExceptions() {
        return governance.scopeExceptions();
    }

    /** {decision ACCEPTED | REJECTED, note}: the insurer's decision on a proposed scope exception. */
    @PostMapping("/scope-exceptions/{code}/decision")
    public Map<String, Object> decideScope(@PathVariable String code, @RequestBody JsonNode body) {
        return governance.decideScope(code, body);
    }
}
