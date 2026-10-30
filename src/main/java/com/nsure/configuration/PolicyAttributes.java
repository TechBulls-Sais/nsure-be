package com.nsure.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A policy's insurer-configured extra fields (guide §15). New business is pinned to the product's active form and
 * validated against it; afterwards the policy is always read and edited against that pinned form, so a later form
 * with more required fields never breaks an unrelated update of an old policy.
 */
@Service
public class PolicyAttributes {

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final ConfigurationService configuration;
    private final PolicyFieldValidator validator;
    private final ObjectMapper mapper;

    public PolicyAttributes(EntityManager em, PlatformSecurityContext security, ConfigurationService configuration,
            PolicyFieldValidator validator, ObjectMapper mapper) {
        this.em = em;
        this.security = security;
        this.configuration = configuration;
        this.validator = validator;
        this.mapper = mapper;
    }

    /**
     * Pins a new policy to its product's active form and stores validated values. A client that loaded an older
     * form (formVersionId) is told to reload rather than having its values silently checked against another form.
     */
    @Transactional
    public void pinNewPolicy(InsurancePolicy policy, JsonNode body, boolean requireAll) {
        Optional<PolicyFormVersion> active = configuration.activeForm(policy.getProduct());
        JsonNode submitted = body == null ? null : body.get("customAttributes");
        Long expected = body != null && body.hasNonNull("formVersionId") ? body.get("formVersionId").asLong() : null;
        if (active.isEmpty()) {
            if (submitted != null && submitted.isObject() && !submitted.isEmpty()) {
                throw new IllegalArgumentException(policy.getProduct() + " has no extra policy fields configured.");
            }
            policy.setFormVersionId(null);
            policy.setCustomAttributes(null);
            return;
        }
        PolicyFormVersion form = active.get();
        if (expected != null && !expected.equals(form.getId())) {
            throw new ConflictException("The policy form for " + policy.getProduct() + " changed (now version "
                    + form.getVersionNo() + "). Reload it and enter the extra details again.");
        }
        policy.setFormVersionId(form.getId());
        policy.setCustomAttributes(json(validator.validate(form, submitted, requireAll)));
    }

    @Transactional(readOnly = true)
    public ConfigurationViews.PolicyAttributesView view(String ref) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_POLICY");
        return view(policy(ref), user.hasPermissionTo("UPDATE_POLICY"));
    }

    /** Edits the values against the policy's own pinned form, refusing a stale expectedVersion (409). */
    @Transactional
    public ConfigurationViews.PolicyAttributesView update(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UPDATE_POLICY");
        InsurancePolicy policy = policy(ref);
        if (body == null || !body.hasNonNull("expectedVersion")) {
            throw new IllegalArgumentException("expectedVersion is required.");
        }
        if (body.get("expectedVersion").asLong() != policy.getRowVersion()) {
            throw new ConflictException("Policy " + ref + " was changed by someone else; reload it.");
        }
        if (policy.getFormVersionId() == null) {
            throw new IllegalArgumentException("Policy " + ref + " was issued without extra fields; collect new"
                    + " details through an endorsement.");
        }
        PolicyFormVersion form = em.find(PolicyFormVersion.class, policy.getFormVersionId());
        policy.setCustomAttributes(json(validator.validate(form, body.get("customAttributes"))));
        em.flush();
        return view(policy, true);
    }

    private ConfigurationViews.PolicyAttributesView view(InsurancePolicy policy, boolean showSensitive) {
        PolicyFormVersion form = policy.getFormVersionId() == null ? null
                : em.find(PolicyFormVersion.class, policy.getFormVersionId());
        ObjectNode values = read(policy.getCustomAttributes());
        if (form != null && !showSensitive) {
            form.getFields().stream().filter(PolicyFieldDefinition::isSensitive)
                    .forEach(f -> {
                        if (values.has(f.getKey())) {
                            values.put(f.getKey(), "••••");
                        }
                    });
        }
        boolean current = form != null && configuration.activeForm(policy.getProduct())
                .map(active -> Objects.equals(active.getId(), form.getId())).orElse(false);
        return new ConfigurationViews.PolicyAttributesView(policy.getRef(), policy.getProduct(),
                policy.getRowVersion(), form == null ? null : ConfigurationService.form(form), values, current);
    }

    private InsurancePolicy policy(String ref) {
        return em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Policy " + ref + " not found"));
    }

    private ObjectNode read(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (JsonProcessingException | ClassCastException ex) {
            throw new IllegalStateException("Stored policy attributes are not a JSON object", ex);
        }
    }

    private String json(ObjectNode values) {
        try {
            return values.isEmpty() ? null : mapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
