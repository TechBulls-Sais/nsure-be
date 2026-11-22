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
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approved migration of in-force policies to newer product terms (NS-SH02-004). New business takes the version in
 * force on its date and every policy keeps the version it was issued under; only a migration proposed with a reason
 * and approved by a second person moves in-force policies (all of the product's, or those on one older version) to
 * the version in force today. Each moved policy's history records the change.
 */
@Service
public class ProductTermsMigration {

    /** States in which a policy's terms still matter going forward. */
    static final List<String> IN_FORCE = List.of(PolicyWorkflow.ACTIVE, PolicyWorkflow.ACTIVE_NO_COVER,
            PolicyWorkflow.PENDING_PAYMENT, PolicyWorkflow.LAPSED, PolicyWorkflow.REINSTATEMENT_PENDING);

    @Entity(name = "ProductTermsMigrationRecord")
    @Table(name = "ins_terms_migration")
    public static class Migration {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        Long id;
        @Column(name = "product_name", nullable = false, length = 100, updatable = false)
        String product;
        @Column(name = "from_version", updatable = false)
        Integer fromVersion;
        @Column(name = "to_version", nullable = false, updatable = false)
        int toVersion;
        @Column(nullable = false, length = 500, updatable = false)
        String reason;
        @Column(nullable = false, length = 20)
        String status;
        @Column(name = "policies_expected", nullable = false)
        int policiesExpected;
        @Column(name = "policies_moved")
        Integer policiesMoved;
        @Column(name = "proposed_by", nullable = false, length = 100, updatable = false)
        String proposedBy;
        @Column(name = "proposed_at", nullable = false, updatable = false)
        LocalDateTime proposedAt;
        @Column(name = "decided_by", length = 100)
        String decidedBy;
        @Column(name = "decided_at")
        LocalDateTime decidedAt;
        @Column(name = "rejection_reason", length = 500)
        String rejectionReason;
    }

    public record MigrationView(Long id, String product, Integer fromVersion, int toVersion, String reason,
            String status, int policiesExpected, Integer policiesMoved, String proposedBy, LocalDateTime proposedAt,
            String decidedBy, LocalDateTime decidedAt, String rejectionReason) {
    }

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final WorkflowHistory history;
    private final EvidenceChain evidence;
    private final Outbox outbox;

    public ProductTermsMigration(EntityManager em, PlatformSecurityContext security, WorkflowHistory history,
            EvidenceChain evidence, Outbox outbox) {
        this.em = em;
        this.security = security;
        this.history = history;
        this.evidence = evidence;
        this.outbox = outbox;
    }

    @Transactional(readOnly = true)
    public List<MigrationView> migrations(String product) {
        user("READ_PRODUCT");
        return em.createQuery("select m from ProductTermsMigrationRecord m where m.product = :p order by m.id desc",
                Migration.class).setParameter("p", product).getResultList().stream().map(this::view).toList();
    }

    /** {toVersion, fromVersion?, reason}: proposes moving in-force policies to the version in force today. */
    @Transactional
    public MigrationView propose(String product, JsonNode body) {
        AppUser user = user("UPDATE_PRODUCT");
        int to = body.path("toVersion").asInt(0);
        Integer from = body.hasNonNull("fromVersion") ? body.get("fromVersion").asInt() : null;
        String reason = body.path("reason").asText("").trim();
        if (reason.length() < 10) {
            throw new IllegalArgumentException("Give the reason for moving in-force policies (at least 10 characters):"
                    + " issued contracts keep their terms unless an approved change says otherwise.");
        }
        ProductVersion target = version(product, to);
        ProductVersion inForce = inForce(product);
        if (!target.isApproved() || inForce == null || !inForce.getId().equals(target.getId())) {
            throw new IllegalArgumentException(product + " v" + to + " is not the approved version in force today;"
                    + " in-force policies can only move to " + (inForce == null ? "an approved version" : inForce.getLabel())
                    + ".");
        }
        if (from != null && from == to) {
            throw new IllegalArgumentException("Choose an older version to move policies from.");
        }
        if (!em.createQuery("select m.id from ProductTermsMigrationRecord m where m.product = :p and m.status ="
                + " 'PROPOSED'", Long.class).setParameter("p", product).getResultList().isEmpty()) {
            throw new ConflictException("A terms migration for " + product + " is already awaiting approval.");
        }
        List<InsurancePolicy> affected = affected(product, target, from);
        if (affected.isEmpty()) {
            throw new IllegalArgumentException("No in-force " + product + " policy is on "
                    + (from == null ? "an older version" : "v" + from) + ".");
        }
        Migration m = new Migration();
        m.product = product;
        m.fromVersion = from;
        m.toVersion = to;
        m.reason = reason.length() <= 500 ? reason : reason.substring(0, 500);
        m.status = "PROPOSED";
        m.policiesExpected = affected.size();
        m.proposedBy = user.getUsername();
        m.proposedAt = LocalDateTime.now();
        em.persist(m);
        evidence.record(user.getUsername(), "Proposed terms migration", "ProductVersion", target.getLabel(),
                affected.size() + " in-force policies " + (from == null ? "" : "on v" + from + " ") + "→ v" + to + ": "
                        + reason);
        return view(m);
    }

    /** A second person approves (the policies move now) or rejects with a reason. */
    @Transactional
    public MigrationView decide(String product, long id, boolean approve, String rejection) {
        AppUser user = user("ACTIVATE_PRODUCT");
        Migration m = em.find(Migration.class, id);
        if (m == null || !m.product.equals(product)) {
            throw new NotFoundException("Terms migration " + id + " not found for " + product);
        }
        if (!"PROPOSED".equals(m.status)) {
            throw new ConflictException("Terms migration " + id + " is " + m.status.toLowerCase() + ".");
        }
        if (user.getUsername().equalsIgnoreCase(m.proposedBy)) {
            throw new NoAuthorizationException("You proposed this migration, so another user must decide it.");
        }
        m.decidedBy = user.getUsername();
        m.decidedAt = LocalDateTime.now();
        if (!approve) {
            if (rejection == null || rejection.isBlank()) {
                throw new IllegalArgumentException("Give the reason for rejecting the migration.");
            }
            m.status = "REJECTED";
            m.rejectionReason = rejection.length() <= 500 ? rejection : rejection.substring(0, 500);
            evidence.record(user.getUsername(), "Rejected terms migration", "ProductVersion", product + " v"
                    + m.toVersion, rejection);
            return view(m);
        }
        ProductVersion target = version(product, m.toVersion);
        ProductVersion inForce = inForce(product);
        if (inForce == null || !inForce.getId().equals(target.getId())) {
            throw new IllegalArgumentException(target.getLabel() + " is no longer the version in force; reject this"
                    + " migration and propose one to " + (inForce == null ? "the current version" : inForce.getLabel()) + ".");
        }
        int moved = 0;
        for (InsurancePolicy p : affected(product, target, m.fromVersion)) {
            ProductVersion old = p.getProductVersionId() == null ? null : em.find(ProductVersion.class, p.getProductVersionId());
            p.migrateProductVersion(target.getId());
            history.record(WorkflowHistory.POLICY, p.getRef(), p.getCustomerName(), "Terms Migrated", p.getStatus(),
                    p.getStatus(), (old == null ? "Unpinned" : old.getLabel()) + " → " + target.getLabel() + ": "
                            + m.reason + " (proposed by " + m.proposedBy + ", approved by " + user.getUsername() + ")",
                    user.getUsername());
            moved++;
        }
        m.status = "APPLIED";
        m.policiesMoved = moved;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("product", product);
        data.put("toVersion", m.toVersion);
        data.put("fromVersion", m.fromVersion == null ? "" : String.valueOf(m.fromVersion));
        data.put("policies", moved);
        data.put("approvedBy", user.getUsername());
        outbox.publish(Events.PRODUCT_TERMS_MIGRATED, product, data, user.getUsername());
        evidence.record(user.getUsername(), "Applied terms migration", "ProductVersion", target.getLabel(),
                moved + " in-force policies moved: " + m.reason);
        return view(m);
    }

    private List<InsurancePolicy> affected(String product, ProductVersion target, Integer from) {
        if (from != null) {
            return em.createQuery("select p from InsurancePolicy p where p.product = :product and p.status in (:states)"
                            + " and p.productVersionId = :from order by p.id", InsurancePolicy.class)
                    .setParameter("product", product).setParameter("states", IN_FORCE)
                    .setParameter("from", version(product, from).getId()).getResultList();
        }
        return em.createQuery("select p from InsurancePolicy p where p.product = :product and p.status in (:states)"
                        + " and (p.productVersionId is null or p.productVersionId <> :target) order by p.id",
                        InsurancePolicy.class)
                .setParameter("product", product).setParameter("states", IN_FORCE)
                .setParameter("target", target.getId()).getResultList();
    }

    private ProductVersion version(String product, int number) {
        return em.createQuery("select v from ProductVersion v where v.product = :p and v.versionNo = :n",
                        ProductVersion.class).setParameter("p", product).setParameter("n", number)
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException(product + " v" + number + " not found"));
    }

    private ProductVersion inForce(String product) {
        return em.createQuery("select v from ProductVersion v where v.product = :p and v.approvalStatus = :a and"
                        + " v.effectiveFrom <= :d order by v.effectiveFrom desc, v.versionNo desc", ProductVersion.class)
                .setParameter("p", product).setParameter("a", ProductVersion.APPROVED)
                .setParameter("d", LocalDate.now()).setMaxResults(1).getResultStream().findFirst().orElse(null);
    }

    private MigrationView view(Migration m) {
        return new MigrationView(m.id, m.product, m.fromVersion, m.toVersion, m.reason, m.status, m.policiesExpected,
                m.policiesMoved, m.proposedBy, m.proposedAt, m.decidedBy, m.decidedAt, m.rejectionReason);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }
}
