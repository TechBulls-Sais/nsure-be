package com.nsure.configuration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuration releases (guide §7, §12, §16): a maker drafts and submits, a different user approves or rejects, a
 * deployer activates. There is no Super Admin bypass for approving your own release. Approved content is identified
 * by its checksum, which is re-computed before approval and activation.
 */
@Service
public class ConfigurationService {

    private static final int MAX_FIELDS = 30;
    /** Core policy values are typed columns with domain rules; a custom field may never shadow them. */
    private static final Set<String> RESERVED_KEYS = Set.of("id", "ref", "name", "customername", "product", "tier",
            "amount", "cover", "premium", "status", "recorddate", "coverstartdate", "nextpremiumdue",
            "graceperiodend", "paymentmethod", "beneficiaries", "lives", "formversionid", "customattributes");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final ObjectMapper mapper;

    public ConfigurationService(EntityManager em, PlatformSecurityContext security, ObjectMapper mapper) {
        this.em = em;
        this.security = security;
        this.mapper = mapper;
    }

    // ── reads ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ConfigurationViews.ReleaseView> releases() {
        security.authenticatedUser().validateHasPermissionTo("READ_CONFIG");
        return em.createQuery("select r from ConfigRelease r order by r.id desc", ConfigRelease.class)
                .getResultList().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public ConfigurationViews.ReleaseView release(Long id) {
        security.authenticatedUser().validateHasPermissionTo("READ_CONFIG");
        return view(find(id));
    }

    /** The active form for new business on a product; a product without one has no extra fields. */
    @Transactional(readOnly = true)
    public ConfigurationViews.FormView activePolicyForm(String product) {
        return activeForm(product).map(ConfigurationService::form)
                .orElse(new ConfigurationViews.FormView(null, product, null, "NONE", null, List.of()));
    }

    @Transactional(readOnly = true)
    public List<String> activeReleaseCodes() {
        return em.createQuery("select r.code from ConfigRelease r where r.status = :active order by r.id",
                String.class).setParameter("active", ConfigRelease.ACTIVE).getResultList();
    }

    Optional<PolicyFormVersion> activeForm(String product) {
        return em.createQuery("select f from PolicyFormVersion f where f.product = :product and f.status = :active",
                        PolicyFormVersion.class)
                .setParameter("product", product)
                .setParameter("active", PolicyFormVersion.ACTIVE)
                .getResultStream()
                .findFirst();
    }

    // ── maker ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public ConfigurationViews.ReleaseView createDraft(JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        String code = required(body, "code").toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9][A-Z0-9-]{2,39}")) {
            throw new IllegalArgumentException("code: 3-40 letters, digits and dashes, e.g. 2026-R2.");
        }
        if (!em.createQuery("select r.id from ConfigRelease r where r.code = :code", Long.class)
                .setParameter("code", code).getResultList().isEmpty()) {
            throw new ConflictException("Release " + code + " already exists.");
        }
        ConfigRelease release = new ConfigRelease(code, required(body, "description"), user.getUsername());
        em.persist(release);
        replaceForms(release, body.get("forms"));
        return saved(release);
    }

    @Transactional
    public ConfigurationViews.ReleaseView updateDraft(Long id, JsonNode body) {
        user("MANAGE_CONFIG");
        ConfigRelease release = find(id);
        requireVersion(release, body);
        requireStatus(release, ConfigRelease.DRAFT, "edited");
        release.describe(required(body, "description"));
        replaceForms(release, body.get("forms"));
        return saved(release);
    }

    @Transactional
    public ConfigurationViews.ReleaseView submit(Long id, JsonNode body) {
        AppUser user = user("MANAGE_CONFIG");
        ConfigRelease release = find(id);
        requireVersion(release, body);
        requireStatus(release, ConfigRelease.DRAFT, "submitted");
        if (release.getForms().isEmpty()) {
            throw new IllegalArgumentException("Add at least one product form before submitting.");
        }
        release.submit(checksum(release), schemaVersion(), user.getUsername());
        return saved(release);
    }

    // ── approver ──────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public ConfigurationViews.ReleaseView approve(Long id, JsonNode body) {
        AppUser user = user("APPROVE_CONFIG");
        ConfigRelease release = find(id);
        requireVersion(release, body);
        requireStatus(release, ConfigRelease.IN_REVIEW, "approved");
        requireIndependent(release, user);
        requireChecksum(release);
        release.approve(user.getUsername());
        return saved(release);
    }

    @Transactional
    public ConfigurationViews.ReleaseView reject(Long id, JsonNode body) {
        AppUser user = user("APPROVE_CONFIG");
        ConfigRelease release = find(id);
        requireVersion(release, body);
        requireStatus(release, ConfigRelease.IN_REVIEW, "rejected");
        requireIndependent(release, user);
        release.reject(required(body, "reason"), user.getUsername());
        return saved(release);
    }

    // ── deployer ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Each product form of the release becomes that product's next active version; the previous active form is
     * retired (policies issued under it keep it). A release whose forms have all been superseded is retired too.
     */
    @Transactional
    public ConfigurationViews.ReleaseView activate(Long id, JsonNode body) {
        AppUser user = user("ACTIVATE_CONFIG");
        ConfigRelease release = find(id);
        requireVersion(release, body);
        requireStatus(release, ConfigRelease.APPROVED, "activated");
        requireChecksum(release);
        for (PolicyFormVersion form : release.getForms()) {
            activeForm(form.getProduct()).ifPresent(PolicyFormVersion::retire);
            Integer max = em.createQuery("select max(f.versionNo) from PolicyFormVersion f where f.product = :p",
                    Integer.class).setParameter("p", form.getProduct()).getSingleResult();
            form.activate((max == null ? 0 : max) + 1, sha256(canonical(form).toString()));
        }
        release.activate(user.getUsername());
        em.flush();
        for (ConfigRelease other : em.createQuery("select r from ConfigRelease r where r.status = :active and r.id <> :id",
                ConfigRelease.class).setParameter("active", ConfigRelease.ACTIVE).setParameter("id", id).getResultList()) {
            if (other.getForms().stream().allMatch(f -> PolicyFormVersion.RETIRED.equals(f.getStatus()))) {
                other.retire();
            }
        }
        return saved(release);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void replaceForms(ConfigRelease release, JsonNode forms) {
        release.getForms().clear();
        em.flush();
        if (forms == null || forms.isNull()) {
            return;
        }
        if (!forms.isArray()) {
            throw new IllegalArgumentException("forms must be a list.");
        }
        Set<String> products = new HashSet<>(em.createQuery("select p.name from InsuranceProduct p", String.class)
                .getResultList());
        Set<String> seen = new HashSet<>();
        for (JsonNode node : forms) {
            String product = required(node, "product");
            if (!products.contains(product)) {
                throw new IllegalArgumentException("forms: " + product + " is not a product.");
            }
            if (!seen.add(product)) {
                throw new IllegalArgumentException("forms: " + product + " appears twice.");
            }
            PolicyFormVersion form = new PolicyFormVersion(release, product);
            release.getForms().add(form);
            JsonNode fields = node.get("fields");
            if (fields == null || !fields.isArray() || fields.isEmpty()) {
                throw new IllegalArgumentException(product + ": add at least one field.");
            }
            if (fields.size() > MAX_FIELDS) {
                throw new IllegalArgumentException(product + ": at most " + MAX_FIELDS + " extra fields.");
            }
            Set<String> keys = new HashSet<>();
            int order = 0;
            for (JsonNode f : fields) {
                form.getFields().add(field(form, product, f, keys, ++order));
            }
        }
    }

    private static PolicyFieldDefinition field(PolicyFormVersion form, String product, JsonNode f, Set<String> keys,
            int order) {
        String key = required(f, "key");
        String where = product + "." + key;
        if (!key.matches("[a-z][A-Za-z0-9]{1,39}")) {
            throw new IllegalArgumentException(where + ": keys are camelCase letters and digits, e.g. employerReference.");
        }
        if (RESERVED_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(where + ": is a core policy field and cannot be configured.");
        }
        if (!keys.add(key)) {
            throw new IllegalArgumentException(where + ": appears twice.");
        }
        String type = required(f, "type").toUpperCase(Locale.ROOT);
        if (!PolicyFieldDefinition.TYPES.contains(type)) {
            throw new IllegalArgumentException(where + ": type must be one of " + PolicyFieldDefinition.TYPES + ".");
        }
        Integer maxLength = null;
        if ("STRING".equals(type)) {
            maxLength = f.hasNonNull("maxLength") ? f.get("maxLength").asInt() : 100;
            if (maxLength < 1 || maxLength > 500) {
                throw new IllegalArgumentException(where + ": maxLength must be 1-500.");
            }
        }
        List<String> allowed = new ArrayList<>();
        if (f.hasNonNull("allowedValues")) {
            JsonNode values = f.get("allowedValues");
            if (values.isArray()) {
                values.forEach(v -> addAllowed(allowed, v.asText()));
            } else {
                for (String v : values.asText().split(",")) {
                    addAllowed(allowed, v);
                }
            }
        }
        if ("ENUM".equals(type) && allowed.size() < 2) {
            throw new IllegalArgumentException(where + ": a choice field needs at least two allowed values.");
        }
        if (String.join(",", allowed).length() > 500) {
            throw new IllegalArgumentException(where + ": allowed values are too long.");
        }
        String label = required(f, "label");
        return new PolicyFieldDefinition(form, key, type, label.length() > 100 ? label.substring(0, 100) : label,
                f.path("required").asBoolean(false), f.path("sensitive").asBoolean(false), maxLength,
                "ENUM".equals(type) ? allowed : List.of(), text(f, "helpText"), order);
    }

    private static void addAllowed(List<String> allowed, String value) {
        String v = value.trim();
        if (v.isEmpty() || v.contains(",")) {
            return;
        }
        if (!allowed.contains(v)) {
            allowed.add(v);
        }
    }

    private void requireIndependent(ConfigRelease release, AppUser user) {
        if (user.getUsername().equalsIgnoreCase(release.getCreatedBy())
                || user.getUsername().equalsIgnoreCase(release.getSubmittedBy())) {
            throw new NoAuthorizationException("You drafted or submitted release " + release.getCode()
                    + "; a different user must review it.");
        }
    }

    private void requireChecksum(ConfigRelease release) {
        if (!checksum(release).equals(release.getChecksum())) {
            throw new ConflictException("Release " + release.getCode() + " no longer matches its submitted checksum.");
        }
    }

    private static void requireStatus(ConfigRelease release, String status, String verb) {
        if (!status.equals(release.getStatus())) {
            throw new ConflictException("Release " + release.getCode() + " is " + release.getStatus()
                    + " and cannot be " + verb + ".");
        }
    }

    /** NS-TXN-001: every write names the version it read; a stale one is refused instead of overwriting. */
    private static void requireVersion(ConfigRelease release, JsonNode body) {
        if (body == null || !body.hasNonNull("expectedVersion")) {
            throw new IllegalArgumentException("expectedVersion is required.");
        }
        if (body.get("expectedVersion").asLong() != release.getRowVersion()) {
            throw new ConflictException("Release " + release.getCode() + " was changed by someone else; reload it.");
        }
    }

    private String checksum(ConfigRelease release) {
        ArrayNode forms = mapper.createArrayNode();
        release.getForms().stream()
                .sorted((a, b) -> a.getProduct().compareTo(b.getProduct()))
                .forEach(form -> forms.add(canonical(form)));
        return sha256(release.getCode() + "|" + release.getDescription() + "|" + forms);
    }

    private ObjectNode canonical(PolicyFormVersion form) {
        ObjectNode node = mapper.createObjectNode();
        node.put("product", form.getProduct());
        ArrayNode fields = node.putArray("fields");
        for (PolicyFieldDefinition f : form.getFields()) {
            ObjectNode field = fields.addObject();
            field.put("key", f.getKey());
            field.put("type", f.getType());
            field.put("label", f.getLabel());
            field.put("required", f.isRequired());
            field.put("sensitive", f.isSensitive());
            field.put("maxLength", f.getMaxLength());
            field.put("allowedValues", String.join(",", f.getAllowedValues()));
            field.put("helpText", f.getHelpText());
        }
        return node;
    }

    private String schemaVersion() {
        try {
            Object id = em.createNativeQuery("select ID from DATABASECHANGELOG order by ORDEREXECUTED desc")
                    .setMaxResults(1).getSingleResult();
            return id == null ? null : id.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ConfigRelease find(Long id) {
        ConfigRelease release = em.find(ConfigRelease.class, id);
        if (release == null) {
            throw new NotFoundException("Configuration release " + id + " not found");
        }
        return release;
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    /** Flushes first so the returned rowVersion is the one the next write must quote. */
    private ConfigurationViews.ReleaseView saved(ConfigRelease release) {
        em.flush();
        return view(release);
    }

    ConfigurationViews.ReleaseView view(ConfigRelease r) {
        return new ConfigurationViews.ReleaseView(r.getId(), r.getCode(), r.getDescription(), r.getStatus(),
                r.getChecksum(), r.getSchemaVersion(), r.getCreatedBy(), r.getCreatedAt(), r.getSubmittedBy(),
                r.getSubmittedAt(), r.getApprovedBy(), r.getApprovedAt(), r.getActivatedBy(), r.getActivatedAt(),
                r.getRejectionReason(), r.getRowVersion(), r.getForms().stream().map(ConfigurationService::form).toList());
    }

    static ConfigurationViews.FormView form(PolicyFormVersion f) {
        return new ConfigurationViews.FormView(f.getId(), f.getProduct(), f.getVersionNo(), f.getStatus(),
                f.getRelease().getCode(), f.getFields().stream()
                        .map(d -> new ConfigurationViews.FieldView(d.getKey(), d.getType(), d.getLabel(),
                                d.isRequired(), d.isSensitive(), d.getMaxLength(), d.getAllowedValues(),
                                d.getHelpText(), d.getDisplayOrder()))
                        .toList());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String required(JsonNode body, String field) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return null;
        }
        return body.get(field).asText().trim();
    }
}
