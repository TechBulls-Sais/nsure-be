package com.nsure.reinsurance;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SH07 Reinsurance: treaty drafting and four-eyes approval, and bordereaux. Cessions and recoveries are written by
 * {@link CessionEventHandler} from premium and claim events.
 */
@Service
public class ReinsuranceService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EntityManager em;
    private final PlatformSecurityContext security;

    public ReinsuranceService(EntityManager em, PlatformSecurityContext security) {
        this.em = em;
        this.security = security;
    }

    // ── treaties ──────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReinsuranceViews.TreatyView> treaties() {
        user("READ_REINSURANCE");
        Map<Long, Object[]> totals = new HashMap<>();
        for (Object[] row : em.createQuery("select c.treaty.id, count(c), sum(c.cededPremium) from Cession c"
                + " group by c.treaty.id", Object[].class).getResultList()) {
            totals.put((Long) row[0], row);
        }
        return em.createQuery("select t from Treaty t order by t.id desc", Treaty.class)
                .getResultList()
                .stream()
                .map(t -> {
                    Object[] total = totals.get(t.getId());
                    return view(t, total == null ? 0 : (Long) total[1],
                            total == null || total[2] == null ? BigDecimal.ZERO : (BigDecimal) total[2]);
                })
                .toList();
    }

    /**
     * Drafts a treaty: {name, reinsurer, treatyType QUOTA_SHARE|SURPLUS, products (list or comma separated),
     * cededPercent (quota share), retention (surplus), effectiveFrom, effectiveTo}. It cedes nothing until approved.
     */
    @Transactional
    public ReinsuranceViews.TreatyView draft(JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        String name = required(body, "name", 150);
        String reinsurer = required(body, "reinsurer", 150);
        String type = required(body, "treatyType", 20).toUpperCase(Locale.ROOT);
        BigDecimal percent = null;
        BigDecimal retention = null;
        if (Treaty.QUOTA_SHARE.equals(type)) {
            percent = number(body, "cededPercent");
            if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("A quota share cedes more than 0% and at most 100%.");
            }
            if (percent.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("cededPercent can have at most two decimal places.");
            }
            percent = percent.setScale(2);
        } else if (Treaty.SURPLUS.equals(type)) {
            retention = number(body, "retention");
            if (retention.signum() <= 0) {
                throw new IllegalArgumentException("A surplus treaty needs a retention above zero.");
            }
            if (retention.stripTrailingZeros().scale() > 2 || retention.compareTo(new BigDecimal("9999999999999")) > 0) {
                throw new IllegalArgumentException("retention must be an amount with at most two decimal places.");
            }
            retention = retention.setScale(2);
        } else {
            throw new IllegalArgumentException("Treaty type must be QUOTA_SHARE or SURPLUS.");
        }
        LocalDate from = date(body, "effectiveFrom");
        LocalDate to = date(body, "effectiveTo");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("The treaty must end after it starts.");
        }
        List<String> products = products(body);
        Treaty treaty = new Treaty(name, reinsurer, type, products, percent, retention, from, to, user.getUsername());
        em.persist(treaty);
        em.flush();
        treaty.numbered();
        return view(treaty, 0, BigDecimal.ZERO);
    }

    /**
     * Approves a draft. The user who drafted it cannot approve it (four eyes), except Super user and Super admin, who
     * may approve their own draft. Two approved treaties may not cover the same product on the same dates.
     */
    @Transactional
    public ReinsuranceViews.TreatyView approve(String ref) {
        AppUser user = user("APPROVE_TREATY");
        Treaty treaty = treaty(ref);
        if (!Treaty.DRAFT.equals(treaty.getStatus())) {
            throw new ConflictException("Treaty " + ref + " is already " + treaty.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getUsername().equalsIgnoreCase(treaty.getCreatedBy()) && !user.isSuperAdmin()) {
            throw new NoAuthorizationException("You drafted treaty " + ref + "; another user must approve it.");
        }
        for (Treaty other : em.createQuery("select t from Treaty t where t.status = :approved and t.id <> :id",
                        Treaty.class)
                .setParameter("approved", Treaty.APPROVED)
                .setParameter("id", treaty.getId())
                .getResultList()) {
            if (treaty.overlaps(other)) {
                throw new ConflictException("Treaty " + other.getRef() + " (" + other.getName()
                        + ") already covers " + String.join(", ", treaty.productList().stream()
                        .filter(other::covers).toList()) + " between " + other.getEffectiveFrom() + " and "
                        + other.getEffectiveTo() + ".");
            }
        }
        treaty.approve(user.getUsername());
        return view(treaty, 0, BigDecimal.ZERO);
    }

    // ── cessions, recoveries, bordereaux ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReinsuranceViews.CessionView> cessions(String treatyRef, String policyRef) {
        user("READ_REINSURANCE");
        return em.createQuery("select c from Cession c join fetch c.treaty t left join fetch c.bordereau"
                        + " where (:treaty is null or t.ref = :treaty) and (:policy is null or c.policyRef = :policy)"
                        + " order by c.id desc", Cession.class)
                .setParameter("treaty", blankToNull(treatyRef))
                .setParameter("policy", blankToNull(policyRef))
                .getResultList()
                .stream()
                .map(ReinsuranceService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReinsuranceViews.RecoveryView> recoveries() {
        user("READ_REINSURANCE");
        return em.createQuery("select r from Recovery r join fetch r.treaty order by r.id desc", Recovery.class)
                .getResultList()
                .stream()
                .map(ReinsuranceService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ReinsuranceViews.BordereauView> bordereaux() {
        user("READ_REINSURANCE");
        return em.createQuery("select b from Bordereau b join fetch b.treaty order by b.id desc", Bordereau.class)
                .getResultList()
                .stream()
                .map(ReinsuranceService::view)
                .toList();
    }

    /**
     * Produces the bordereau for {treatyRef, period yyyy-MM}: every cession of that treaty created in the month and
     * not yet reported, with its count and control totals. Asking again for the same month returns the same one.
     */
    @Transactional
    public ReinsuranceViews.BordereauView bordereau(JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Treaty treaty = treaty(required(body, "treatyRef", 20));
        String period = required(body, "period", 7);
        YearMonth month;
        try {
            month = YearMonth.parse(period);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("period must be a month (yyyy-MM).");
        }
        List<Bordereau> existing = em.createQuery("select b from Bordereau b join fetch b.treaty"
                        + " where b.treaty = :treaty and b.period = :period", Bordereau.class)
                .setParameter("treaty", treaty)
                .setParameter("period", month.toString())
                .getResultList();
        if (!existing.isEmpty()) {
            return view(existing.get(0));
        }
        if (!Treaty.APPROVED.equals(treaty.getStatus())) {
            throw new IllegalArgumentException("Treaty " + treaty.getRef() + " is not approved and has no cessions to report.");
        }
        if (month.isAfter(YearMonth.now())) {
            throw new IllegalArgumentException("A bordereau cannot be produced for a future month.");
        }
        List<Cession> cessions = em.createQuery("select c from Cession c where c.treaty = :treaty"
                        + " and c.bordereau is null and c.createdAt >= :from and c.createdAt < :to order by c.id asc",
                        Cession.class)
                .setParameter("treaty", treaty)
                .setParameter("from", month.atDay(1).atStartOfDay())
                .setParameter("to", month.plusMonths(1).atDay(1).atStartOfDay())
                .getResultList();
        BigDecimal premium = cessions.stream().map(Cession::getCededPremium).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum = cessions.stream().map(Cession::getCededSum).reduce(BigDecimal.ZERO, BigDecimal::add);
        Bordereau bordereau = new Bordereau(treaty, month.toString(), cessions.size(), premium, sum,
                user.getUsername());
        em.persist(bordereau);
        em.flush();
        bordereau.numbered();
        cessions.forEach(c -> c.reportedOn(bordereau));
        return view(bordereau);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private Treaty treaty(String ref) {
        return em.createQuery("select t from Treaty t where t.ref = :ref", Treaty.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Treaty " + ref + " not found"));
    }

    private List<String> products(JsonNode body) {
        List<String> raw = new ArrayList<>();
        JsonNode node = body.get("products");
        if (node != null && node.isArray()) {
            node.forEach(p -> raw.add(p.asText()));
        } else if (node != null && !node.isNull()) {
            raw.addAll(List.of(node.asText().split(",")));
        }
        Set<String> known = new LinkedHashSet<>(em.createQuery("select p.name from InsuranceProduct p", String.class)
                .getResultList());
        Set<String> products = new LinkedHashSet<>();
        for (String value : raw) {
            String product = value.trim();
            if (product.isEmpty()) {
                continue;
            }
            String match = known.stream().filter(k -> k.equalsIgnoreCase(product)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown product " + product + "."));
            products.add(match);
        }
        if (products.isEmpty()) {
            throw new IllegalArgumentException("A treaty must cover at least one product.");
        }
        if (String.join(",", products).length() > 500) {
            throw new IllegalArgumentException("Too many products for one treaty.");
        }
        return List.copyOf(products);
    }

    private static ReinsuranceViews.TreatyView view(Treaty t, long cessions, BigDecimal ceded) {
        return new ReinsuranceViews.TreatyView(t.getRef(), t.getName(), t.getReinsurer(), t.getTreatyType(),
                t.productList(), t.getCededPercent(), t.getRetention(), t.getEffectiveFrom(), t.getEffectiveTo(),
                t.getStatus(), Treaty.APPROVED.equals(t.getStatus()) && t.inForceOn(LocalDate.now()), t.getCreatedBy(),
                t.getCreatedAt(), t.getApprovedBy(), t.getApprovedAt(), cessions, ceded);
    }

    private static ReinsuranceViews.CessionView view(Cession c) {
        return new ReinsuranceViews.CessionView(c.getRef(), c.getTreaty().getRef(), c.getTreaty().getName(),
                c.getPolicyRef(), c.getProduct(), c.getSumAssured(), c.getCededSum(), c.getPremium(),
                c.getCededPremium(), c.getCededShare(), c.getBordereau() == null ? null : c.getBordereau().getRef(),
                c.getSourceEventId(), c.getCreatedAt());
    }

    private static ReinsuranceViews.RecoveryView view(Recovery r) {
        return new ReinsuranceViews.RecoveryView(r.getRef(), r.getTreaty().getRef(), r.getTreaty().getName(),
                r.getTreaty().getReinsurer(), r.getClaimRef(), r.getPolicyRef(), r.getClaimAmount(), r.getCededShare(),
                r.getRecoveryAmount(), r.getStatus(), r.getCreatedAt());
    }

    private static ReinsuranceViews.BordereauView view(Bordereau b) {
        return new ReinsuranceViews.BordereauView(b.getRef(), b.getTreaty().getRef(), b.getTreaty().getName(),
                b.getTreaty().getReinsurer(), b.getPeriod(), b.getRecordCount(), b.getTotalCededPremium(),
                b.getTotalCededSum(), b.getStatus(), b.getCreatedBy(), b.getCreatedAt());
    }

    private static String required(JsonNode body, String field, int max) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        String value = body.get(field).asText().trim();
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters.");
        }
        return value;
    }

    private static BigDecimal number(JsonNode body, String field) {
        String value = required(body, field, 20);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " must be a number.");
        }
    }

    private static LocalDate date(JsonNode body, String field) {
        try {
            return LocalDate.parse(required(body, field, 10));
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a date (yyyy-MM-dd).");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
