package com.nsure.portfolio;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioned product terms (NS-SH02-003/004, NS-IL03-001, AT-SH02-02). New business takes the approved version in force
 * on its issue date and keeps it: waiting period, grace period, beneficiary limit and wording are read from the
 * policy's own version, never from whatever the product says today. A change is proposed with a reason, approved by
 * someone else, and applies to new business from its effective date; it never reaches policies already issued.
 */
@Service
public class ProductTerms {

    public record VersionView(Long id, String product, int version, LocalDate effectiveFrom, LocalDate effectiveTo,
            int waitingPeriodDays, int gracePeriodDays, int maxBeneficiaries, String wordingRef, String approvalStatus,
            boolean inForce, String changeReason, String proposedBy, LocalDateTime recordedAt, String approvedBy,
            LocalDateTime approvedAt, String rejectionReason, long policies) {
    }

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final EvidenceChain evidence;
    private final Outbox outbox;

    public ProductTerms(EntityManager em, PlatformSecurityContext security, EvidenceChain evidence, Outbox outbox) {
        this.em = em;
        this.security = security;
        this.evidence = evidence;
        this.outbox = outbox;
    }

    // ── resolving ─────────────────────────────────────────────────────────────────────────────────

    /** The approved version that new business on {@code date} is sold under. */
    public ProductVersion inForce(String product, LocalDate date) {
        return em.createQuery("select v from ProductVersion v where v.product = :product and v.approvalStatus = :approved"
                        + " and v.effectiveFrom <= :date order by v.effectiveFrom desc, v.versionNo desc",
                        ProductVersion.class)
                .setParameter("product", product)
                .setParameter("approved", ProductVersion.APPROVED)
                .setParameter("date", date)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Product " + product + " has no approved terms in force on "
                        + date + "."));
    }

    /**
     * The terms a policy was issued under. A policy that predates pinning (none should remain after migration 066)
     * falls back to the version in force on its cover start.
     */
    public ProductVersion of(InsurancePolicy policy) {
        if (policy.getProductVersionId() != null) {
            ProductVersion pinned = em.find(ProductVersion.class, policy.getProductVersionId());
            if (pinned != null) {
                return pinned;
            }
        }
        LocalDate on = policy.getCoverStartDate() != null ? policy.getCoverStartDate() : LocalDate.now();
        return inForce(policy.getProduct(), on);
    }

    /** Pins a new policy to the terms in force today. Called once, when the policy is first opened. */
    void pin(InsurancePolicy policy) {
        if (policy.getProductVersionId() == null) {
            policy.pinProductVersion(inForce(policy.getProduct(), LocalDate.now()).getId());
        }
    }

    // ── reading ───────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<VersionView> versions(String product) {
        security.authenticatedUser().validateHasPermissionTo("READ_PRODUCT");
        requireProduct(product);
        return views(all(product));
    }

    @Transactional(readOnly = true)
    public VersionView policyTerms(String policyRef) {
        security.authenticatedUser().validateHasPermissionTo("READ_POLICY");
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", policyRef).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Policy " + policyRef + " not found"));
        ProductVersion terms = of(policy);
        return views(all(terms.getProduct())).stream().filter(v -> v.id().equals(terms.getId())).findFirst().orElseThrow();
    }

    // ── changing ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Proposes the next version. Unset values carry over from the version it replaces. It cannot start in the past
     * (policies issued since then are already pinned to the earlier terms) or before an approved version starts.
     */
    @Transactional
    public VersionView propose(String product, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UPDATE_PRODUCT");
        requireProduct(product);
        String reason = required(body, "reason", 500);
        LocalDate effective = date(body, "effectiveFrom", LocalDate.now());
        List<ProductVersion> versions = all(product);
        versions.stream().filter(ProductVersion::isPending).findFirst().ifPresent(pending -> {
            throw new ConflictException(pending.getLabel() + " is awaiting approval. Approve or reject it before"
                    + " proposing another change.");
        });
        int latest = versions.isEmpty() ? 0 : versions.get(0).getVersionNo();
        if (body.hasNonNull("baseVersion") && body.get("baseVersion").asInt() != latest) {
            throw new ConflictException(product + " changed to v" + latest + " while you were editing v"
                    + body.get("baseVersion").asInt() + ". Reload to see the current terms.");
        }
        requireValidStart(product, effective);
        ProductVersion base = inForce(product, effective);
        ProductVersion next = new ProductVersion(product, latest + 1, effective,
                bounded(body, "waitingPeriodDays", base.getWaitingPeriodDays(), 0, 3650),
                bounded(body, "gracePeriodDays", base.getGracePeriodDays(), 0, 365),
                bounded(body, "maxBeneficiaries", base.getMaxBeneficiaries(), 1, 20),
                body.hasNonNull("wordingRef") ? required(body, "wordingRef", 120) : base.getWordingRef(),
                reason, user.getUsername());
        em.persist(next);
        evidence.record(user.getUsername(), "Proposed product terms", "ProductVersion", next.getLabel(),
                base.getLabel() + " (" + base.summary() + ") → " + next.summary() + ", for new business from "
                        + effective + ". " + reason);
        em.flush();
        return view(next);
    }

    @Transactional
    public VersionView approve(String product, int version) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("ACTIVATE_PRODUCT");
        ProductVersion terms = pending(product, version, user);
        if (terms.getEffectiveFrom().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException(terms.getLabel() + " was to start on " + terms.getEffectiveFrom()
                    + ", which has passed; policies issued since keep the earlier terms. Reject it and propose a"
                    + " version starting today or later.");
        }
        terms.approve(user.getUsername());
        evidence.record(user.getUsername(), "Approved product terms", "ProductVersion", terms.getLabel(),
                terms.summary() + ", for new business from " + terms.getEffectiveFrom() + ", proposed by "
                        + terms.getProposedBy());
        // SH02: ProductVersionApproved, in the approval's transaction (references only).
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", terms.getProduct());
        data.put("version", terms.getVersionNo());
        data.put("effectiveFrom", terms.getEffectiveFrom().toString());
        data.put("proposedBy", terms.getProposedBy());
        data.put("approvedBy", user.getUsername());
        data.put("source", "ProductTerms");
        outbox.publish(Events.PRODUCT_VERSION_APPROVED, terms.getProduct(), data, user.getUsername());
        em.flush();
        return view(terms);
    }

    @Transactional
    public VersionView reject(String product, int version, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("ACTIVATE_PRODUCT");
        ProductVersion terms = pending(product, version, user);
        String reason = required(body, "reason", 500);
        terms.reject(user.getUsername(), reason);
        evidence.record(user.getUsername(), "Rejected product terms", "ProductVersion", terms.getLabel(), reason);
        em.flush();
        return view(terms);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private ProductVersion pending(String product, int version, AppUser checker) {
        ProductVersion terms = all(product).stream().filter(v -> v.getVersionNo() == version).findFirst()
                .orElseThrow(() -> new NotFoundException(product + " v" + version + " not found"));
        if (!terms.isPending()) {
            throw new ConflictException(terms.getLabel() + " is "
                    + terms.getApprovalStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
        }
        if (checker.getUsername().equalsIgnoreCase(terms.getProposedBy())) {
            throw new NoAuthorizationException("You proposed " + terms.getLabel()
                    + ", so another user must approve or reject it (maker-checker).");
        }
        return terms;
    }

    private void requireValidStart(String product, LocalDate effective) {
        if (effective.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("New terms cannot start in the past: policies issued since then are"
                    + " already pinned to the terms they were sold under.");
        }
        LocalDate latestApproved = all(product).stream().filter(ProductVersion::isApproved)
                .map(ProductVersion::getEffectiveFrom).max(LocalDate::compareTo).orElse(null);
        if (latestApproved != null && effective.isBefore(latestApproved)) {
            throw new IllegalArgumentException("New terms for " + product + " cannot start before " + latestApproved
                    + ", when the latest approved version starts.");
        }
    }

    private List<ProductVersion> all(String product) {
        return em.createQuery("select v from ProductVersion v where v.product = :product order by v.versionNo desc",
                ProductVersion.class).setParameter("product", product).getResultList();
    }

    private void requireProduct(String product) {
        Long found = em.createQuery("select count(p) from InsuranceProduct p where p.name = :name", Long.class)
                .setParameter("name", product).getSingleResult();
        if (found == 0) {
            throw new NotFoundException("Product " + product + " not found");
        }
    }

    private VersionView view(ProductVersion terms) {
        return views(all(terms.getProduct())).stream().filter(v -> v.id().equals(terms.getId())).findFirst().orElseThrow();
    }

    /** Views newest first, each approved version with its last day of new business (inclusive). */
    private List<VersionView> views(List<ProductVersion> versions) {
        LocalDate today = LocalDate.now();
        List<ProductVersion> approved = versions.stream().filter(ProductVersion::isApproved).toList();
        Long inForce = approved.stream().filter(v -> !v.getEffectiveFrom().isAfter(today))
                .max(Comparator.comparing(ProductVersion::getEffectiveFrom).thenComparingInt(ProductVersion::getVersionNo))
                .map(ProductVersion::getId).orElse(null);
        List<VersionView> views = new ArrayList<>();
        for (ProductVersion v : versions) {
            LocalDate to = !v.isApproved() ? null : approved.stream()
                    .filter(n -> n.getEffectiveFrom().isAfter(v.getEffectiveFrom())
                            || (n.getEffectiveFrom().equals(v.getEffectiveFrom()) && n.getVersionNo() > v.getVersionNo()))
                    .map(n -> n.getEffectiveFrom().minusDays(1)).min(LocalDate::compareTo).orElse(null);
            long policies = em.createQuery("select count(p) from InsurancePolicy p where p.productVersionId = :id",
                    Long.class).setParameter("id", v.getId()).getSingleResult();
            views.add(new VersionView(v.getId(), v.getProduct(), v.getVersionNo(), v.getEffectiveFrom(), to,
                    v.getWaitingPeriodDays(), v.getGracePeriodDays(), v.getMaxBeneficiaries(), v.getWordingRef(),
                    v.getApprovalStatus(), v.getId().equals(inForce), v.getChangeReason(), v.getProposedBy(),
                    v.getRecordedAt(), v.getApprovedBy(), v.getApprovedAt(), v.getRejectionReason(), policies));
        }
        return views;
    }

    private static int bounded(JsonNode body, String field, int fallback, int min, int max) {
        if (body == null || !body.hasNonNull(field)) {
            return fallback;
        }
        if (!body.get(field).canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be a whole number.");
        }
        int value = body.get(field).asInt();
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max + ".");
        }
        return value;
    }

    private static String required(JsonNode body, String field, int max) {
        String value = body == null || !body.hasNonNull(field) ? null : body.get(field).asText().trim();
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters.");
        }
        return value;
    }

    private static LocalDate date(JsonNode body, String field, LocalDate fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(body.get(field).asText().trim());
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a date (yyyy-MM-dd).");
        }
    }
}
