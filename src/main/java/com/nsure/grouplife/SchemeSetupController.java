package com.nsure.grouplife;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.group.GroupViews;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Contract set-up (GL01): sponsors, approved group wordings, the activation check and activating a scheme in set-up. */
@RestController
public class SchemeSetupController {

    private final SchemeSetup setup;
    private final ObjectMapper mapper;
    private final com.nsure.group.GroupRating rating;

    public SchemeSetupController(SchemeSetup setup, ObjectMapper mapper, com.nsure.group.GroupRating rating) {
        this.setup = setup;
        this.mapper = mapper;
        this.rating = rating;
    }

    // ── quotation census, history and rate tables (NS-GL01-001/002) ─────────────────────────────

    /** {csv}: a new census version for the draft quotation, which is priced again on it. */
    @PostMapping("/group-life/quotes/{ref}/census")
    public Map<String, Object> uploadCensus(@PathVariable String ref, @RequestBody JsonNode body) {
        return setup.uploadCensus(ref, body);
    }

    @GetMapping("/group-life/quotes/{ref}/census/{version}")
    public Map<String, Object> census(@PathVariable String ref, @PathVariable int version) {
        return setup.census(ref, version);
    }

    @GetMapping("/group-life/quotes/{ref}/history")
    public List<Map<String, Object>> history(@PathVariable String ref) {
        return setup.history(ref);
    }

    @GetMapping("/group-life/rate-tables")
    public List<Map<String, Object>> rateTables() {
        return rating.rateTables();
    }

    /** {tableCode, label, benefitType, ratingBasis, bands [{ageFrom, ageTo, rate}], note?}: a new draft version. */
    @PostMapping("/group-life/rate-tables")
    public Map<String, Object> draftRateTable(@RequestBody JsonNode body) {
        return rating.draftRateTable(body);
    }

    @PostMapping("/group-life/rate-tables/{code}/versions/{version}/approval")
    public Map<String, Object> approveRateTable(@PathVariable String code, @PathVariable int version) {
        return rating.approveRateTable(code, version);
    }

    /** The scheme schedule and benefit summary generated from the accepted version (NS-GL01-005). */
    @GetMapping("/group-life/schemes/{ref}/schedule")
    public org.springframework.http.ResponseEntity<byte[]> schedule(@PathVariable String ref) {
        return pdf(setup.schedule(ref), "scheme-schedule-" + ref + ".pdf");
    }

    @GetMapping("/group-life/schemes/{ref}/certificates/{memberKey}/document")
    public org.springframework.http.ResponseEntity<byte[]> certificate(@PathVariable String ref,
            @PathVariable String memberKey) {
        return pdf(setup.certificate(ref, memberKey), "certificate-" + ref + "-" + memberKey + ".pdf");
    }

    private static org.springframework.http.ResponseEntity<byte[]> pdf(byte[] bytes, String name) {
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .body(bytes);
    }

    @GetMapping("/group-life/sponsors")
    public List<Map<String, Object>> sponsors() {
        return setup.sponsors();
    }

    /** {legalName, registrationNo?, sector, contactName?, contactEmail?} */
    @PostMapping("/group-life/sponsors")
    public Map<String, Object> createSponsor(@RequestBody JsonNode body) {
        return setup.createSponsor(body);
    }

    @GetMapping("/group-life/wordings")
    public List<Map<String, Object>> wordings() {
        return setup.wordings();
    }

    /** What activating the quote (or the scheme in set-up on it) would check, with nothing changed. */
    @GetMapping("/group-life/quotes/{ref}/activation-check")
    public Map<String, Object> activationCheck(@PathVariable String ref,
            @RequestParam(required = false) String schemeRef,
            @RequestParam(required = false) String wordingRef,
            @RequestParam(required = false) List<String> conditions) {
        ObjectNode body = mapper.createObjectNode();
        if (wordingRef != null) {
            body.put("wordingRef", wordingRef);
        }
        if (conditions != null) {
            conditions.forEach(body.putArray("conditions")::add);
        }
        return setup.readiness(ref, schemeRef, body);
    }

    /** {quoteRef, wordingRef, conditions [..], sponsorRef?, payerName?, brokerRef?, billingMethod?, billingDueDay?, aawRule?, partialCoverAllowed?} */
    @PostMapping("/group/schemes/{ref}/activation")
    public GroupViews.SchemeDetailView activate(@PathVariable String ref, @RequestBody JsonNode body) {
        return setup.activateScheme(ref, body);
    }
}
