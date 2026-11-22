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

    public SchemeSetupController(SchemeSetup setup, ObjectMapper mapper) {
        this.setup = setup;
        this.mapper = mapper;
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
