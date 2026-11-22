package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scheme renewal (GL05: NS-GL05-005, NS-GL05-006, AT-GL05-03). Prepare: the expiring year's category terms, the
 * updated census and the year's claims experience give a recommended rate change and quotation version 1. Negotiate:
 * further versions with per-category rates, free-cover limits and cessation ages. Accept: the sponsor's acceptance of a
 * version, with the next year's start (a gap needs a reason; an overlap is refused). Approve: a second person renews
 * the scheme: the expiring year closes, the next year and next-year category versions open, and SchemeRenewed is
 * published. Income benefits in payment are never terminated by a renewal; the checks show which ones continue and
 * which reach their contractual cessation in the new year.
 *
 * <p>Demo rating rule (D02, not an approved basis): recommended change = (claims incurred / premium billed in the
 * expiring year / target loss ratio - 1) x credibility, credibility = lives / full-credibility lives (max 1), limited
 * to the floor and cap. Cessation age defaults to 65. Properties under {@code nsure.group.renewal.*}.
 */
@Service
public class SchemeRenewalService {

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final CoverHistory cover;
    private final ObjectMapper mapper;
    private final BigDecimal targetLossRatio;
    private final int fullCredibilityLives;
    private final BigDecimal floor;
    private final BigDecimal cap;
    private final int cessationAge;

    public SchemeRenewalService(EntityManager em, PlatformSecurityContext security, GroupRecords records,
            CoverHistory cover, ObjectMapper mapper,
            @Value("${nsure.group.renewal.target-loss-ratio:0.60}") BigDecimal targetLossRatio,
            @Value("${nsure.group.renewal.full-credibility-lives:200}") int fullCredibilityLives,
            @Value("${nsure.group.renewal.max-decrease-percent:15}") BigDecimal maxDecrease,
            @Value("${nsure.group.renewal.max-increase-percent:25}") BigDecimal maxIncrease,
            @Value("${nsure.group.renewal.cessation-age:65}") int cessationAge) {
        this.em = em;
        this.security = security;
        this.records = records;
        this.cover = cover;
        this.mapper = mapper;
        this.targetLossRatio = targetLossRatio;
        this.fullCredibilityLives = Math.max(1, fullCredibilityLives);
        this.floor = maxDecrease.negate();
        this.cap = maxIncrease;
        this.cessationAge = cessationAge;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> renewals(String schemeRef) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        GroupScheme scheme = records.scheme(schemeRef);
        return em.createQuery("select r from SchemeRenewal r where r.scheme = :s order by r.id desc", SchemeRenewal.class)
                .setParameter("s", scheme).getResultList().stream().map(this::view).toList();
    }

    /** {note?}: prepares the renewal of the current scheme year with quotation version 1. */
    @Transactional
    public Map<String, Object> prepare(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        GroupRecords.requireActive(scheme, "a renewal");
        if (em.createQuery("select count(r) from SchemeRenewal r where r.scheme = :s and r.status in :open", Long.class)
                .setParameter("s", scheme).setParameter("open", List.of(SchemeRenewal.QUOTED,
                        SchemeRenewal.NEGOTIATING, SchemeRenewal.ACCEPTED)).getSingleResult() > 0) {
            throw new ConflictException(schemeRef + " already has a renewal in progress");
        }
        List<BenefitCategory> categories = records.categories(scheme);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException(schemeRef + " has no benefit categories to renew");
        }
        SchemeYear year = cover.currentYear(scheme, user.getUsername());
        cover.ensureVersions(scheme, categories, user.getUsername());
        SchemeRenewal renewal = new SchemeRenewal(records.nextRef(SchemeRenewal.class, "GRN-", 5), scheme, year,
                user.getUsername());
        Map<String, Object> experience = experience(scheme, year);
        renewal.experience = json(experience);
        renewal.recommendedChange = (BigDecimal) experience.get("recommendedChangePercent");
        em.persist(renewal);
        Map<String, Integer> ages = new HashMap<>();
        categories.forEach(c -> ages.put(c.getCode(), cessationAgeOf(c)));
        newVersion(renewal, renewal.recommendedChange, Map.of(), Map.of(), ages,
                GroupRecords.text(body, "note") == null ? "Renewal quotation on the demo experience-rating rule"
                        : GroupRecords.text(body, "note"), user.getUsername());
        renewal.checks = json(checks(scheme, renewal, ages));
        em.flush();
        return view(renewal);
    }

    /**
     * {rateChangePercent?, terms?[{category, ratePerMille?, freeCoverLimit?, cessationAge?}], note}: a negotiated
     * version; it supersedes the one before. Terms not named take the rate change on the expiring rate.
     */
    @Transactional
    public Map<String, Object> negotiate(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        SchemeRenewal renewal = find(ref);
        if (!List.of(SchemeRenewal.QUOTED, SchemeRenewal.NEGOTIATING).contains(renewal.status)) {
            throw new ConflictException(ref + " is " + renewal.status.toLowerCase(Locale.ROOT)
                    + "; it can no longer be negotiated");
        }
        BigDecimal change = GroupRecords.decimal(body, "rateChangePercent", "Rate change");
        change = change == null ? latest(renewal).rateChangePercent : change.setScale(2, RoundingMode.HALF_UP);
        if (change.compareTo(BigDecimal.valueOf(-90)) < 0 || change.compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new IllegalArgumentException("Rate change must be between -90% and 500%");
        }
        Map<String, BigDecimal> rates = new HashMap<>();
        Map<String, BigDecimal> freeCover = new HashMap<>();
        Map<String, Integer> ages = new HashMap<>();
        for (RenewalTerm t : terms(latest(renewal))) {
            ages.put(t.categoryCode, t.cessationAge);
        }
        JsonNode terms = body.path("terms");
        if (terms.isArray()) {
            for (JsonNode t : terms) {
                String code = GroupRecords.requiredText(t, "category", "Category", 20).toUpperCase(Locale.ROOT);
                if (!ages.containsKey(code)) {
                    throw new IllegalArgumentException("Unknown category " + code + " on " + ref);
                }
                BigDecimal rate = GroupRecords.decimal(t, "ratePerMille", "Rate per mille");
                if (rate != null) {
                    if (rate.signum() <= 0 || rate.compareTo(BigDecimal.valueOf(1000)) >= 0) {
                        throw new IllegalArgumentException("Rate per mille for " + code + " must be above 0 and below 1000");
                    }
                    rates.put(code, rate.setScale(4, RoundingMode.HALF_UP));
                }
                BigDecimal free = GroupRecords.decimal(t, "freeCoverLimit", "Free cover limit");
                if (free != null) {
                    freeCover.put(code, GroupRecords.money(free, "Free cover limit", true));
                }
                Integer age = GroupRecords.integer(t, "cessationAge", "Cessation age");
                if (age != null) {
                    if (age < 50 || age > 80) {
                        throw new IllegalArgumentException("Cessation age for " + code + " must be between 50 and 80");
                    }
                    ages.put(code, age);
                }
            }
        }
        RenewalVersion previous = latest(renewal);
        previous.status = "SUPERSEDED";
        newVersion(renewal, change, rates, freeCover, ages,
                GroupRecords.requiredText(body, "note", "Negotiation note", 500), user.getUsername());
        renewal.status = SchemeRenewal.NEGOTIATING;
        renewal.checks = json(checks(renewal.getScheme(), renewal, ages));
        em.flush();
        return view(renewal);
    }

    /**
     * {version?, sponsorReference, note, nextStartDate?, gapReason?}: records the sponsor's acceptance of a version.
     * The next year starts the day after the expiring year ends; an earlier start (overlap) is refused and a later one
     * (gap) needs an explicit reason.
     */
    @Transactional
    public Map<String, Object> accept(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        SchemeRenewal renewal = find(ref);
        if (!List.of(SchemeRenewal.QUOTED, SchemeRenewal.NEGOTIATING).contains(renewal.status)) {
            throw new ConflictException(ref + " is " + renewal.status.toLowerCase(Locale.ROOT));
        }
        Integer version = GroupRecords.integer(body, "version", "Version");
        RenewalVersion accepted = version == null ? latest(renewal) : em.createQuery("select v from RenewalVersion v"
                        + " where v.renewal = :r and v.versionNo = :n", RenewalVersion.class)
                .setParameter("r", renewal).setParameter("n", version).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException(ref + " has no version " + version));
        String startText = GroupRecords.text(body, "nextStartDate");
        LocalDate continuous = renewal.expiringEnd.plusDays(1);
        if (startText != null) {
            LocalDate start = GroupRecords.date(body, "nextStartDate", "Next year start");
            if (start.isBefore(continuous)) {
                throw new IllegalArgumentException("The next year cannot start before " + continuous
                        + ": it would overlap policy year " + renewal.expiringYear);
            }
            if (start.isAfter(continuous)) {
                String reason = GroupRecords.text(body, "gapReason");
                if (reason == null) {
                    throw new IllegalArgumentException("Starting on " + start + " leaves a gap in cover from " + continuous
                            + "; give a gap reason to accept it explicitly");
                }
                renewal.gapNote = ("Gap " + continuous + " to " + start.minusDays(1) + " accepted: " + reason)
                        .substring(0, Math.min(300, ("Gap " + continuous + " to " + start.minusDays(1) + " accepted: "
                                + reason).length()));
            }
            renewal.nextStart = start;
            renewal.nextEnd = start.plusYears(1).minusDays(1);
        }
        accepted.status = "ACCEPTED";
        renewal.acceptedVersion = accepted.versionNo;
        renewal.sponsorReference = GroupRecords.requiredText(body, "sponsorReference", "Sponsor acceptance reference", 80);
        renewal.acceptanceNote = GroupRecords.requiredText(body, "note", "Acceptance note", 300);
        renewal.acceptedBy = user.getUsername();
        renewal.acceptedAt = LocalDateTime.now();
        renewal.status = SchemeRenewal.ACCEPTED;
        return view(renewal);
    }

    /**
     * {note}: a second person (not whoever recorded the acceptance) approves the accepted version and the scheme renews.
     */
    @Transactional
    public Map<String, Object> approve(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        SchemeRenewal renewal = find(ref);
        if (!SchemeRenewal.ACCEPTED.equals(renewal.status)) {
            throw new ConflictException(ref + " is " + renewal.status.toLowerCase(Locale.ROOT)
                    + "; only an accepted renewal can be approved");
        }
        if (user.getUsername().equalsIgnoreCase(renewal.acceptedBy)) {
            throw new NoAuthorizationException("You recorded the acceptance of " + ref + ", so someone else must approve it.");
        }
        String note = GroupRecords.requiredText(body, "note", "Approval note", 300);
        GroupScheme scheme = records.lockedScheme(renewal.getScheme().getRef());
        GroupRecords.requireActive(scheme, "a renewal");
        RenewalVersion version = em.createQuery("select v from RenewalVersion v where v.renewal = :r"
                + " and v.versionNo = :n", RenewalVersion.class).setParameter("r", renewal)
                .setParameter("n", renewal.acceptedVersion).getSingleResult();
        Map<String, Integer> ages = new HashMap<>();
        Map<BenefitCategory, BigDecimal[]> next = new LinkedHashMap<>();
        for (RenewalTerm t : terms(version)) {
            ages.put(t.categoryCode, t.cessationAge);
            next.put(t.category, new BigDecimal[] {t.proposedRate, t.proposedFreeCover, BigDecimal.valueOf(t.cessationAge)});
        }
        renewal.checks = json(checks(scheme, renewal, ages));
        renewTo(scheme, renewal.nextStart, renewal.nextEnd, next, ref, renewal.gapNote, user.getUsername());
        renewal.status = SchemeRenewal.RENEWED;
        renewal.approvedBy = user.getUsername();
        renewal.approvedAt = LocalDateTime.now();
        renewal.approvalNote = note;
        long continuing = em.createQuery("select count(c) from GroupClaim c where c.scheme = :s and c.status = :p",
                Long.class).setParameter("s", scheme).setParameter("p", GroupClaim.IN_PAYMENT).getSingleResult();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", scheme.getRef());
        data.put("renewalRef", ref);
        data.put("policyYear", scheme.getPolicyYear());
        data.put("yearStart", renewal.nextStart.toString());
        data.put("renewalDate", scheme.getRenewalDate().toString());
        data.put("version", version.versionNo);
        data.put("rateChangePercent", version.rateChangePercent);
        data.put("annualPremium", version.annualPremium.toPlainString());
        data.put("incomeBenefitsContinuing", continuing);
        records.publish(Events.SCHEME_RENEWED, scheme.getRef(), data, user.getUsername());
        em.flush();
        return view(renewal);
    }

    /**
     * The renewal applied by the scheme lifecycle request (GL01 workspace): every category takes the rate change from
     * the scheme's renewal date, on the same dated versions and scheme-year records a renewal approval creates.
     */
    public void renewByRateChange(GroupScheme scheme, BigDecimal ratePercent, String sourceRef, String actor) {
        SchemeYear year = cover.currentYear(scheme, actor);
        List<BenefitCategory> categories = records.categories(scheme);
        cover.ensureVersions(scheme, categories, actor);
        BigDecimal factor = BigDecimal.ONE.add((ratePercent == null ? BigDecimal.ZERO : ratePercent)
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        Map<BenefitCategory, BigDecimal[]> next = new LinkedHashMap<>();
        for (BenefitCategory c : categories) {
            next.put(c, new BigDecimal[] {c.getRatePerMille().multiply(factor).setScale(4, RoundingMode.HALF_UP),
                    c.getFreeCoverLimit(), BigDecimal.valueOf(cessationAgeOf(c))});
        }
        LocalDate start = year.getEndDate().plusDays(1);
        renewTo(scheme, start, start.plusYears(1).minusDays(1), next, sourceRef, null, actor);
    }

    private void renewTo(GroupScheme scheme, LocalDate start, LocalDate end, Map<BenefitCategory, BigDecimal[]> terms,
            String sourceRef, String gapNote, String actor) {
        SchemeYear year = cover.currentYear(scheme, actor);
        Map<Long, List<CategoryVersion>> versions = cover.versions(terms.keySet());
        int nextYear = scheme.getPolicyYear() + 1;
        for (Map.Entry<BenefitCategory, BigDecimal[]> e : terms.entrySet()) {
            BenefitCategory c = e.getKey();
            for (CategoryVersion v : versions.getOrDefault(c.getId(), List.of())) {
                if (v.getEffectiveTo() == null || !v.getEffectiveTo().isBefore(start)) {
                    v.closeOn(start.minusDays(1));
                }
            }
            CategoryVersion v = new CategoryVersion(c, nextYear, start, e.getValue()[0], e.getValue()[1], sourceRef,
                    actor);
            v.cessationAge(e.getValue()[2].intValue());
            em.persist(v);
            // The category's current terms are the new year's; invoices for earlier months use the dated versions.
            c.define(c.getName(), c.getBenefitBasis(), c.getSalaryMultiple(), c.getFixedCover(), e.getValue()[1],
                    e.getValue()[0], c.getMinEntryAge(), c.getMaxEntryAge(), actor);
        }
        year.expire();
        em.persist(new SchemeYear(scheme, nextYear, start, end, sourceRef, gapNote, actor));
        LocalDate renewal = end.plusDays(1);
        scheme.renewTo(renewal, renewal.format(DISPLAY));
        records.refreshTotals(scheme);
    }

    // ── experience, versions and checks ────────────────────────────────────────────────────────────

    private Map<String, Object> experience(GroupScheme scheme, SchemeYear year) {
        Map<String, SchemeMember> onCover = records.membersOnCover(scheme);
        BigDecimal coverInForce = onCover.values().stream().map(SchemeMember::getAcceptedCover)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double averageAge = onCover.values().stream().mapToInt(m -> Period.between(m.getDateOfBirth(),
                year.getEndDate().plusDays(1)).getYears()).average().orElse(0);
        BigDecimal billed = em.createQuery("select coalesce(sum(i.amount + i.adjustmentAmount), 0) from SchemeInvoice i"
                        + " where i.scheme = :s and i.periodStart >= :from and i.periodStart <= :to", BigDecimal.class)
                .setParameter("s", scheme).setParameter("from", year.getStartDate()).setParameter("to", year.getEndDate())
                .getSingleResult();
        List<GroupClaim> claims = em.createQuery("select c from GroupClaim c where c.scheme = :s"
                        + " and c.eventDate >= :from and c.eventDate <= :to and c.status <> :declined", GroupClaim.class)
                .setParameter("s", scheme).setParameter("from", year.getStartDate()).setParameter("to", year.getEndDate())
                .setParameter("declined", GroupClaim.DECLINED).getResultList();
        BigDecimal incurred = claims.stream().map(c -> c.getApprovedAmount() != null ? c.getApprovedAmount()
                : c.getClaimedAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("policyYear", year.getPolicyYear());
        e.put("yearStart", year.getStartDate());
        e.put("yearEnd", year.getEndDate());
        e.put("membersOnCover", onCover.size());
        e.put("coverInForce", coverInForce.setScale(2, RoundingMode.HALF_UP));
        e.put("averageAgeAtRenewal", BigDecimal.valueOf(averageAge).setScale(1, RoundingMode.HALF_UP));
        e.put("premiumBilled", billed.setScale(2, RoundingMode.HALF_UP));
        e.put("claims", claims.size());
        e.put("claimsIncurred", incurred.setScale(2, RoundingMode.HALF_UP));
        BigDecimal change = BigDecimal.ZERO.setScale(2);
        String basis;
        if (billed.signum() <= 0) {
            basis = "No premium billed in the expiring year: no experience adjustment (demo rule).";
            e.put("lossRatio", null);
        } else {
            BigDecimal lossRatio = incurred.divide(billed, 4, RoundingMode.HALF_UP);
            BigDecimal credibility = BigDecimal.valueOf(Math.min(1.0, onCover.size() / (double) fullCredibilityLives))
                    .setScale(4, RoundingMode.HALF_UP);
            BigDecimal raw = lossRatio.divide(targetLossRatio, 6, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                    .multiply(credibility).multiply(BigDecimal.valueOf(100));
            raw = raw.max(floor).min(cap);
            // Rounded to the nearest half percent.
            change = raw.multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.HALF_UP)
                    .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            e.put("lossRatio", lossRatio);
            e.put("credibility", credibility);
            basis = "Demo experience rating: (loss ratio " + lossRatio.multiply(BigDecimal.valueOf(100)).setScale(1,
                    RoundingMode.HALF_UP) + "% / target " + targetLossRatio.multiply(BigDecimal.valueOf(100)).setScale(0,
                    RoundingMode.HALF_UP) + "% - 1) x credibility " + credibility + ", limited to " + floor + "% .. +"
                    + cap + "%.";
        }
        e.put("recommendedChangePercent", change);
        e.put("basis", basis);
        return e;
    }

    private void newVersion(SchemeRenewal renewal, BigDecimal change, Map<String, BigDecimal> rates,
            Map<String, BigDecimal> freeCover, Map<String, Integer> ages, String note, String actor) {
        renewal.currentVersion++;
        RenewalVersion version = new RenewalVersion(renewal, renewal.currentVersion, change, note, actor);
        em.persist(version);
        GroupScheme scheme = renewal.getScheme();
        List<BenefitCategory> categories = records.categories(scheme);
        Map<Long, List<CategoryVersion>> versions = cover.versions(categories);
        Map<String, SchemeMember> onCover = records.membersOnCover(scheme);
        BigDecimal factor = BigDecimal.ONE.add(change.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
        BigDecimal expiringTotal = BigDecimal.ZERO;
        BigDecimal proposedTotal = BigDecimal.ZERO;
        for (BenefitCategory c : categories) {
            RenewalTerm t = new RenewalTerm(version, c);
            BigDecimal expiringRate = CoverHistory.rateOn(c, versions.get(c.getId()), renewal.expiringEnd);
            List<SchemeMember> members = onCover.values().stream().filter(m -> m.getCategory().getId().equals(c.getId()))
                    .toList();
            t.members = members.size();
            t.cover = members.stream().map(SchemeMember::getAcceptedCover).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
            t.expiringRate = expiringRate;
            t.proposedRate = rates.containsKey(c.getCode()) ? rates.get(c.getCode())
                    : expiringRate.multiply(factor).setScale(4, RoundingMode.HALF_UP);
            t.expiringFreeCover = c.getFreeCoverLimit();
            t.proposedFreeCover = freeCover.getOrDefault(c.getCode(), c.getFreeCoverLimit());
            t.cessationAge = ages.getOrDefault(c.getCode(), cessationAgeOf(c));
            t.expiringAnnual = annual(t.cover, t.expiringRate);
            t.proposedAnnual = annual(t.cover, t.proposedRate);
            expiringTotal = expiringTotal.add(t.expiringAnnual);
            proposedTotal = proposedTotal.add(t.proposedAnnual);
            em.persist(t);
        }
        version.expiringPremium = expiringTotal.setScale(2, RoundingMode.HALF_UP);
        version.annualPremium = proposedTotal.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal annual(BigDecimal cover, BigDecimal rate) {
        return cover.multiply(rate).multiply(BigDecimal.valueOf(12)).divide(BigDecimal.valueOf(1000), 2,
                RoundingMode.HALF_UP);
    }

    /**
     * Renewal impact checks (NS-GL05-006, AT-GL05-03): members reaching their category's cessation age in the next
     * year, claims pending, income benefits in payment (which continue; any contractual cessation date in the new year
     * is shown, never applied by the renewal), underwriting outstanding, plan changes awaiting approval, unpaid
     * invoices and year continuity.
     */
    Map<String, Object> checks(GroupScheme scheme, SchemeRenewal renewal, Map<String, Integer> ages) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> reachingAge = new ArrayList<>();
        int pendingUw = 0;
        for (SchemeMember m : records.membersOnCover(scheme).values()) {
            int age = ages.getOrDefault(m.getCategory().getCode(), cessationAgeOf(m.getCategory()));
            LocalDate ceases = m.getDateOfBirth().plusYears(age);
            if (!ceases.isAfter(renewal.nextEnd)) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("memberKey", m.getMemberKey());
                v.put("name", m.fullName());
                v.put("category", m.getCategory().getCode());
                v.put("cessationAge", age);
                v.put("ceasesOn", ceases.isBefore(renewal.nextStart) ? renewal.nextStart : ceases);
                reachingAge.add(v);
            }
            if (SchemeMember.UNDERWRITING_PENDING.equals(m.getStatus())) {
                pendingUw++;
            }
        }
        out.put("membersReachingCessationAge", reachingAge);
        List<Map<String, Object>> pending = new ArrayList<>();
        List<Map<String, Object>> income = new ArrayList<>();
        for (GroupClaim c : em.createQuery("select c from GroupClaim c join fetch c.member where c.scheme = :s"
                + " and c.status in :open order by c.eventDate", GroupClaim.class).setParameter("s", scheme)
                .setParameter("open", List.of(GroupClaim.NOTIFIED, GroupClaim.APPROVED, GroupClaim.IN_PAYMENT))
                .getResultList()) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("ref", c.getRef());
            v.put("memberKey", c.getMember().getMemberKey());
            v.put("benefitType", c.getBenefitType());
            v.put("status", c.getStatus());
            v.put("eventDate", c.getEventDate());
            if (GroupClaim.IN_PAYMENT.equals(c.getStatus())) {
                int age = ages.getOrDefault(c.getMember().getCategory().getCode(),
                        cessationAgeOf(c.getMember().getCategory()));
                LocalDate cessation = c.getMember().getDateOfBirth().plusYears(age);
                v.put("nextReviewDate", c.getNextReviewDate());
                v.put("contractualCessation", cessation);
                v.put("treatment", cessation.isAfter(renewal.nextEnd)
                        ? "Continues into policy year " + (renewal.expiringYear + 1) + "; the renewal does not end it"
                        : "Continues until its contractual cessation on " + cessation + " (age " + age
                                + "); the renewal itself does not end it");
                income.add(v);
            } else {
                pending.add(v);
            }
        }
        out.put("pendingClaims", pending);
        out.put("incomeBenefitsInPayment", income);
        out.put("pendingUnderwriting", pendingUw);
        long plans = em.createQuery("select count(b) from SchemeBenefit b where b.schemeRef = :ref"
                + " and b.approvalStatus = 'PENDING'", Long.class).setParameter("ref", scheme.getRef()).getSingleResult();
        out.put("planChangesAwaitingApproval", plans);
        BigDecimal unpaid = em.createQuery("select coalesce(sum(i.outstanding), 0) from SchemeInvoice i"
                + " where i.scheme = :s", BigDecimal.class).setParameter("s", scheme).getSingleResult();
        out.put("unpaidPremium", unpaid.setScale(2, RoundingMode.HALF_UP));
        out.put("continuity", renewal.nextStart.equals(renewal.expiringEnd.plusDays(1))
                ? "Policy year " + (renewal.expiringYear + 1) + " starts " + renewal.nextStart + ", the day after year "
                        + renewal.expiringYear + " ends: no gap or overlap."
                : renewal.gapNote);
        List<String> notes = new ArrayList<>();
        if (!reachingAge.isEmpty()) {
            notes.add(reachingAge.size() + " member(s) reach their category cessation age in the next year.");
        }
        if (!pending.isEmpty()) {
            notes.add(pending.size() + " claim(s) pending: decided on the cover in force on their event date.");
        }
        if (!income.isEmpty()) {
            notes.add(income.size() + " income benefit(s) in payment continue across the renewal (AT-GL05-03).");
        }
        if (pendingUw > 0) {
            notes.add(pendingUw + " member(s) have underwriting outstanding; their evidence stays valid.");
        }
        if (plans > 0) {
            notes.add(plans + " plan change(s) await approval: documents for the new year follow that approval.");
        }
        if (unpaid.signum() > 0) {
            notes.add(unpaid.setScale(2, RoundingMode.HALF_UP).toPlainString() + " of premium is unpaid.");
        }
        out.put("notes", notes);
        return out;
    }

    private int cessationAgeOf(BenefitCategory c) {
        CategoryVersion latest = em.createQuery("select v from CategoryVersion v where v.category = :c"
                        + " order by v.effectiveFrom desc, v.id desc", CategoryVersion.class).setParameter("c", c)
                .setMaxResults(1).getResultStream().findFirst().orElse(null);
        return latest != null && latest.getCessationAge() != null ? latest.getCessationAge() : cessationAge;
    }

    // ── views and helpers ──────────────────────────────────────────────────────────────────────────

    private SchemeRenewal find(String ref) {
        return em.createQuery("select r from SchemeRenewal r join fetch r.scheme where r.ref = :ref",
                        SchemeRenewal.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Renewal " + ref + " not found"));
    }

    private RenewalVersion latest(SchemeRenewal renewal) {
        return em.createQuery("select v from RenewalVersion v where v.renewal = :r order by v.versionNo desc",
                RenewalVersion.class).setParameter("r", renewal).setMaxResults(1).getSingleResult();
    }

    private List<RenewalTerm> terms(RenewalVersion version) {
        return em.createQuery("select t from RenewalTerm t join fetch t.category where t.version = :v"
                + " order by t.categoryCode", RenewalTerm.class).setParameter("v", version).getResultList();
    }

    private String json(Object value) {
        try {
            String text = mapper.writeValueAsString(value);
            return text.length() <= 4000 ? text : text.substring(0, 4000);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Object parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return text;
        }
    }

    private Map<String, Object> view(SchemeRenewal r) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", r.getRef());
        v.put("schemeRef", r.getScheme().getRef());
        v.put("status", r.status);
        v.put("expiringYear", r.expiringYear);
        v.put("expiringStart", r.expiringStart);
        v.put("expiringEnd", r.expiringEnd);
        v.put("nextYear", r.expiringYear + 1);
        v.put("nextStart", r.nextStart);
        v.put("nextEnd", r.nextEnd);
        v.put("recommendedChangePercent", r.recommendedChange);
        v.put("experience", parse(r.experience));
        v.put("checks", parse(r.checks));
        v.put("currentVersion", r.currentVersion);
        v.put("acceptedVersion", r.acceptedVersion);
        v.put("sponsorReference", r.sponsorReference);
        v.put("acceptanceNote", r.acceptanceNote);
        v.put("gapNote", r.gapNote);
        v.put("acceptedBy", r.acceptedBy);
        v.put("acceptedAt", r.acceptedAt);
        v.put("approvedBy", r.approvedBy);
        v.put("approvedAt", r.approvedAt);
        v.put("approvalNote", r.approvalNote);
        v.put("createdBy", r.createdBy);
        v.put("createdAt", r.createdAt);
        List<Map<String, Object>> versions = new ArrayList<>();
        for (RenewalVersion rv : em.createQuery("select v from RenewalVersion v where v.renewal = :r"
                + " order by v.versionNo desc", RenewalVersion.class).setParameter("r", r).getResultList()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("version", rv.versionNo);
            x.put("status", rv.status);
            x.put("rateChangePercent", rv.rateChangePercent);
            x.put("expiringPremium", rv.expiringPremium);
            x.put("annualPremium", rv.annualPremium);
            x.put("note", rv.note);
            x.put("createdBy", rv.createdBy);
            x.put("createdAt", rv.createdAt);
            x.put("terms", terms(rv).stream().map(t -> {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("category", t.categoryCode);
                y.put("members", t.members);
                y.put("cover", t.cover);
                y.put("expiringRate", t.expiringRate);
                y.put("proposedRate", t.proposedRate);
                y.put("expiringFreeCover", t.expiringFreeCover);
                y.put("proposedFreeCover", t.proposedFreeCover);
                y.put("cessationAge", t.cessationAge);
                y.put("expiringAnnual", t.expiringAnnual);
                y.put("proposedAnnual", t.proposedAnnual);
                return y;
            }).toList());
            versions.add(x);
        }
        v.put("versions", versions);
        return v;
    }
}
