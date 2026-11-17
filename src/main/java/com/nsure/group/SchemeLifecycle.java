package com.nsure.group;

import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What a scheme suspension, reactivation, closure or transfer does to member cover, and what it would touch before it
 * is approved (NS-GL01-006, AT-GL01-03). The scheme lifecycle request in {@code com.nsure.grouplife} asks for the
 * impact when it is raised and applies the change here once a second person approves it. Every member keeps their
 * cover history: an episode is ceased or suspended with its dates, never deleted.
 */
@Component
public class SchemeLifecycle {

    private final EntityManager em;
    private final GroupRecords records;

    public SchemeLifecycle(EntityManager em, GroupRecords records) {
        this.em = em;
        this.records = records;
    }

    // ── impact preview ─────────────────────────────────────────────────────────────────────────────

    /** The members the request acts on: suspended members for a reactivation, members on cover otherwise. */
    public List<Map<String, Object>> affectedMembers(GroupScheme scheme, String eventType) {
        return members(scheme, eventType).stream().map(m -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("memberKey", m.getMemberKey());
            v.put("name", m.fullName());
            v.put("category", m.getCategory().getCode());
            v.put("status", m.getStatus());
            v.put("acceptedCover", m.getAcceptedCover());
            v.put("pendingCover", m.getPendingCover());
            v.put("monthlyPremium", m.onCover() ? m.getCategory().monthlyPremium(m.getAcceptedCover())
                    : BigDecimal.ZERO.setScale(2));
            return v;
        }).toList();
    }

    /** Invoices with money still owed by the sponsor; they stay receivable whatever happens to the scheme. */
    public List<Map<String, Object>> unpaidInvoices(GroupScheme scheme) {
        return em.createQuery("select i from SchemeInvoice i where i.scheme = :s and i.outstanding > 0"
                        + " order by i.period", SchemeInvoice.class)
                .setParameter("s", scheme).getResultList().stream().map(i -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("ref", i.getRef());
                    v.put("period", i.getPeriod());
                    v.put("amount", i.getAmount());
                    v.put("outstanding", i.getOutstanding());
                    v.put("status", i.getStatus());
                    return v;
                }).toList();
    }

    /**
     * Premium already invoiced for cover on or after the effective date: what the sponsor would be owed back (or not
     * charged) if cover ends or is suspended from that day. Each line is apportioned by the days it billed.
     */
    public BigDecimal premiumInvoicedFrom(GroupScheme scheme, LocalDate effective) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceLine line : em.createQuery("select l from InvoiceLine l join fetch l.invoice i"
                        + " where i.scheme = :s and i.periodEnd >= :from", InvoiceLine.class)
                .setParameter("s", scheme).setParameter("from", effective).getResultList()) {
            SchemeMember m = em.find(SchemeMember.class, line.getMemberId());
            if (m == null || line.getDaysCovered() <= 0) {
                continue;
            }
            SchemeInvoice inv = line.getInvoice();
            LocalDate from = later(later(m.getJoinDate(), inv.getPeriodStart()), effective);
            LocalDate to = m.getExitDate() != null && m.getExitDate().isBefore(inv.getPeriodEnd()) ? m.getExitDate()
                    : inv.getPeriodEnd();
            if (to.isBefore(from)) {
                continue;
            }
            long days = Math.min(line.getDaysCovered(), ChronoUnit.DAYS.between(from, to) + 1);
            total = total.add(line.getPremium().multiply(BigDecimal.valueOf(days))
                    .divide(BigDecimal.valueOf(line.getDaysCovered()), 2, RoundingMode.HALF_UP));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    /** The scheme's claims in the given statuses, newest first. */
    public List<Map<String, Object>> claims(GroupScheme scheme, Set<String> statuses) {
        return em.createQuery("select c from GroupClaim c join fetch c.member where c.scheme = :s"
                        + " and c.status in :statuses order by c.id desc", GroupClaim.class)
                .setParameter("s", scheme).setParameter("statuses", statuses).getResultList().stream()
                .map(c -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("ref", c.getRef());
                    v.put("memberKey", c.getMember().getMemberKey());
                    v.put("member", c.getMember().fullName());
                    v.put("benefitType", c.getBenefitType());
                    v.put("eventDate", c.getEventDate());
                    v.put("status", c.getStatus());
                    v.put("amount", c.getApprovedAmount() != null ? c.getApprovedAmount() : c.getClaimedAmount());
                    return v;
                }).toList();
    }

    /** Why the members cannot move to the target scheme: categories it lacks, or keys already on cover there. */
    public List<String> transferBlockers(GroupScheme source, GroupScheme target) {
        List<String> blockers = new ArrayList<>();
        if (target == null) {
            blockers.add("Choose the scheme the members transfer to.");
            return blockers;
        }
        if (target.getId().equals(source.getId())) {
            blockers.add("A scheme cannot transfer to itself.");
            return blockers;
        }
        if (!"ACTIVE".equals(target.getStatus())) {
            blockers.add(target.getRef() + " is " + target.getStatus().toLowerCase() + "; members can only move to an"
                    + " active scheme.");
        }
        Map<String, BenefitCategory> targetCategories = new HashMap<>();
        records.categories(target).forEach(c -> targetCategories.put(c.getCode(), c));
        Map<String, SchemeMember> onTarget = records.membersOnCover(target);
        Set<String> missing = new java.util.TreeSet<>();
        List<String> clashes = new ArrayList<>();
        for (SchemeMember m : members(source, "TRANSFER")) {
            if (!targetCategories.containsKey(m.getCategory().getCode())) {
                missing.add(m.getCategory().getCode());
            }
            if (onTarget.containsKey(m.getMemberKey())) {
                clashes.add(m.getMemberKey());
            }
        }
        if (!missing.isEmpty()) {
            blockers.add(target.getRef() + " has no categor" + (missing.size() == 1 ? "y " : "ies ")
                    + String.join(", ", missing) + "; add " + (missing.size() == 1 ? "it" : "them")
                    + " there first.");
        }
        if (!clashes.isEmpty()) {
            blockers.add("Already on cover on " + target.getRef() + ": " + String.join(", ", clashes) + ".");
        }
        return blockers;
    }

    // ── applying an approved request ───────────────────────────────────────────────────────────────

    /** Suspends every member on cover from the effective date; they are neither covered nor billed while suspended. */
    public int suspend(GroupScheme scheme, LocalDate effective, String actor, String requestRef) {
        int n = 0;
        for (SchemeMember m : members(scheme, "SUSPENSION")) {
            BigDecimal cover = m.getAcceptedCover();
            m.suspend(effective);
            em.persist(new MemberChange(m, "SUSPENSION", effective, m.getSalary(), cover, requestRef,
                    "Scheme suspended", actor));
            n++;
        }
        records.refreshTotals(scheme);
        return n;
    }

    /** Puts suspended members back on cover from the effective date, in the status they had before. */
    public int reactivate(GroupScheme scheme, LocalDate effective, String actor, String requestRef) {
        int n = 0;
        for (SchemeMember m : members(scheme, "REACTIVATION")) {
            m.reactivate(effective);
            em.persist(new MemberChange(m, "REACTIVATION", effective, m.getSalary(), m.getAcceptedCover(), requestRef,
                    "Scheme reactivated", actor));
            n++;
        }
        records.refreshTotals(scheme);
        return n;
    }

    /**
     * Ends every member's cover at the close of the day before the effective date. Pending underwriting lapses with
     * it; the ceased episode and its history stay on record.
     */
    public int close(GroupScheme scheme, LocalDate effective, String actor, String requestRef) {
        int n = 0;
        for (SchemeMember m : em.createQuery("select m from SchemeMember m where m.scheme = :s"
                        + " and m.status in :statuses", SchemeMember.class)
                .setParameter("s", scheme)
                .setParameter("statuses", union(SchemeMember.ON_COVER, Set.of(SchemeMember.SUSPENDED)))
                .getResultList()) {
            BigDecimal cover = m.getAcceptedCover();
            if (SchemeMember.SUSPENDED.equals(m.getStatus())) {
                m.reactivate(effective);
            }
            m.cease(effective.minusDays(1));
            em.persist(new MemberChange(m, "CLOSURE", effective, m.getSalary(), cover, requestRef,
                    "Scheme closed; cover ended " + effective.minusDays(1), actor));
            n++;
        }
        records.refreshTotals(scheme);
        return n;
    }

    /**
     * Moves every member on cover to the target scheme from the effective date: the episode on the source ends the day
     * before and a new episode starts on the target in the category with the same code, rated and limited by the
     * target's terms. Cover above the target's free-cover limit is pending underwriting there: earlier evidence is not
     * reused unless the insurer's rules say so.
     */
    public int transfer(GroupScheme source, GroupScheme target, LocalDate effective, String actor, String requestRef) {
        List<String> blockers = transferBlockers(source, target);
        if (!blockers.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", blockers));
        }
        Map<String, BenefitCategory> categories = new HashMap<>();
        records.categories(target).forEach(c -> categories.put(c.getCode(), c));
        int n = 0;
        for (SchemeMember m : members(source, "TRANSFER")) {
            BigDecimal cover = m.getAcceptedCover();
            m.cease(effective.minusDays(1));
            em.persist(new MemberChange(m, "TRANSFER_OUT", effective, m.getSalary(), cover, requestRef,
                    "Transferred to " + target.getRef(), actor));
            SchemeMember moved = new SchemeMember(target, categories.get(m.getCategory().getCode()), m.getMemberKey(),
                    m.getFirstName(), m.getLastName(), m.getDateOfBirth(), m.getSalary(), effective, requestRef);
            em.persist(moved);
            em.persist(new MemberChange(moved, "TRANSFER_IN", effective, null, null, requestRef,
                    "Transferred from " + source.getRef() + (SchemeMember.UNDERWRITING_PENDING.equals(moved.getStatus())
                            ? "; cover above the free-cover limit is pending underwriting" : ""), actor));
            n++;
        }
        records.refreshTotals(source);
        records.refreshTotals(target);
        return n;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private List<SchemeMember> members(GroupScheme scheme, String eventType) {
        Set<String> statuses = "REACTIVATION".equals(eventType) ? Set.of(SchemeMember.SUSPENDED) : SchemeMember.ON_COVER;
        return em.createQuery("select m from SchemeMember m join fetch m.category where m.scheme = :s"
                        + " and m.status in :statuses order by m.memberKey, m.id", SchemeMember.class)
                .setParameter("s", scheme)
                .setParameter("statuses", statuses)
                .getResultList();
    }

    private static LocalDate later(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new java.util.HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
