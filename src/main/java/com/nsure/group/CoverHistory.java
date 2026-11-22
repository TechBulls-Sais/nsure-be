package com.nsure.group;

import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Effective-dated cover (NS-GL04-004) and the premium it earns (GL05). Member changes record cover periods; category
 * terms are read from the version in force on each day; billing prices each day of a period on the cover and rate of
 * that day. Runs in the caller's transaction.
 *
 * <p>Proration basis (NS-GL04-005, demo default): {@code DAILY} charges cover x rate per mille x days covered / days in
 * the month; {@code MID_MONTH} charges a whole month for each cover slice in force on the 15th and nothing otherwise.
 * Set {@code nsure.group.billing.proration-basis}; the insurer has not yet approved a basis (D02).
 */
@Component
public class CoverHistory {

    public static final String DAILY = "DAILY";
    public static final String MID_MONTH = "MID_MONTH";

    private final EntityManager em;
    private final String prorationBasis;

    public CoverHistory(EntityManager em,
            @Value("${nsure.group.billing.proration-basis:DAILY}") String prorationBasis) {
        this.em = em;
        String basis = prorationBasis == null ? DAILY : prorationBasis.trim().toUpperCase(Locale.ROOT);
        this.prorationBasis = MID_MONTH.equals(basis) ? MID_MONTH : DAILY;
    }

    public String prorationBasis() {
        return prorationBasis;
    }

    public String prorationText() {
        return MID_MONTH.equals(prorationBasis)
                ? "Mid-month: a whole month for cover in force on the 15th (demo basis)"
                : "Daily: cover x rate per mille x days covered / days in month (demo basis)";
    }

    // ── cover periods ──────────────────────────────────────────────────────────────────────────────

    /** Live periods of one member in date order; a member without any yet is read from the member row. */
    public List<MemberCoverPeriod> periods(SchemeMember member) {
        return periods(List.of(member)).getOrDefault(member.getId(), List.of(MemberCoverPeriod.snapshot(member)));
    }

    /** Live periods for many members, keyed by member id; members without recorded periods get a snapshot. */
    Map<Long, List<MemberCoverPeriod>> periods(Collection<SchemeMember> members) {
        Map<Long, List<MemberCoverPeriod>> out = new HashMap<>();
        if (members.isEmpty()) {
            return out;
        }
        List<Long> ids = members.stream().map(SchemeMember::getId).toList();
        for (int i = 0; i < ids.size(); i += 500) {
            for (MemberCoverPeriod p : em.createQuery("select p from MemberCoverPeriod p join fetch p.category"
                            + " where p.member.id in :ids and p.supersededBy is null order by p.validFrom, p.id",
                            MemberCoverPeriod.class)
                    .setParameter("ids", ids.subList(i, Math.min(ids.size(), i + 500))).getResultList()) {
                out.computeIfAbsent(p.getMember().getId(), k -> new ArrayList<>()).add(p);
            }
        }
        for (SchemeMember m : members) {
            out.computeIfAbsent(m.getId(), k -> new ArrayList<>(List.of(MemberCoverPeriod.snapshot(m))));
        }
        return out;
    }

    /**
     * Before a change mutates a member, makes sure the cover in force so far is on record (members created before
     * cover periods existed, or by a path that does not record them).
     */
    void baseline(SchemeMember member) {
        Long count = em.createQuery("select count(p) from MemberCoverPeriod p where p.member = :m", Long.class)
                .setParameter("m", member).getSingleResult();
        if (count == 0) {
            MemberCoverPeriod first = MemberCoverPeriod.snapshot(member);
            em.persist(first);
        }
    }

    /**
     * Records a change effective {@code from}: the slice in force that day closes the day before (a leave closes it on
     * the exit day itself), later slices are superseded, and the member's terms after the change open the next slice.
     */
    void record(SchemeMember member, LocalDate from, String changeType, Long changeId) {
        boolean leave = "LEAVE".equals(changeType) || "CEASE".equals(changeType);
        List<MemberCoverPeriod> live = em.createQuery("select p from MemberCoverPeriod p where p.member = :m"
                        + " and p.supersededBy is null order by p.validFrom, p.id", MemberCoverPeriod.class)
                .setParameter("m", member).getResultList();
        for (MemberCoverPeriod p : live) {
            boolean startsLater = leave ? p.getValidFrom().isAfter(from) : !p.getValidFrom().isBefore(from);
            if (startsLater) {
                p.supersede(changeId == null ? 0L : changeId);
            } else if (p.getValidTo() == null || !p.getValidTo().isBefore(from)) {
                p.closeOn(leave ? from : from.minusDays(1));
            }
        }
        if (!leave) {
            MemberCoverPeriod next = new MemberCoverPeriod(member, from, changeType, changeId);
            if (member.getExitDate() != null) {
                next.closeOn(member.getExitDate());
            }
            em.persist(next);
        }
    }

    /**
     * An underwriting decision updates the latest live period in place (the cover was requested from its start) and
     * returns that start date, or null when the member has no recorded period.
     */
    LocalDate decided(SchemeMember member, Long changeId) {
        MemberCoverPeriod latest = em.createQuery("select p from MemberCoverPeriod p where p.member = :m"
                        + " and p.supersededBy is null order by p.validFrom desc, p.id desc", MemberCoverPeriod.class)
                .setParameter("m", member).setMaxResults(1).getResultStream().findFirst().orElse(null);
        if (latest == null) {
            return null;
        }
        latest.decided(member, changeId);
        return latest.getValidFrom();
    }

    // ── category versions ──────────────────────────────────────────────────────────────────────────

    /** Versions of the given categories, keyed by category id, oldest first. */
    Map<Long, List<CategoryVersion>> versions(Collection<BenefitCategory> categories) {
        Map<Long, List<CategoryVersion>> out = new HashMap<>();
        if (categories.isEmpty()) {
            return out;
        }
        for (CategoryVersion v : em.createQuery("select v from CategoryVersion v where v.category in :c"
                        + " order by v.effectiveFrom, v.id", CategoryVersion.class)
                .setParameter("c", categories).getResultList()) {
            out.computeIfAbsent(v.getCategory().getId(), k -> new ArrayList<>()).add(v);
        }
        return out;
    }

    /**
     * The rate per mille in force for the category on the day: its dated version, else its current terms. A category
     * amended after its open version was written (a prospective amendment on the Categories tab) applies its current
     * rate from the amendment date on.
     */
    static BigDecimal rateOn(BenefitCategory category, List<CategoryVersion> versions, LocalDate day) {
        if (versions != null) {
            for (CategoryVersion v : versions) {
                if (v.covers(day)) {
                    boolean amendedSince = v.getEffectiveTo() == null && category.getUpdatedAt() != null
                            && category.getUpdatedAt().isAfter(v.getCreatedAt())
                            && !day.isBefore(category.getUpdatedAt().toLocalDate());
                    return amendedSince ? category.getRatePerMille() : v.getRatePerMille();
                }
            }
        }
        return category.getRatePerMille();
    }

    /** The scheme's category versions, creating the current year's baseline for categories that have none yet. */
    List<CategoryVersion> ensureVersions(GroupScheme scheme, List<BenefitCategory> categories, String actor) {
        Map<Long, List<CategoryVersion>> existing = versions(categories);
        SchemeYear year = currentYear(scheme, actor);
        List<CategoryVersion> out = new ArrayList<>();
        for (BenefitCategory c : categories) {
            List<CategoryVersion> list = existing.get(c.getId());
            if (list == null || list.isEmpty()) {
                CategoryVersion v = new CategoryVersion(c, scheme.getPolicyYear(), scheme.getInceptionDate().isAfter(
                        year.getStartDate()) ? scheme.getInceptionDate() : year.getStartDate(), c.getRatePerMille(),
                        c.getFreeCoverLimit(), "Terms in force", actor);
                em.persist(v);
                out.add(v);
            } else {
                out.addAll(list);
            }
        }
        return out;
    }

    // ── scheme years ───────────────────────────────────────────────────────────────────────────────

    /** The current scheme-year record, created from the scheme's renewal date when the scheme has none yet. */
    SchemeYear currentYear(GroupScheme scheme, String actor) {
        SchemeYear year = em.createQuery("select y from SchemeYear y where y.scheme = :s and y.status = :current"
                        + " order by y.policyYear desc", SchemeYear.class)
                .setParameter("s", scheme).setParameter("current", SchemeYear.CURRENT).setMaxResults(1)
                .getResultStream().findFirst().orElse(null);
        if (year == null) {
            LocalDate start = scheme.getRenewalDate().minusYears(1);
            if (start.isBefore(scheme.getInceptionDate())) {
                start = scheme.getInceptionDate();
            }
            year = new SchemeYear(scheme, scheme.getPolicyYear(), start, scheme.getRenewalDate().minusDays(1), null,
                    null, actor);
            em.persist(year);
        }
        return year;
    }

    List<SchemeYear> years(GroupScheme scheme) {
        return em.createQuery("select y from SchemeYear y where y.scheme = :s order by y.policyYear", SchemeYear.class)
                .setParameter("s", scheme).getResultList();
    }

    // ── pricing ────────────────────────────────────────────────────────────────────────────────────

    /** What a member's cover earns over [start, end] of a billing month on the configured proration basis. */
    record Priced(BigDecimal premium, int daysCovered, BigDecimal cover, BigDecimal rate, String categoryCode) {
    }

    Priced price(SchemeMember member, List<MemberCoverPeriod> periods, Map<Long, List<CategoryVersion>> versions,
            LocalDate start, LocalDate end, int daysInPeriod) {
        BigDecimal sum = BigDecimal.ZERO;
        int days = 0;
        BigDecimal lastCover = BigDecimal.ZERO.setScale(2);
        BigDecimal lastRate = member.getCategory().getRatePerMille();
        String lastCategory = member.getCategory().getCode();
        LocalDate mid = start.withDayOfMonth(Math.min(15, start.lengthOfMonth()));
        for (MemberCoverPeriod p : periods) {
            if (SchemeMember.DECLINED.equals(p.getStatus()) || p.getAcceptedCover().signum() <= 0) {
                continue;
            }
            LocalDate from = max(max(p.getValidFrom(), member.getJoinDate()), start);
            LocalDate to = min(min(p.getValidTo() == null ? end : p.getValidTo(),
                    member.getExitDate() == null ? end : member.getExitDate()), end);
            if (to.isBefore(from)) {
                continue;
            }
            List<CategoryVersion> catVersions = versions.get(p.getCategory().getId());
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (member.suspendedDays(d, d) > 0) {
                    continue;
                }
                BigDecimal rate = rateOn(p.getCategory(), catVersions, d);
                days++;
                lastCover = p.getAcceptedCover();
                lastRate = rate;
                lastCategory = p.getCategoryCode();
                if (MID_MONTH.equals(prorationBasis)) {
                    if (d.equals(mid)) {
                        sum = sum.add(p.getAcceptedCover().multiply(rate).multiply(BigDecimal.valueOf(daysInPeriod)));
                    }
                } else {
                    sum = sum.add(p.getAcceptedCover().multiply(rate));
                }
            }
        }
        BigDecimal premium = sum.divide(BigDecimal.valueOf(1000L * daysInPeriod), 2, RoundingMode.HALF_UP);
        return new Priced(premium, days, lastCover, lastRate, lastCategory);
    }

    /** A full month's premium on the member's cover in force on the day (for previews and the dashboard). */
    BigDecimal monthlyPremiumOn(SchemeMember member, List<MemberCoverPeriod> periods,
            Map<Long, List<CategoryVersion>> versions, LocalDate day) {
        for (MemberCoverPeriod p : periods) {
            if (!day.isBefore(p.getValidFrom()) && (p.getValidTo() == null || !day.isAfter(p.getValidTo()))) {
                if (SchemeMember.DECLINED.equals(p.getStatus())) {
                    return BigDecimal.ZERO.setScale(2);
                }
                return p.getAcceptedCover().multiply(rateOn(p.getCategory(), versions.get(p.getCategory().getId()), day))
                        .divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP);
            }
        }
        return BigDecimal.ZERO.setScale(2);
    }

    static int days(LocalDate from, LocalDate to) {
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    static final Comparator<MemberCoverPeriod> BY_START = Comparator.comparing(MemberCoverPeriod::getValidFrom);
}
