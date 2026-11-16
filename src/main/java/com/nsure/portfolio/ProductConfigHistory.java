package com.nsure.portfolio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.security.exception.NoAuthorizationException;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * History and maker-checker for product configurations (NS-SH02-003, NS-DAT-004). Each save keeps an immutable
 * snapshot; nothing earlier is lost. Making a configuration Active is a separate act by someone other than the person
 * who last changed its content, and it cannot carry content changes of its own.
 */
@Service
public class ProductConfigHistory {

    public record VersionView(int version, String status, String savedBy, LocalDateTime savedAt, JsonNode snapshot) {
    }

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final EvidenceChain evidence;

    public ProductConfigHistory(EntityManager em, ObjectMapper mapper, EvidenceChain evidence) {
        this.em = em;
        this.mapper = mapper;
        this.evidence = evidence;
    }

    /** The configuration's content, without its status, version or display order. */
    ObjectNode content(ProductConfiguration row) {
        ObjectNode node = mapper.createObjectNode();
        node.put("name", row.getName());
        node.put("code", row.getCode());
        node.put("domain", row.getDomain());
        node.put("currency", row.getCurrency());
        node.put("description", row.getDescription());
        node.put("effective", row.getEffectiveOn());
        node.put("minAge", row.getMinAge());
        node.put("maxAge", row.getMaxAge());
        node.put("waitingMonths", row.getWaitingMonths());
        node.put("cover", row.getCoverAmount());
        node.put("premium", row.getPremiumAmount());
        node.put("frequency", row.getFrequency());
        node.put("graceDays", row.getGraceDays());
        node.put("underwriting", row.getUnderwriting());
        node.put("benefits", row.getBenefits());
        node.put("channels", row.getChannels());
        node.put("wording", row.getWording());
        return node;
    }

    /**
     * Refuses an activation that is not independent: content changed in the same request, or the user activating is
     * the one who made the content as it stands.
     */
    void requireIndependentActivation(String ref, JsonNode before, JsonNode after, String actor) {
        if (ref == null || before == null) {
            throw new NoAuthorizationException("A new configuration cannot be created Active. Save it for review;"
                    + " another user activates it.");
        }
        if (!before.equals(after)) {
            throw new IllegalArgumentException("Activation cannot include changes. Save the changes for review first;"
                    + " another user then activates the configuration as reviewed.");
        }
        String author = contentAuthor(ref, hash(before));
        if (author != null && author.equalsIgnoreCase(actor)) {
            throw new NoAuthorizationException("You made the current version of " + ref
                    + ", so another user must activate it (maker-checker).");
        }
    }

    /** Keeps the saved state, and evidence of what changed. */
    void record(ProductConfiguration row, JsonNode before, String actor) {
        ObjectNode after = content(row);
        ObjectNode snapshot = after.deepCopy();
        snapshot.put("status", row.getStatus());
        em.persist(new ProductConfigVersion(row.getRef(), row.getVersionNo(), write(snapshot), hash(after),
                row.getStatus(), actor));
        evidence.record(actor, before == null ? "Created product configuration" : "Saved product configuration",
                "ProductConfiguration", row.getRef() + " v" + row.getVersionNo(),
                "status " + row.getStatus() + (before == null ? "" : "; " + changes(before, after)));
    }

    @Transactional(readOnly = true)
    public List<VersionView> versions(String ref) {
        List<VersionView> views = new ArrayList<>();
        for (ProductConfigVersion v : history(ref)) {
            views.add(new VersionView(v.getVersionNo(), v.getStatus(), v.getSavedBy(), v.getSavedAt(), read(v.getSnapshot())));
        }
        return views;
    }

    /** Who made the content that has hash {@code hash}: the earliest save in the latest unbroken run with it. */
    private String contentAuthor(String ref, String hash) {
        String author = null;
        for (ProductConfigVersion v : history(ref)) {
            if (!v.getContentHash().equals(hash)) {
                break;
            }
            author = v.getSavedBy();
        }
        return author;
    }

    private List<ProductConfigVersion> history(String ref) {
        return em.createQuery("select v from ProductConfigVersion v where v.configRef = :ref order by v.versionNo desc,"
                + " v.id desc", ProductConfigVersion.class).setParameter("ref", ref).getResultList();
    }

    private static String changes(JsonNode before, JsonNode after) {
        List<String> changed = new ArrayList<>();
        Iterator<String> fields = after.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!before.path(field).equals(after.path(field))) {
                changed.add(field + " " + before.path(field).asText() + " → " + after.path(field).asText());
            }
        }
        return changed.isEmpty() ? "no content change" : String.join("; ", changed);
    }

    private String write(JsonNode node) {
        try {
            String json = mapper.writeValueAsString(node);
            return json.length() <= 4000 ? json : json.substring(0, 4000);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return mapper.createObjectNode().put("raw", json);
        }
    }

    private static String hash(JsonNode content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
