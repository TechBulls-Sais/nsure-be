package com.nsure.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigurationController {

    private final ConfigurationService configuration;
    private final PolicyAttributes attributes;

    public ConfigurationController(ConfigurationService configuration, PolicyAttributes attributes) {
        this.configuration = configuration;
        this.attributes = attributes;
    }

    @GetMapping("/admin/config-releases")
    public List<ConfigurationViews.ReleaseView> releases() {
        return configuration.releases();
    }

    @GetMapping("/admin/config-releases/{id}")
    public ConfigurationViews.ReleaseView release(@PathVariable Long id) {
        return configuration.release(id);
    }

    /** {code, description, forms: [{product, fields: [{key, type, label, required, sensitive, maxLength, allowedValues, helpText}]}]} */
    @PostMapping("/admin/config-releases")
    public ConfigurationViews.ReleaseView create(@RequestBody JsonNode body) {
        return configuration.createDraft(body);
    }

    /** Same body as create plus expectedVersion; drafts only. */
    @PutMapping("/admin/config-releases/{id}")
    public ConfigurationViews.ReleaseView update(@PathVariable Long id, @RequestBody JsonNode body) {
        return configuration.updateDraft(id, body);
    }

    /** {expectedVersion} for every action; reject also needs {reason}. */
    @PostMapping("/admin/config-releases/{id}/{action:submit|approve|reject|activate}")
    public ConfigurationViews.ReleaseView act(@PathVariable Long id, @PathVariable String action,
            @RequestBody JsonNode body) {
        return switch (action) {
            case "submit" -> configuration.submit(id, body);
            case "approve" -> configuration.approve(id, body);
            case "reject" -> configuration.reject(id, body);
            default -> configuration.activate(id, body);
        };
    }

    @GetMapping("/configuration/forms/policy")
    public ConfigurationViews.FormView policyForm(@RequestParam String product) {
        return configuration.activePolicyForm(product);
    }

    @GetMapping("/portfolio/policies/{ref}/attributes")
    public ConfigurationViews.PolicyAttributesView policyAttributes(@PathVariable String ref) {
        return attributes.view(ref);
    }

    /** {expectedVersion, customAttributes}: validated against the form the policy was issued under. */
    @PutMapping("/portfolio/policies/{ref}/attributes")
    public ConfigurationViews.PolicyAttributesView updatePolicyAttributes(@PathVariable String ref,
            @RequestBody JsonNode body) {
        return attributes.update(ref, body);
    }
}
