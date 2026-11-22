package com.nsure.reinsurance;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.InvalidFieldException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
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
    private final Outbox outbox;
    private final int overdueDays;

    public ReinsuranceService(EntityManager em, PlatformSecurityContext security, Outbox outbox,
            @org.springframework.beans.factory.annotation.Value("${nsure.reinsurance.recovery-overdue-days:60}")
            int overdueDays) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.overdueDays = overdueDays;
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
        return em.createQuery("select t from Treaty t left join fetch t.amends order by t.id desc", Treaty.class)
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
        } else if (Treaty.EXCESS_OF_LOSS.equals(type)) {
            // NS-SH07-001: a distinct non-proportional model: priority and limit per claim, no premium share.
            if (!body.hasNonNull("xlPriority") || !body.hasNonNull("xlLimit")) {
                throw new InvalidFieldException("xlPriority", "An excess-of-loss cover needs a priority and a limit per claim.");
            }
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
            throw new IllegalArgumentException("Treaty type must be QUOTA_SHARE, SURPLUS or EXCESS_OF_LOSS.");
        }
        LocalDate from = date(body, "effectiveFrom");
        LocalDate to = date(body, "effectiveTo");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("The treaty must end after it starts.");
        }
        List<String> products = products(body);
        List<Object[]> participants = participants(body, reinsurer);
        Treaty treaty = new Treaty(name, (String) participants.get(0)[0], type, products, percent, retention, from, to,
                user.getUsername());
        applyTerms(treaty, body);
        if (body.hasNonNull("capacity") && !body.get("capacity").asText().isBlank()) {
            BigDecimal capacity = number(body, "capacity");
            if (capacity.signum() <= 0 || capacity.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("capacity must be an amount above zero with at most two decimals.");
            }
            treaty.capacity(capacity.setScale(2));
        }
        em.persist(treaty);
        em.flush();
        treaty.numbered();
        saveParticipants(treaty, participants);
        return view(treaty, 0, BigDecimal.ZERO);
    }

    /**
     * NS-SH07-001: amends an approved treaty as its next version — {reason, cededPercent?, retention?, capacity?,
     * commissionPercent?, premiumBasis?, reportingFrequency?, bordereauFormat?, xlPriority?, xlLimit?, participants?,
     * effectiveFrom, effectiveTo?}. The draft cedes nothing until someone else approves it; then the amended version
     * stops the day before, keeping every cession already made under it.
     */
    @Transactional
    public ReinsuranceViews.TreatyView amend(String ref, JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Treaty original = treaty(ref);
        if (!Treaty.APPROVED.equals(original.getStatus()) || !original.isAutomatic()) {
            throw new ConflictException("Only an approved automatic treaty can be amended; " + ref + " is "
                    + original.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (!em.createQuery("select t.id from Treaty t where t.amends = :o and t.status = :draft", Long.class)
                .setParameter("o", original).setParameter("draft", Treaty.DRAFT).setMaxResults(1).getResultList()
                .isEmpty()) {
            throw new ConflictException("An amendment of " + ref + " is already waiting for approval.");
        }
        String reason = required(body, "reason", 500);
        LocalDate from = date(body, "effectiveFrom");
        LocalDate to = body.hasNonNull("effectiveTo") ? date(body, "effectiveTo") : original.getEffectiveTo();
        if (!from.isAfter(original.getEffectiveFrom()) || !from.isBefore(to)) {
            throw new IllegalArgumentException("An amendment starts after " + original.getEffectiveFrom()
                    + " and before it ends.");
        }
        BigDecimal percent = original.getCededPercent();
        BigDecimal retention = original.getRetention();
        if (Treaty.QUOTA_SHARE.equals(original.getTreatyType()) && body.hasNonNull("cededPercent")) {
            percent = number(body, "cededPercent");
            if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0 || percent.stripTrailingZeros().scale() > 2) {
                throw new IllegalArgumentException("A quota share cedes more than 0% and at most 100%.");
            }
            percent = percent.setScale(2);
        }
        if (Treaty.SURPLUS.equals(original.getTreatyType()) && body.hasNonNull("retention")) {
            retention = number(body, "retention").setScale(2, java.math.RoundingMode.HALF_UP);
            if (retention.signum() <= 0) {
                throw new IllegalArgumentException("A surplus treaty needs a retention above zero.");
            }
        }
        List<Object[]> participants = body.has("participants") ? participants(body, original.getReinsurer())
                : em.createQuery("select p from TreatyParticipant p where p.treaty = :t order by p.id",
                        TreatyParticipant.class).setParameter("t", original).getResultStream()
                .map(p -> new Object[] {p.getReinsurer(), p.getSharePercent(), p.isLead()}).toList();
        if (participants.isEmpty()) {
            participants = List.<Object[]>of(new Object[] {original.getReinsurer(), HUNDRED.setScale(2), true});
        }
        Treaty amendment = new Treaty(original.getName(), (String) participants.get(0)[0], original.getTreatyType(),
                original.productList(), percent, retention, from, to, user.getUsername());
        amendment.amendmentOf(original, reason);
        amendment.capacity(body.hasNonNull("capacity") ? number(body, "capacity").setScale(2, java.math.RoundingMode.HALF_UP)
                : original.getCapacity());
        amendment.terms(original.getCommissionPercent(), original.getPremiumBasis(), original.getReportingFrequency(),
                original.getBordereauFormat(), original.getXlPriority(), original.getXlLimit());
        applyTerms(amendment, body);
        em.persist(amendment);
        em.flush();
        amendment.numbered();
        saveParticipants(amendment, participants);
        return view(amendment, 0, BigDecimal.ZERO);
    }

    /** Commission, premium basis, reporting terms and the excess-of-loss priority and limit the body carries. */
    private void applyTerms(Treaty treaty, JsonNode body) {
        BigDecimal commission = body.hasNonNull("commissionPercent") && !body.get("commissionPercent").asText().isBlank()
                ? number(body, "commissionPercent") : treaty.getCommissionPercent();
        if (commission != null && (commission.signum() < 0 || commission.compareTo(HUNDRED) > 0)) {
            throw new IllegalArgumentException("commissionPercent must be between 0 and 100.");
        }
        String basis = choice(body, "premiumBasis", Set.of("ORIGINAL_TERMS", "RISK_PREMIUM"), treaty.getPremiumBasis());
        String frequency = choice(body, "reportingFrequency", Set.of("MONTHLY", "QUARTERLY"), treaty.getReportingFrequency());
        String format = choice(body, "bordereauFormat", Set.of("CSV", "JSON"), treaty.getBordereauFormat());
        BigDecimal priority = treaty.getXlPriority();
        BigDecimal limit = treaty.getXlLimit();
        if (Treaty.EXCESS_OF_LOSS.equals(treaty.getTreatyType())) {
            if (body.hasNonNull("xlPriority")) {
                priority = number(body, "xlPriority").setScale(2, java.math.RoundingMode.HALF_UP);
            }
            if (body.hasNonNull("xlLimit")) {
                limit = number(body, "xlLimit").setScale(2, java.math.RoundingMode.HALF_UP);
            }
            if (priority == null || limit == null || priority.signum() <= 0 || limit.signum() <= 0) {
                throw new IllegalArgumentException("An excess-of-loss cover needs a priority and a limit above zero.");
            }
        }
        treaty.terms(commission == null ? null : commission.setScale(2, java.math.RoundingMode.HALF_UP), basis,
                frequency, format, priority, limit);
        if (body.hasNonNull("capacity") && !body.get("capacity").asText().isBlank() && treaty.getCapacity() == null) {
            BigDecimal capacity = number(body, "capacity");
            if (capacity.signum() <= 0) {
                throw new IllegalArgumentException("capacity must be above zero.");
            }
            treaty.capacity(capacity.setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }

    /**
     * {participants: [{reinsurer, sharePercent, lead?}]}, shares adding up to exactly 100 (NS-SH07-001 blocks
     * inconsistent percentages); without it, the named reinsurer takes 100%. The lead comes first.
     */
    private List<Object[]> participants(JsonNode body, String reinsurer) {
        JsonNode node = body.get("participants");
        List<Object[]> out = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            out.add(new Object[] {reinsurer, HUNDRED.setScale(2), true});
            return out;
        }
        BigDecimal total = BigDecimal.ZERO;
        Set<String> seen = new java.util.HashSet<>();
        boolean leadSeen = false;
        for (JsonNode p : node) {
            String name = p.path("reinsurer").asText("").trim();
            if (name.isEmpty() || name.length() > 150 || !seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Each participant needs a distinct reinsurer name (at most 150 characters).");
            }
            BigDecimal share;
            try {
                share = new BigDecimal(p.path("sharePercent").asText("")).setScale(2, java.math.RoundingMode.UNNECESSARY);
            } catch (NumberFormatException | ArithmeticException ex) {
                throw new IllegalArgumentException("Each participant's share is a percentage with at most two decimals.");
            }
            if (share.signum() <= 0 || share.compareTo(HUNDRED) > 0) {
                throw new IllegalArgumentException("Each participant's share is above 0% and at most 100%.");
            }
            boolean lead = p.path("lead").asBoolean(false) && !leadSeen;
            leadSeen |= lead;
            if (lead) {
                out.add(0, new Object[] {name, share, true});
            } else {
                out.add(new Object[] {name, share, false});
            }
            total = total.add(share);
        }
        if (total.compareTo(HUNDRED) != 0) {
            throw new IllegalArgumentException("The participants' shares add up to " + total.toPlainString()
                    + "%; they must add up to exactly 100%.");
        }
        if (!leadSeen) {
            out.get(0)[2] = true;
        }
        return out;
    }

    private void saveParticipants(Treaty treaty, List<Object[]> participants) {
        for (Object[] p : participants) {
            em.persist(new TreatyParticipant(treaty, (String) p[0], (BigDecimal) p[1], (Boolean) p[2]));
        }
    }

    private static String choice(JsonNode body, String field, Set<String> allowed, String fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        String value = body.get(field).asText().trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException(field + " must be one of " + String.join(", ", allowed) + ".");
        }
        return value;
    }

    /**
     * Approves a draft. The user who drafted it cannot approve it (four eyes, no exception for any role: Nsure Life
     * NS-UX-004). Two approved treaties may not cover the same product on the same dates.
     */
    @Transactional
    public ReinsuranceViews.TreatyView approve(String ref) {
        AppUser user = user("APPROVE_TREATY");
        Treaty treaty = treaty(ref);
        if (!Treaty.DRAFT.equals(treaty.getStatus())) {
            throw new ConflictException("Treaty " + ref + " is already " + treaty.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (!treaty.isAutomatic()) {
            throw new ConflictException("Treaty " + ref + " is a facultative arrangement, approved with its acceptance.");
        }
        if (user.getUsername().equalsIgnoreCase(treaty.getCreatedBy())) {
            throw new NoAuthorizationException("You drafted treaty " + ref + "; another user must approve it.");
        }
        Treaty amended = treaty.getAmends();
        if (amended != null) {
            if (!Treaty.APPROVED.equals(amended.getStatus())) {
                throw new ConflictException("Treaty " + amended.getRef() + " is no longer approved; this amendment"
                        + " cannot take over from it.");
            }
            // The amended version governs up to the day before; its cessions keep pointing at it.
            amended.endBefore(treaty.getEffectiveFrom());
            em.flush();
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
     * Produces the bordereau for {treatyRef, period yyyy-MM, kind PREMIUM|CLAIMS}: a premium bordereau lists the
     * treaty's cessions of the month not yet reported, a claims bordereau its recoveries recognised in the month
     * (NS-SH07-004); each with record count and control totals. Asking again for the same month returns the same one.
     */
    @Transactional
    public ReinsuranceViews.BordereauView bordereau(JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Treaty treaty = treaty(required(body, "treatyRef", 20));
        String period = required(body, "period", 7);
        String kind = body.hasNonNull("kind") ? body.get("kind").asText().trim().toUpperCase(Locale.ROOT) : Bordereau.PREMIUM;
        if (!Set.of(Bordereau.PREMIUM, Bordereau.CLAIMS).contains(kind)) {
            throw new InvalidFieldException("kind", "kind must be PREMIUM or CLAIMS.");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(period);
        } catch (DateTimeParseException ex) {
            throw new InvalidFieldException("period", "period must be a month (yyyy-MM).");
        }
        List<Bordereau> existing = em.createQuery("select b from Bordereau b join fetch b.treaty"
                        + " where b.treaty = :treaty and b.period = :period and b.kind = :kind", Bordereau.class)
                .setParameter("treaty", treaty)
                .setParameter("period", month.toString())
                .setParameter("kind", kind)
                .getResultList();
        if (!existing.isEmpty()) {
            return view(existing.get(0));
        }
        if (!Treaty.APPROVED.equals(treaty.getStatus())) {
            throw new IllegalArgumentException("Treaty " + treaty.getRef() + " is not approved and has nothing to report.");
        }
        if (month.isAfter(YearMonth.now())) {
            throw new IllegalArgumentException("A bordereau cannot be produced for a future month.");
        }
        Bordereau bordereau;
        if (Bordereau.CLAIMS.equals(kind)) {
            List<Recovery> recoveries = unreportedRecoveries(treaty, month);
            BigDecimal claims = recoveries.stream().map(Recovery::getClaimAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal recovered = recoveries.stream().map(Recovery::getRecoveryAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            bordereau = new Bordereau(treaty, month.toString(), recoveries.size(), BigDecimal.ZERO.setScale(2), claims,
                    user.getUsername()).claims(recovered);
            em.persist(bordereau);
            em.flush();
            bordereau.numbered();
            Bordereau reported = bordereau;
            recoveries.forEach(r -> r.reportedOn(reported));
        } else {
            List<Cession> cessions = unreportedCessions(treaty, month);
            BigDecimal premium = cessions.stream().map(Cession::getCededPremium).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sum = cessions.stream().map(Cession::getCededSum).reduce(BigDecimal.ZERO, BigDecimal::add);
            bordereau = new Bordereau(treaty, month.toString(), cessions.size(), premium, sum, user.getUsername());
            em.persist(bordereau);
            em.flush();
            bordereau.numbered();
            Bordereau reported = bordereau;
            cessions.forEach(c -> c.reportedOn(reported));
        }
        publishSubmitted(bordereau, user.getUsername());
        return view(bordereau);
    }

    private List<Cession> unreportedCessions(Treaty treaty, YearMonth month) {
        return em.createQuery("select c from Cession c where c.treaty = :treaty"
                        + " and c.bordereau is null and c.createdAt >= :from and c.createdAt < :to order by c.id asc",
                        Cession.class)
                .setParameter("treaty", treaty)
                .setParameter("from", month.atDay(1).atStartOfDay())
                .setParameter("to", month.plusMonths(1).atDay(1).atStartOfDay())
                .getResultList();
    }

    private List<Recovery> unreportedRecoveries(Treaty treaty, YearMonth month) {
        return em.createQuery("select r from Recovery r where r.treaty = :treaty and r.bordereau is null"
                        + " and r.createdAt >= :from and r.createdAt < :to order by r.id asc", Recovery.class)
                .setParameter("treaty", treaty)
                .setParameter("from", month.atDay(1).atStartOfDay())
                .setParameter("to", month.plusMonths(1).atDay(1).atStartOfDay())
                .getResultList();
    }

    private void publishSubmitted(Bordereau bordereau, String actor) {
        // SH07: producing (or resubmitting a corrected) bordereau submits it to the reinsurer.
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("bordereauRef", bordereau.getRef());
        data.put("treatyRef", bordereau.getTreaty().getRef());
        data.put("reinsurer", bordereau.getTreaty().getReinsurer());
        data.put("period", bordereau.getPeriod());
        data.put("kind", bordereau.getKind());
        data.put("recordCount", bordereau.getRecordCount());
        data.put("totalCededPremium", bordereau.getTotalCededPremium().toPlainString());
        data.put("totalCededSum", bordereau.getTotalCededSum().toPlainString());
        if (bordereau.getTotalRecovery() != null) {
            data.put("totalRecovery", bordereau.getTotalRecovery().toPlainString());
        }
        data.put("correction", bordereau.getCorrectionCount());
        outbox.publish(Events.BORDEREAU_SUBMITTED, bordereau.getRef(), data, actor);
    }

    /** {reference}: the reinsurer acknowledged the bordereau. */
    @Transactional
    public ReinsuranceViews.BordereauView acknowledgeBordereau(String ref, JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Bordereau b = bordereauByRef(ref, Set.of(Bordereau.SUBMITTED, Bordereau.CORRECTED));
        b.acknowledge(required(body, "reference", 60), user.getUsername());
        return view(b);
    }

    /** {note}: the reinsurer asked for a correction. */
    @Transactional
    public ReinsuranceViews.BordereauView requestCorrection(String ref, JsonNode body) {
        user("MANAGE_REINSURANCE");
        Bordereau b = bordereauByRef(ref, Set.of(Bordereau.SUBMITTED, Bordereau.ACKNOWLEDGED, Bordereau.CORRECTED));
        b.correctionRequested(required(body, "note", 500));
        return view(b);
    }

    /**
     * Corrects and resubmits: the month's records are gathered again (late cessions or recoveries included), the
     * control totals recomputed and ReinsuranceBordereauSubmitted raised again with the correction number.
     */
    @Transactional
    public ReinsuranceViews.BordereauView correctBordereau(String ref) {
        AppUser user = user("MANAGE_REINSURANCE");
        Bordereau b = bordereauByRef(ref, Set.of(Bordereau.CORRECTION_REQUESTED));
        YearMonth month = YearMonth.parse(b.getPeriod());
        if (Bordereau.CLAIMS.equals(b.getKind())) {
            unreportedRecoveries(b.getTreaty(), month).forEach(r -> r.reportedOn(b));
            em.flush();
            List<Recovery> lines = em.createQuery("select r from Recovery r where r.bordereau = :b", Recovery.class)
                    .setParameter("b", b).getResultList();
            b.corrected(lines.size(), BigDecimal.ZERO.setScale(2),
                    lines.stream().map(Recovery::getClaimAmount).reduce(BigDecimal.ZERO, BigDecimal::add),
                    lines.stream().map(Recovery::getRecoveryAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        } else {
            unreportedCessions(b.getTreaty(), month).forEach(c -> c.reportedOn(b));
            em.flush();
            List<Cession> lines = em.createQuery("select c from Cession c where c.bordereau = :b", Cession.class)
                    .setParameter("b", b).getResultList();
            b.corrected(lines.size(), lines.stream().map(Cession::getCededPremium).reduce(BigDecimal.ZERO, BigDecimal::add),
                    lines.stream().map(Cession::getCededSum).reduce(BigDecimal.ZERO, BigDecimal::add), null);
        }
        publishSubmitted(b, user.getUsername());
        return view(b);
    }

    /** {reference, amount}: the account was settled with the reinsurer. */
    @Transactional
    public ReinsuranceViews.BordereauView settleBordereau(String ref, JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Bordereau b = bordereauByRef(ref, Set.of(Bordereau.ACKNOWLEDGED));
        BigDecimal amount = number(body, "amount").setScale(2, java.math.RoundingMode.HALF_UP);
        b.settle(required(body, "reference", 60), amount, user.getUsername());
        return view(b);
    }

    /** The bordereau's lines in the agreed layout (the treaty's format: CSV or JSON, rendered by the client). */
    @Transactional(readOnly = true)
    public List<ReinsuranceViews.BordereauLine> bordereauLines(String ref) {
        user("READ_REINSURANCE");
        Bordereau b = bordereauByRef(ref, null);
        if (Bordereau.CLAIMS.equals(b.getKind())) {
            return em.createQuery("select r from Recovery r where r.bordereau = :b order by r.id", Recovery.class)
                    .setParameter("b", b).getResultStream()
                    .map(r -> new ReinsuranceViews.BordereauLine(r.getRef(), r.getPolicyRef(), null, null, null, null,
                            null, r.getClaimRef(), r.getClaimAmount(), r.getRecoveryAmount(), null))
                    .toList();
        }
        return em.createQuery("select c from Cession c where c.bordereau = :b order by c.id", Cession.class)
                .setParameter("b", b).getResultStream()
                .map(c -> new ReinsuranceViews.BordereauLine(c.getRef(), c.getPolicyRef(), c.getProduct(),
                        c.getSumAssured(), c.getCededSum(), c.getPremium(), c.getCededPremium(), null, null, null,
                        c.getReceiptRef()))
                .toList();
    }

    private Bordereau bordereauByRef(String ref, Set<String> allowed) {
        Bordereau b = em.createQuery("select b from Bordereau b join fetch b.treaty where b.ref = :ref", Bordereau.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Bordereau " + ref + " not found"));
        if (allowed != null && !allowed.contains(b.getStatus())) {
            throw new ConflictException("Bordereau " + ref + " is " + b.getStatus().toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + ".");
        }
        return b;
    }

    /**
     * NS-SH07-006: gross premium received on products a treaty covers, ceded and retained per treaty for a month,
     * against the ceded premium and recoveries the subledger posted; with the operational queues: policies without
     * an approved treaty, capacity breaches waiting for facultative placement, and recoveries overdue.
     */
    @Transactional(readOnly = true)
    public ReinsuranceViews.ReconciliationView reconciliation(String period) {
        user("READ_REINSURANCE");
        YearMonth month;
        try {
            month = period == null || period.isBlank() ? YearMonth.now() : YearMonth.parse(period);
        } catch (DateTimeParseException ex) {
            throw new InvalidFieldException("period", "period must be a month (yyyy-MM).");
        }
        java.time.LocalDateTime from = month.atDay(1).atStartOfDay();
        java.time.LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        List<ReinsuranceViews.ReconRow> rows = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal ceded = BigDecimal.ZERO;
        for (Object[] r : em.createQuery("select c.treaty.ref, c.treaty.name, count(c), sum(c.premium),"
                        + " sum(c.cededPremium) from Cession c where c.createdAt >= :from and c.createdAt < :to"
                        + " group by c.treaty.ref, c.treaty.name order by c.treaty.ref", Object[].class)
                .setParameter("from", from).setParameter("to", to).getResultList()) {
            BigDecimal g = (BigDecimal) r[3];
            BigDecimal c = (BigDecimal) r[4];
            rows.add(new ReinsuranceViews.ReconRow((String) r[0], (String) r[1], (Long) r[2], g, c, g.subtract(c)));
            gross = gross.add(g);
            ceded = ceded.add(c);
        }
        BigDecimal ledgerCeded = ledger("5200", month, true);
        BigDecimal recognised = em.createQuery("select coalesce(sum(r.recoveryAmount), 0) from Recovery r"
                + " where r.createdAt >= :from and r.createdAt < :to", BigDecimal.class)
                .setParameter("from", from).setParameter("to", to).getSingleResult();
        BigDecimal ledgerRecoveries = ledger("4200", month, false);
        LocalDate today = LocalDate.now();
        List<ReinsuranceViews.QueueItem> missing = em.createQuery("select e from Exposure e where e.status = :none",
                        Exposure.class).setParameter("none", Exposure.NO_TREATY).setMaxResults(200).getResultStream()
                .map(e -> new ReinsuranceViews.QueueItem(e.getRef(), e.getPolicyRef(), e.getProduct()
                        + ": no approved treaty on " + e.getEffectiveFrom(), e.getSumAssured(),
                        java.time.temporal.ChronoUnit.DAYS.between(e.getEffectiveFrom(), today)))
                .toList();
        List<ReinsuranceViews.QueueItem> breaches = em.createQuery("select f from Facultative f where f.status in"
                        + " :open", Facultative.class).setParameter("open", Set.of(Facultative.REFERRED, Facultative.OFFERED))
                .getResultStream()
                .map(f -> new ReinsuranceViews.QueueItem(f.getRef(), f.getPolicyRef(), f.getStatus().toLowerCase(Locale.ROOT)
                        + ": " + f.getReferredReason(), f.getExcessSum(),
                        java.time.temporal.ChronoUnit.DAYS.between(f.getReferredAt().toLocalDate(), today)))
                .toList();
        List<ReinsuranceViews.QueueItem> overdue = em.createQuery("select r from Recovery r join fetch r.treaty"
                        + " where r.status = :expected and r.createdAt < :before order by r.createdAt", Recovery.class)
                .setParameter("expected", Recovery.EXPECTED)
                .setParameter("before", today.minusDays(overdueDays).atStartOfDay())
                .getResultStream()
                .map(r -> new ReinsuranceViews.QueueItem(r.getRef(), r.getPolicyRef(), r.getTreaty().getReinsurer()
                        + " on claim " + r.getClaimRef() + (Recovery.NONE.equals(r.getStage()) ? ""
                        : " (" + r.getStage().toLowerCase(Locale.ROOT).replace('_', ' ') + ")"), r.getRecoveryAmount(),
                        java.time.temporal.ChronoUnit.DAYS.between(r.getCreatedAt().toLocalDate(), today)))
                .toList();
        return new ReinsuranceViews.ReconciliationView(month.toString(), rows, gross, ceded, gross.subtract(ceded),
                ledgerCeded, ceded.subtract(ledgerCeded), recognised, ledgerRecoveries, missing, breaches, overdue);
    }

    /** Net movement on a subledger account in the month (debit nature: debits less credits). */
    private BigDecimal ledger(String account, YearMonth month, boolean debitNature) {
        Object[] row = em.createQuery("select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0) from JournalLine l"
                        + " where l.accountCode = :a and l.journal.periodCode = :p", Object[].class)
                .setParameter("a", account).setParameter("p", month.toString()).getSingleResult();
        BigDecimal d = (BigDecimal) row[0];
        BigDecimal c = (BigDecimal) row[1];
        return debitNature ? d.subtract(c) : c.subtract(d);
    }

    /**
     * {amount, reference, note?}: the reinsurer paid a recognised recovery (SH07 RecoveryReceived). A receipt that
     * differs from the amount expected needs a note; a recovery is received once.
     */
    @Transactional
    public ReinsuranceViews.RecoveryView receiveRecovery(String ref, JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Recovery recovery = em.createQuery("select r from Recovery r join fetch r.treaty where r.ref = :ref",
                        Recovery.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Recovery " + ref + " not found"));
        if (Recovery.RECEIVED.equals(recovery.getStatus())) {
            throw new ConflictException("Recovery " + ref + " was already received (" + recovery.getReceivedReference()
                    + ").");
        }
        if (Recovery.WRITTEN_OFF.equals(recovery.getStatus())) {
            throw new ConflictException("Recovery " + ref + " was written off.");
        }
        if (!Recovery.NONE.equals(recovery.getStage())) {
            throw new ConflictException("Recovery " + ref + " is " + recovery.getStage().toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + "; settle that first.");
        }
        BigDecimal amount = number(body, "amount").setScale(2, java.math.RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero.");
        }
        String reference = required(body, "reference", 60);
        String note = body.hasNonNull("note") && !body.get("note").asText().isBlank()
                ? body.get("note").asText().trim() : null;
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("note must be at most 500 characters.");
        }
        if (amount.compareTo(recovery.getRecoveryAmount()) != 0 && note == null) {
            throw new IllegalArgumentException("The reinsurer paid " + com.nsure.common.Money.display(amount)
                    + " but " + com.nsure.common.Money.display(recovery.getRecoveryAmount())
                    + " was expected. Add a note explaining the difference.");
        }
        recovery.received(amount, reference, note, user.getUsername());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("recoveryRef", recovery.getRef());
        data.put("treatyRef", recovery.getTreaty().getRef());
        data.put("claimRef", recovery.getClaimRef());
        data.put("policyRef", recovery.getPolicyRef());
        data.put("amount", amount.toPlainString());
        data.put("expected", recovery.getRecoveryAmount().toPlainString());
        data.put("reference", reference);
        outbox.publish(Events.RECOVERY_RECEIVED, recovery.getRef(), data, user.getUsername());
        return view(recovery);
    }

    // ── exposures and facultative placement (NS-SH07-002) ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ReinsuranceViews.ExposureView> exposures(String policyRef, String status) {
        user("READ_REINSURANCE");
        return em.createQuery("select e from Exposure e left join fetch e.treaty left join fetch e.facultative"
                        + " left join fetch e.supersedes where (:policy is null or e.policyRef = :policy)"
                        + " and (:status is null or e.status = :status) order by e.id desc", Exposure.class)
                .setParameter("policy", blankToNull(policyRef))
                .setParameter("status", blankToNull(status) == null ? null : status.trim().toUpperCase(Locale.ROOT))
                .setMaxResults(500).getResultStream().map(ReinsuranceService::view).toList();
    }

    @Transactional(readOnly = true)
    public List<ReinsuranceViews.FacultativeView> facultative() {
        user("READ_REINSURANCE");
        return em.createQuery("select f from Facultative f left join fetch f.treaty left join fetch f.facTreaty"
                + " order by f.id desc", Facultative.class).getResultStream().map(ReinsuranceService::view).toList();
    }

    /** {reinsurer, offeredSum}: the referral is offered to a reinsurer for facultative acceptance. */
    @Transactional
    public ReinsuranceViews.FacultativeView offer(String ref, JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Facultative fac = facultative(ref);
        if (!Set.of(Facultative.REFERRED, Facultative.OFFERED).contains(fac.getStatus())) {
            throw new ConflictException("Referral " + ref + " is already " + fac.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        BigDecimal sum = number(body, "offeredSum").setScale(2, java.math.RoundingMode.HALF_UP);
        if (sum.signum() <= 0 || sum.compareTo(fac.getExcessSum()) > 0) {
            throw new IllegalArgumentException("Offer more than zero and at most the " + com.nsure.common.Money.display(
                    fac.getExcessSum()) + " referred.");
        }
        fac.offered(required(body, "reinsurer", 150), sum, user.getUsername());
        return view(fac);
    }

    /**
     * {outcome ACCEPTED|DECLINED, acceptedSum?, acceptanceRef?, note}: the reinsurer's answer, captured by someone other
     * than the person who offered it. An acceptance becomes a policy-specific facultative arrangement that cedes the
     * accepted sum on every later premium and claim; a decline leaves the excess retained.
     */
    @Transactional
    public ReinsuranceViews.FacultativeView decide(String ref, JsonNode body) {
        AppUser user = user("APPROVE_TREATY");
        Facultative fac = facultative(ref);
        if (!Facultative.OFFERED.equals(fac.getStatus())) {
            throw new ConflictException("Referral " + ref + " is " + fac.getStatus().toLowerCase(Locale.ROOT)
                    + "; only an offered placement can be decided.");
        }
        if (user.getUsername().equalsIgnoreCase(fac.getOfferedBy())) {
            throw new NoAuthorizationException("You offered " + ref + "; someone else records the reinsurer's answer.");
        }
        String outcome = required(body, "outcome", 20).toUpperCase(Locale.ROOT);
        String note = required(body, "note", 500);
        if (Facultative.DECLINED.equals(outcome)) {
            fac.declined(note, user.getUsername());
            return view(fac);
        }
        if (!Facultative.ACCEPTED.equals(outcome)) {
            throw new IllegalArgumentException("outcome must be ACCEPTED or DECLINED.");
        }
        BigDecimal accepted = number(body, "acceptedSum").setScale(2, java.math.RoundingMode.HALF_UP);
        if (accepted.signum() <= 0 || accepted.compareTo(fac.getOfferedSum()) > 0) {
            throw new IllegalArgumentException("The accepted sum is more than zero and at most the "
                    + com.nsure.common.Money.display(fac.getOfferedSum()) + " offered.");
        }
        String acceptanceRef = required(body, "acceptanceRef", 60);
        BigDecimal percent = accepted.multiply(HUNDRED).divide(fac.getSumAssured(), 2, java.math.RoundingMode.HALF_UP);
        Treaty facTreaty = Treaty.facultative("Facultative " + fac.getRef() + " · " + fac.getPolicyRef(),
                fac.getReinsurer(), fac.getProduct(), fac.getPolicyRef(), percent, LocalDate.now(), fac.getOfferedBy(),
                user.getUsername());
        em.persist(facTreaty);
        em.flush();
        facTreaty.numbered();
        fac.accepted(accepted, acceptanceRef, note, facTreaty, user.getUsername());
        return view(fac);
    }

    // ── recovery ledger (NS-SH07-005) ────────────────────────────────────────────────────────────

    /** {reason}: the reinsurer disputes the recovery; it stays expected (and the claim open) until resolved. */
    @Transactional
    public ReinsuranceViews.RecoveryView dispute(String ref, JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Recovery recovery = expected(ref, Recovery.NONE);
        recovery.dispute(required(body, "reason", 500), user.getUsername());
        return view(recovery);
    }

    /** {note, agreedAmount?}: the dispute is settled; an agreed amount re-states what is expected. */
    @Transactional
    public ReinsuranceViews.RecoveryView resolveDispute(String ref, JsonNode body) {
        user("MANAGE_REINSURANCE");
        Recovery recovery = expected(ref, Recovery.DISPUTED);
        BigDecimal agreed = null;
        if (body.hasNonNull("agreedAmount") && !body.get("agreedAmount").asText().isBlank()) {
            agreed = number(body, "agreedAmount").setScale(2, java.math.RoundingMode.HALF_UP);
            if (agreed.signum() < 0) {
                throw new IllegalArgumentException("agreedAmount cannot be negative.");
            }
        }
        BigDecimal before = recovery.getRecoveryAmount();
        recovery.resolveDispute(agreed, required(body, "note", 300));
        BigDecimal delta = recovery.getRecoveryAmount().subtract(before);
        if (delta.signum() != 0) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("recoveryRef", recovery.getRef());
            data.put("treatyRef", recovery.getTreaty().getRef());
            data.put("claimRef", recovery.getClaimRef());
            data.put("policyRef", recovery.getPolicyRef());
            data.put("amount", delta.toPlainString());
            data.put("basis", "DISPUTE_RESOLVED");
            outbox.publish(Events.RECOVERY_RECOGNISED, recovery.getRef(), data, "system");
        }
        return view(recovery);
    }

    /** {amount?, reason}: asks for the recovery (or part of it) to be written off; a second person approves. */
    @Transactional
    public ReinsuranceViews.RecoveryView requestWriteOff(String ref, JsonNode body) {
        AppUser user = user("MANAGE_REINSURANCE");
        Recovery recovery = em.createQuery("select r from Recovery r join fetch r.treaty where r.ref = :ref",
                        Recovery.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Recovery " + ref + " not found"));
        if (!Recovery.EXPECTED.equals(recovery.getStatus()) || Recovery.WRITE_OFF_PENDING.equals(recovery.getStage())) {
            throw new ConflictException("Recovery " + ref + " is " + recovery.getStatus().toLowerCase(Locale.ROOT)
                    + (Recovery.WRITE_OFF_PENDING.equals(recovery.getStage()) ? " with a write-off pending" : "")
                    + "; only an expected recovery can be written off.");
        }
        BigDecimal amount = recovery.getRecoveryAmount();
        if (body.hasNonNull("amount") && !body.get("amount").asText().isBlank()) {
            amount = number(body, "amount").setScale(2, java.math.RoundingMode.HALF_UP);
        }
        if (amount.signum() <= 0 || amount.compareTo(recovery.getRecoveryAmount()) > 0) {
            throw new IllegalArgumentException("Write off more than zero and at most the "
                    + com.nsure.common.Money.display(recovery.getRecoveryAmount()) + " expected.");
        }
        recovery.requestWriteOff(amount, required(body, "reason", 500), user.getUsername());
        return view(recovery);
    }

    /** A different person approves (APPROVE_TREATY) or rejects the write-off; approval posts RecoveryWrittenOff. */
    @Transactional
    public ReinsuranceViews.RecoveryView decideWriteOff(String ref, boolean approve) {
        AppUser user = user("APPROVE_TREATY");
        Recovery recovery = expected(ref, Recovery.WRITE_OFF_PENDING);
        if (user.getUsername().equalsIgnoreCase(recovery.getWriteOffRequestedBy())) {
            throw new NoAuthorizationException("You asked to write off " + ref + "; someone else must decide it.");
        }
        recovery.decideWriteOff(approve, user.getUsername());
        if (approve) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("recoveryRef", recovery.getRef());
            data.put("treatyRef", recovery.getTreaty().getRef());
            data.put("claimRef", recovery.getClaimRef());
            data.put("policyRef", recovery.getPolicyRef());
            data.put("amount", recovery.getWriteOffAmount().toPlainString());
            data.put("requestedBy", recovery.getWriteOffRequestedBy());
            data.put("approvedBy", user.getUsername());
            outbox.publish(Events.RECOVERY_WRITTEN_OFF, recovery.getRef(), data, user.getUsername());
        }
        return view(recovery);
    }

    private Recovery expected(String ref, String stage) {
        Recovery recovery = em.createQuery("select r from Recovery r join fetch r.treaty where r.ref = :ref",
                        Recovery.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Recovery " + ref + " not found"));
        if (!Recovery.EXPECTED.equals(recovery.getStatus()) || !stage.equals(recovery.getStage())) {
            throw new ConflictException("Recovery " + ref + " is " + recovery.getStatus().toLowerCase(Locale.ROOT)
                    + (Recovery.NONE.equals(recovery.getStage()) ? "" : ", " + recovery.getStage().toLowerCase(Locale.ROOT)
                            .replace('_', ' ')) + ".");
        }
        return recovery;
    }

    private Facultative facultative(String ref) {
        return em.createQuery("select f from Facultative f where f.ref = :ref", Facultative.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Facultative referral " + ref + " not found"));
    }

    private static ReinsuranceViews.ExposureView view(Exposure e) {
        return new ReinsuranceViews.ExposureView(e.getRef(), e.getPolicyRef(), e.getCustomerRef(), e.getProduct(),
                e.getVersion(), e.getSumAssured(), e.getAggregateSum(), e.getRetainedSum(), e.getCededSum(),
                e.getCededShare(), e.getTreaty() == null ? null : e.getTreaty().getRef(),
                e.getTreaty() == null ? null : e.getTreaty().getName(),
                e.getFacultative() == null ? null : e.getFacultative().getRef(),
                e.getFacultative() == null ? null : e.getFacultative().getStatus(), e.getStatus(), e.getEffectiveFrom(),
                e.getEndedOn(), e.getSourceEventType(), e.getReason(),
                e.getSupersedes() == null ? null : e.getSupersedes().getRef(), e.getCreatedAt());
    }

    private static ReinsuranceViews.FacultativeView view(Facultative f) {
        return new ReinsuranceViews.FacultativeView(f.getRef(), f.getPolicyRef(), f.getCustomerRef(), f.getProduct(),
                f.getSumAssured(), f.getAggregateSum(), f.getExcessSum(),
                f.getTreaty() == null ? null : f.getTreaty().getRef(), f.getStatus(), f.getReferredReason(),
                f.getReferredAt(), f.getReinsurer(), f.getOfferedSum(), f.getOfferedBy(), f.getOfferedAt(),
                f.getAcceptedSum(), f.getAcceptanceRef(), f.getDecisionNote(), f.getDecidedBy(), f.getDecidedAt(),
                f.getFacTreaty() == null ? null : f.getFacTreaty().getRef());
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

    private ReinsuranceViews.TreatyView view(Treaty t, long cessions, BigDecimal ceded) {
        List<ReinsuranceViews.ParticipantView> participants = em.createQuery("select p from TreatyParticipant p"
                        + " where p.treaty = :t order by p.id", TreatyParticipant.class).setParameter("t", t)
                .getResultStream().map(p -> new ReinsuranceViews.ParticipantView(p.getReinsurer(), p.getSharePercent(),
                        p.isLead())).toList();
        if (participants.isEmpty()) {
            participants = List.of(new ReinsuranceViews.ParticipantView(t.getReinsurer(), HUNDRED.setScale(2), true));
        }
        return new ReinsuranceViews.TreatyView(t.getRef(), t.getName(), t.getReinsurer(), t.getTreatyType(),
                t.productList(), t.getCededPercent(), t.getRetention(), t.getEffectiveFrom(), t.getEffectiveTo(),
                t.getStatus(), Treaty.APPROVED.equals(t.getStatus()) && t.inForceOn(LocalDate.now()), t.getCreatedBy(),
                t.getCreatedAt(), t.getApprovedBy(), t.getApprovedAt(), cessions, ceded, t.getCapacity(),
                t.getPolicyRef(), t.getVersion(), t.getAmends() == null ? null : t.getAmends().getRef(),
                t.getAmendmentReason(), participants, t.getCommissionPercent(), t.getPremiumBasis(),
                t.getReportingFrequency(), t.getBordereauFormat(), t.getXlPriority(), t.getXlLimit());
    }

    private static ReinsuranceViews.CessionView view(Cession c) {
        return new ReinsuranceViews.CessionView(c.getRef(), c.getTreaty().getRef(), c.getTreaty().getName(),
                c.getPolicyRef(), c.getProduct(), c.getSumAssured(), c.getCededSum(), c.getPremium(),
                c.getCededPremium(), c.getCededShare(), c.getBordereau() == null ? null : c.getBordereau().getRef(),
                c.getSourceEventId(), c.getCreatedAt(), c.getReceiptRef(),
                c.getAdjusts() == null ? null : c.getAdjusts().getRef(),
                c.getExposure() == null ? null : c.getExposure().getRef(), c.getEventDate(), c.getReason());
    }

    private static ReinsuranceViews.RecoveryView view(Recovery r) {
        return new ReinsuranceViews.RecoveryView(r.getRef(), r.getTreaty().getRef(), r.getTreaty().getName(),
                r.getTreaty().getReinsurer(), r.getClaimRef(), r.getPolicyRef(), r.getClaimAmount(), r.getCededShare(),
                r.getRecoveryAmount(), r.getStatus(), r.getCreatedAt(), r.getReceivedAmount(),
                r.getReceivedReference(), r.getReceivedNote(), r.getReceivedBy(), r.getReceivedAt(), r.getStage(),
                r.getEventDate(), r.getClaimPaidAmount(), r.getOriginalAmount(), r.getDisputeReason(),
                r.getDisputedBy(), r.getDisputedAt(), r.getWriteOffAmount(), r.getWriteOffReason(),
                r.getWriteOffRequestedBy(), r.getWriteOffRequestedAt(), r.getWriteOffDecidedBy(),
                r.getWriteOffDecidedAt(), Recovery.EXPECTED.equals(r.getStatus())
                        ? java.time.temporal.ChronoUnit.DAYS.between(r.getCreatedAt().toLocalDate(), LocalDate.now()) : 0);
    }

    private static ReinsuranceViews.BordereauView view(Bordereau b) {
        return new ReinsuranceViews.BordereauView(b.getRef(), b.getTreaty().getRef(), b.getTreaty().getName(),
                b.getTreaty().getReinsurer(), b.getPeriod(), b.getRecordCount(), b.getTotalCededPremium(),
                b.getTotalCededSum(), b.getStatus(), b.getCreatedBy(), b.getCreatedAt(), b.getKind(),
                b.getCorrectionCount(), b.getAckReference(), b.getCorrectionNote(), b.getSettlementReference(),
                b.getSettledAmount(), b.getTotalRecovery(),
                b.getTreaty().getBordereauFormat() == null ? "CSV" : b.getTreaty().getBordereauFormat());
    }

    private static String required(JsonNode body, String field, int max) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            throw new InvalidFieldException(field, field + " is required.");
        }
        String value = body.get(field).asText().trim();
        if (value.length() > max) {
            throw new InvalidFieldException(field, field + " must be at most " + max + " characters.");
        }
        return value;
    }

    private static BigDecimal number(JsonNode body, String field) {
        String value = required(body, field, 20);
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new InvalidFieldException(field, field + " must be a number.");
        }
    }

    private static LocalDate date(JsonNode body, String field) {
        try {
            return LocalDate.parse(required(body, field, 10));
        } catch (DateTimeParseException ex) {
            throw new InvalidFieldException(field, field + " must be a date (yyyy-MM-dd).");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
