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

    public ReinsuranceService(EntityManager em, PlatformSecurityContext security, Outbox outbox) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
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
        return view(treaty, 0, BigDecimal.ZERO);
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
            throw new InvalidFieldException("period", "period must be a month (yyyy-MM).");
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
        // SH07: producing the bordereau submits it to the reinsurer (it is created SUBMITTED).
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("bordereauRef", bordereau.getRef());
        data.put("treatyRef", treaty.getRef());
        data.put("reinsurer", treaty.getReinsurer());
        data.put("period", month.toString());
        data.put("recordCount", cessions.size());
        data.put("totalCededPremium", premium.toPlainString());
        data.put("totalCededSum", sum.toPlainString());
        outbox.publish(Events.BORDEREAU_SUBMITTED, bordereau.getRef(), data, user.getUsername());
        return view(bordereau);
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

    private static ReinsuranceViews.TreatyView view(Treaty t, long cessions, BigDecimal ceded) {
        return new ReinsuranceViews.TreatyView(t.getRef(), t.getName(), t.getReinsurer(), t.getTreatyType(),
                t.productList(), t.getCededPercent(), t.getRetention(), t.getEffectiveFrom(), t.getEffectiveTo(),
                t.getStatus(), Treaty.APPROVED.equals(t.getStatus()) && t.inForceOn(LocalDate.now()), t.getCreatedBy(),
                t.getCreatedAt(), t.getApprovedBy(), t.getApprovedAt(), cessions, ceded, t.getCapacity(),
                t.getPolicyRef());
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
                b.getTotalCededSum(), b.getStatus(), b.getCreatedBy(), b.getCreatedAt());
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
