package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.group.UnderwritingRecords.MemberDecision;
import com.nsure.group.UnderwritingRecords.MemberEvidence;
import com.nsure.integration.Events;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Group member underwriting (NS-GL03-001..006, AT-GL03-02, GL03 events).
 *
 * <p>A member referred for cover above the free-cover limit, a late entrant or a member not actively at work gets
 * evidence requirements generated ({@link GroupRecords#memberChanged}). The underwriter decides per benefit: accepted
 * amount, loading, exclusions and effective date. Every outstanding requirement must be received or waived before
 * cover is accepted. A decision above the authority limit waits for a second underwriter; one on modified terms
 * (a loading or exclusions) is an offer the member accepts or declines before it applies, and lapses when it expires
 * (MemberDecisionExpired). Only an applied decision changes cover and publishes MemberCoverAuthorised.
 */
@Service
public class MemberUnderwriting {

    static final Set<String> REQUIREMENTS = Set.of("MEDICAL_QUESTIONNAIRE", "MEDICAL_EXAMINATION", "BLOOD_PROFILE",
            "FINANCIAL_EVIDENCE", "AAW_CONFIRMATION", "HEALTH_DECLARATION");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final EvidenceChain audit;
    private final BenefitRules benefitRules;
    /** Demo defaults (D06 open): cover one underwriter may accept alone, the loading above which a second must
     * confirm, how long an offer on modified terms stays open, and how long received evidence stays valid. */
    private final BigDecimal authorityLimit;
    private final BigDecimal loadingLimit;
    private final int offerDays;
    private final int evidenceValidityMonths;

    public MemberUnderwriting(EntityManager em, PlatformSecurityContext security, GroupRecords records,
            EvidenceChain audit, BenefitRules benefitRules,
            @Value("${nsure.group.underwriting.authority-limit:2000000}") BigDecimal authorityLimit,
            @Value("${nsure.group.underwriting.loading-limit-percent:50}") BigDecimal loadingLimit,
            @Value("${nsure.group.underwriting.offer-days:30}") int offerDays,
            @Value("${nsure.group.underwriting.evidence-validity-months:12}") int evidenceValidityMonths) {
        this.em = em;
        this.security = security;
        this.records = records;
        this.audit = audit;
        this.benefitRules = benefitRules;
        this.authorityLimit = authorityLimit;
        this.loadingLimit = loadingLimit;
        this.offerDays = offerDays;
        this.evidenceValidityMonths = evidenceValidityMonths;
    }

    /** Effective-dated cover and supplementary billing (GL04/GL05): underwriting decisions update both. */
    private SupplementaryBilling supplementary;

    @org.springframework.beans.factory.annotation.Autowired
    void supplementaryBilling(SupplementaryBilling supplementary) {
        this.supplementary = supplementary;
    }

    // ── decisions ──────────────────────────────────────────────────────────────────────────────────

    /**
     * {decision ACCEPT|DECLINE, acceptedAmount?, reason?, loadingPercent?, exclusions?, effectiveDate?,
     * benefits [{benefitType, decision, acceptedAmount?, loadingPercent?, exclusions?}]?}: the underwriter's decision on
     * the member's referred cover, per benefit. Applies at once, or waits for authority or the member's acceptance.
     */
    @Transactional
    public GroupViews.MemberView decide(Long memberId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        SchemeMember member = member(memberId);
        GroupRecords.requireActive(records.lockedScheme(member.getScheme().getRef()), "an underwriting decision");
        em.refresh(member);
        com.nsure.common.ExpectedVersion.check("Member " + member.getMemberKey(), member.getRowVersion());
        if (!SchemeMember.UNDERWRITING_PENDING.equals(member.getStatus())) {
            throw new IllegalArgumentException("Member " + member.getMemberKey() + " has no cover pending underwriting");
        }
        if (!open(member).isEmpty()) {
            throw new ConflictException("Member " + member.getMemberKey() + " already has a decision "
                    + label(open(member).get(0).status) + "; it must be completed or expire first.");
        }
        String decision = GroupRecords.requiredText(body, "decision", "Decision", 20).toUpperCase(Locale.ROOT);
        String reason = GroupRecords.text(body, "reason");
        if (reason != null && reason.length() > 500) {
            throw new IllegalArgumentException("Reason must be at most 500 characters");
        }
        LocalDate effective = effectiveDate(body, member);
        List<MemberDecision> rows = new ArrayList<>();
        if ("ACCEPT".equals(decision)) {
            BigDecimal amount = GroupRecords.decimal(body, "acceptedAmount", "Accepted amount");
            amount = amount == null ? member.getRequestedCover() : GroupRecords.money(amount, "Accepted amount", false);
            if (amount.compareTo(member.getRequestedCover()) > 0) {
                throw new IllegalArgumentException("Accepted amount cannot exceed the requested cover of "
                        + member.getRequestedCover().toPlainString());
            }
            if (amount.compareTo(member.getAcceptedCover()) < 0) {
                throw new IllegalArgumentException("Accepted amount cannot be below the free cover already in force ("
                        + member.getAcceptedCover().toPlainString() + ")");
            }
            List<String> outstanding = outstanding(member);
            if (!outstanding.isEmpty()) {
                throw new IllegalArgumentException("Evidence is outstanding: " + String.join(", ", outstanding)
                        + ". Record it as received or waive it before accepting cover.");
            }
            rows.add(row(member, "GLA", "ACCEPT", member.getRequestedCover(), amount, loading(body), exclusions(body),
                    effective, reason, user));
        } else if ("DECLINE".equals(decision)) {
            if (reason == null) {
                throw new IllegalArgumentException("Please enter a reason for declining the pending cover");
            }
            rows.add(row(member, "GLA", "DECLINE", member.getRequestedCover(), member.getAcceptedCover(), null, null,
                    effective, reason, user));
        } else {
            throw new IllegalArgumentException("Decision must be ACCEPT or DECLINE");
        }
        // Per-benefit decisions on the scheme's other benefits for the member's category (NS-GL03-004).
        JsonNode benefits = body.get("benefits");
        if (benefits != null && benefits.isArray()) {
            for (JsonNode b : benefits) {
                String type = GroupRecords.requiredText(b, "benefitType", "Benefit", 30).toUpperCase(Locale.ROOT);
                if ("GLA".equals(type)) {
                    continue;
                }
                if (!schemeBenefits(member).contains(type)) {
                    throw new IllegalArgumentException(type + " is not a benefit of category "
                            + member.getCategory().getCode() + " on scheme " + member.getScheme().getRef() + ".");
                }
                String d = GroupRecords.requiredText(b, "decision", "Benefit decision", 20).toUpperCase(Locale.ROOT);
                if (!Set.of("ACCEPT", "DECLINE").contains(d)) {
                    throw new IllegalArgumentException("Each benefit decision must be ACCEPT or DECLINE");
                }
                BigDecimal amount = GroupRecords.decimal(b, "acceptedAmount", "Accepted amount");
                BigDecimal requested = amount == null ? BigDecimal.ZERO.setScale(2) : GroupRecords.money(amount,
                        "Accepted amount", true);
                rows.add(row(member, type, d, requested, "DECLINE".equals(d) ? BigDecimal.ZERO.setScale(2) : requested,
                        loading(b), exclusions(b), effective, GroupRecords.text(b, "reason"), user));
            }
        }
        MemberDecision life = rows.get(0);
        boolean modified = rows.stream().anyMatch(r -> "ACCEPT".equals(r.decision)
                && ((r.loadingPercent != null && r.loadingPercent.signum() > 0) || r.exclusions != null));
        boolean senior = "ACCEPT".equals(life.decision) && (life.acceptedAmount.compareTo(authorityLimit) > 0
                || rows.stream().anyMatch(r -> r.loadingPercent != null && r.loadingPercent.compareTo(loadingLimit) > 0));
        String status = senior ? MemberDecision.AWAITING_AUTHORITY : modified ? MemberDecision.OFFERED : MemberDecision.FINAL;
        for (MemberDecision r : rows) {
            r.status = status;
            r.offerExpiresOn = MemberDecision.OFFERED.equals(status) ? LocalDate.now().plusDays(offerDays) : null;
            em.persist(r);
        }
        audit.record(user.getUsername(), "Member underwriting decision", "SchemeMember", member.getMemberKey(),
                decision + " " + Money.display(life.acceptedAmount) + " (" + label(status) + ")");
        if (MemberDecision.FINAL.equals(status)) {
            apply(member, rows, user.getUsername());
        }
        em.flush();
        return GroupRecords.memberView(member);
    }

    /** {approve, note}: a second underwriter confirms (or rejects) a decision above the first one's authority. */
    @Transactional
    public Map<String, Object> confirmAuthority(Long memberId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        SchemeMember member = member(memberId);
        records.lockedScheme(member.getScheme().getRef());
        List<MemberDecision> rows = open(member).stream()
                .filter(r -> MemberDecision.AWAITING_AUTHORITY.equals(r.status)).toList();
        if (rows.isEmpty()) {
            throw new ConflictException("Member " + member.getMemberKey() + " has no decision awaiting authority.");
        }
        if (rows.stream().anyMatch(r -> user.getUsername().equalsIgnoreCase(r.decidedBy))) {
            throw new NoAuthorizationException("You made this decision, so another underwriter with the authority"
                    + " confirms it.");
        }
        String note = GroupRecords.requiredText(body, "note", "Note", 500);
        boolean approve = body.path("approve").asBoolean(false);
        boolean modified = rows.stream().anyMatch(r -> "ACCEPT".equals(r.decision)
                && ((r.loadingPercent != null && r.loadingPercent.signum() > 0) || r.exclusions != null));
        for (MemberDecision r : rows) {
            r.authorisedBy = user.getUsername();
            r.authorisedAt = LocalDateTime.now();
            r.status = !approve ? MemberDecision.REJECTED : modified ? MemberDecision.OFFERED : MemberDecision.FINAL;
            r.offerExpiresOn = MemberDecision.OFFERED.equals(r.status) ? LocalDate.now().plusDays(offerDays) : null;
        }
        audit.record(user.getUsername(), approve ? "Member decision authorised" : "Member decision rejected",
                "SchemeMember", member.getMemberKey(), note);
        if (approve && !modified) {
            apply(member, rows, user.getUsername());
        }
        return file(memberId);
    }

    /** {accepted, note}: the member's answer to an offer on modified terms (recorded from the signed response). */
    @Transactional
    public Map<String, Object> memberResponse(Long memberId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        SchemeMember member = member(memberId);
        records.lockedScheme(member.getScheme().getRef());
        List<MemberDecision> rows = open(member).stream().filter(r -> MemberDecision.OFFERED.equals(r.status)).toList();
        if (rows.isEmpty()) {
            throw new ConflictException("Member " + member.getMemberKey() + " has no offer waiting for an answer.");
        }
        if (rows.get(0).offerExpiresOn != null && rows.get(0).offerExpiresOn.isBefore(LocalDate.now())) {
            expire(rows, member, user.getUsername());
            throw new ConflictException("The offer expired on " + rows.get(0).offerExpiresOn + "; a new decision is needed.");
        }
        boolean accepted = body.path("accepted").asBoolean(false);
        String note = GroupRecords.text(body, "note");
        for (MemberDecision r : rows) {
            r.memberResponse = accepted ? "ACCEPTED" : "DECLINED";
            r.memberResponseNote = note;
            r.memberResponseAt = LocalDateTime.now();
            r.status = MemberDecision.FINAL;
        }
        if (accepted) {
            apply(member, rows, user.getUsername());
        } else {
            // Declining the modified terms leaves only the free cover already in force.
            BigDecimal before = member.getAcceptedCover();
            if (supplementary != null) {
                supplementary.beforeUnderwriting(member);
            }
            member.decline("Member declined the offer on modified terms" + (note == null ? "" : ": " + note),
                    user.getUsername());
            MemberChange declined = new MemberChange(member, "UNDERWRITING", rows.get(0).effectiveDate,
                    member.getSalary(), before, null, "Offer declined by the member", user.getUsername());
            records.memberChanged(declined);
            if (supplementary != null) {
                supplementary.afterUnderwriting(member, declined.getId(), user.getUsername());
            }
            authorised(member, "DECLINE", rows.get(0).effectiveDate, user.getUsername());
            records.refreshTotals(member.getScheme());
        }
        return file(memberId);
    }

    /** Applies the final decision rows to the member's cover and publishes MemberCoverAuthorised. */
    private void apply(SchemeMember member, List<MemberDecision> rows, String actor) {
        MemberDecision life = rows.get(0);
        BigDecimal before = member.getAcceptedCover();
        if (supplementary != null) {
            supplementary.beforeUnderwriting(member);
        }
        if ("ACCEPT".equals(life.decision)) {
            member.accept(life.acceptedAmount, life.loadingPercent, life.exclusions, life.reason, actor);
        } else {
            member.decline(life.reason, actor);
        }
        String perBenefit = rows.size() == 1 ? "" : "; " + String.join(", ", rows.subList(1, rows.size()).stream()
                .map(r -> r.benefitType + " " + r.decision.toLowerCase(Locale.ROOT)).toList());
        MemberChange decided = new MemberChange(member, "UNDERWRITING", life.effectiveDate, member.getSalary(), before,
                null, life.decision + (life.reason == null ? "" : ": " + life.reason) + perBenefit, actor);
        records.memberChanged(decided);
        if (supplementary != null) {
            // GL04/GL05: the decided cover applies from the date it was requested; invoiced months are re-priced.
            supplementary.afterUnderwriting(member, decided.getId(), actor);
        }
        authorised(member, life.decision, life.effectiveDate, actor);
        records.refreshTotals(member.getScheme());
    }

    private void authorised(SchemeMember member, String decision, LocalDate effective, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", member.getScheme().getRef());
        data.put("memberKey", member.getMemberKey());
        data.put("memberId", member.getId());
        data.put("decision", decision);
        data.put("acceptedCover", member.getAcceptedCover().toPlainString());
        data.put("effectiveDate", effective.toString());
        records.publish(Events.MEMBER_COVER_AUTHORISED, member.getMemberKey(), data, actor);
    }

    // ── expiry (MemberDecisionExpired) ─────────────────────────────────────────────────────────────

    /** Offers past their expiry lapse; the member's referred cover stays pending for a new decision. */
    @Transactional
    public int expireOffers() {
        List<MemberDecision> due = em.createQuery("select d from GroupMemberDecision d join fetch d.member"
                + " where d.status = :offered and d.offerExpiresOn < :today", MemberDecision.class)
                .setParameter("offered", MemberDecision.OFFERED).setParameter("today", LocalDate.now()).getResultList();
        Map<Long, List<MemberDecision>> byMember = new LinkedHashMap<>();
        due.forEach(d -> byMember.computeIfAbsent(d.member.getId(), k -> new ArrayList<>()).add(d));
        byMember.values().forEach(rows -> expire(rows, rows.get(0).member, "system"));
        return byMember.size();
    }

    /** Daily at 01:20 by default: lapse expired offers. */
    @Scheduled(cron = "${nsure.group.underwriting.expiry-cron:0 20 1 * * *}")
    public void expireOnSchedule() {
        expireOffers();
    }

    /** Runs the offer expiry now (the scheduled run, on demand). */
    @Transactional
    public Map<String, Object> runExpiry() {
        security.authenticatedUser().validateHasPermissionTo("UNDERWRITE_MEMBER");
        return Map.of("expired", expireOffers());
    }

    private void expire(List<MemberDecision> rows, SchemeMember member, String actor) {
        for (MemberDecision r : rows) {
            r.status = MemberDecision.EXPIRED;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemeRef", member.getScheme().getRef());
        data.put("memberKey", member.getMemberKey());
        data.put("memberId", member.getId());
        data.put("expiredOn", rows.get(0).offerExpiresOn == null ? LocalDate.now().toString()
                : rows.get(0).offerExpiresOn.toString());
        data.put("pendingCover", member.getPendingCover().toPlainString());
        records.publish(Events.MEMBER_DECISION_EXPIRED, member.getMemberKey(), data, actor);
        audit.record(actor, "Member decision expired", "SchemeMember", member.getMemberKey(), "Offer not answered by "
                + rows.get(0).offerExpiresOn);
    }

    // ── evidence (NS-GL03-003, NS-GL03-006) ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> evidence(Long memberId) {
        security.authenticatedUser().validateHasPermissionTo("UNDERWRITE_MEMBER");
        return evidenceOf(member(memberId)).stream().map(MemberUnderwriting::evidenceView).toList();
    }

    /** {requirement, note?}: a requirement the underwriter adds for a risk exception. */
    @Transactional
    public Map<String, Object> addEvidence(Long memberId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        SchemeMember member = member(memberId);
        String requirement = GroupRecords.requiredText(body, "requirement", "Requirement", 30).toUpperCase(Locale.ROOT);
        if (!REQUIREMENTS.contains(requirement)) {
            throw new IllegalArgumentException("Requirement must be one of " + REQUIREMENTS.stream().sorted().toList());
        }
        MemberEvidence e = GroupRecords.requirement(member, requirement, "RISK_EXCEPTION", user.getUsername());
        e.note = note(body);
        em.persist(e);
        return evidenceView(e);
    }

    /** {status RECEIVED|WAIVED, receivedOn?, note?}: received evidence stays valid for the insurer's period. */
    @Transactional
    public Map<String, Object> updateEvidence(Long evidenceId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("UNDERWRITE_MEMBER");
        MemberEvidence e = em.find(MemberEvidence.class, evidenceId);
        if (e == null) {
            throw new NotFoundException("Evidence requirement " + evidenceId + " not found");
        }
        if (!MemberEvidence.REQUESTED.equals(e.status)) {
            throw new ConflictException("This requirement is already " + e.status.toLowerCase(Locale.ROOT) + ".");
        }
        String status = GroupRecords.requiredText(body, "status", "Status", 20).toUpperCase(Locale.ROOT);
        if (!Set.of(MemberEvidence.RECEIVED, MemberEvidence.WAIVED).contains(status)) {
            throw new IllegalArgumentException("Status must be RECEIVED or WAIVED");
        }
        String note = note(body);
        if (MemberEvidence.WAIVED.equals(status) && note == null) {
            throw new IllegalArgumentException("Please give the reason for waiving the requirement");
        }
        LocalDate received = GroupRecords.text(body, "receivedOn") == null ? LocalDate.now()
                : GroupRecords.date(body, "receivedOn", "Received on");
        if (received.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Evidence cannot be received in the future");
        }
        e.status = status;
        e.note = note == null ? e.note : note;
        e.receivedBy = user.getUsername();
        e.receivedOn = received;
        e.validUntil = MemberEvidence.RECEIVED.equals(status) ? received.plusMonths(evidenceValidityMonths) : null;
        audit.record(user.getUsername(), "Member evidence " + status.toLowerCase(Locale.ROOT), "SchemeMember",
                e.member.getMemberKey(), e.requirement);
        return evidenceView(e);
    }

    // ── actively at work, category transfer (AT-GL03-02, NS-GL03-005) ──────────────────────────────

    /** {effectiveDate?, note?}: the employer confirms a member whose cover was deferred is actively at work. */
    @Transactional
    public GroupViews.MemberView backAtWork(Long memberId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        SchemeMember member = member(memberId);
        GroupRecords.requireActive(records.lockedScheme(member.getScheme().getRef()), "a return to work");
        LocalDate effective = effectiveDate(body, member);
        member.backAtWork();
        records.memberChanged(new MemberChange(member, "BACK_AT_WORK", effective, member.getSalary(),
                BigDecimal.ZERO.setScale(2), null, "Actively at work from " + effective + (note(body) == null ? ""
                        : ": " + note(body)), user.getUsername()));
        evidenceOf(member).stream().filter(e -> "AAW_CONFIRMATION".equals(e.requirement)
                && MemberEvidence.REQUESTED.equals(e.status)).forEach(e -> {
                    e.status = MemberEvidence.RECEIVED;
                    e.receivedBy = user.getUsername();
                    e.receivedOn = effective;
                    e.validUntil = effective.plusMonths(evidenceValidityMonths);
                });
        records.refreshTotals(member.getScheme());
        em.flush();
        return GroupRecords.memberView(member);
    }

    /**
     * {categoryCode, effectiveDate?, reason?}: moves a member to another category (NS-GL03-005). The episode in the
     * old category ends the day before; a new one starts in the new category on its formula. Cover already accepted
     * continues up to that amount (the demo scheme rule); anything above it and the free-cover limit is referred.
     */
    @Transactional
    public GroupViews.MemberView transferCategory(Long memberId, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_SCHEME");
        SchemeMember member = member(memberId);
        GroupScheme scheme = records.lockedScheme(member.getScheme().getRef());
        GroupRecords.requireActive(scheme, "a category transfer");
        if (!SchemeMember.ON_COVER.contains(member.getStatus()) || SchemeMember.COVER_DEFERRED.equals(member.getStatus())) {
            throw new IllegalArgumentException("Member " + member.getMemberKey() + " is not on cover");
        }
        if (!open(member).isEmpty()) {
            throw new ConflictException("Complete the open underwriting decision before moving the member.");
        }
        String code = GroupRecords.requiredText(body, "categoryCode", "Category", 20).toUpperCase(Locale.ROOT);
        BenefitCategory target = records.categories(scheme).stream().filter(c -> c.getCode().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Category " + code + " is not on scheme " + scheme.getRef()));
        if (target.getId().equals(member.getCategory().getId())) {
            throw new IllegalArgumentException("Member " + member.getMemberKey() + " is already in category " + code);
        }
        LocalDate effective = effectiveDate(body, member);
        if (effective.equals(member.getJoinDate())) {
            throw new IllegalArgumentException("The transfer must be dated after the member's current entry ("
                    + member.getJoinDate() + ")");
        }
        int age = java.time.Period.between(member.getDateOfBirth(), effective).getYears();
        if (target.getExitAge() != null && age >= target.getExitAge()) {
            throw new IllegalArgumentException("Member " + member.getMemberKey() + " is " + age + ", at or past category "
                    + code + "'s exit age of " + target.getExitAge());
        }
        BigDecimal before = member.getAcceptedCover();
        if (supplementary != null) {
            supplementary.beforeUnderwriting(member); // the cover in force so far is on record (GL04)
        }
        member.cease(effective.minusDays(1));
        String reason = GroupRecords.text(body, "reason");
        records.memberChanged(new MemberChange(member, "CATEGORY_OUT", effective, member.getSalary(), before, null,
                "Moved to category " + code + (reason == null ? "" : ": " + reason), user.getUsername()));
        SchemeMember moved = new SchemeMember(scheme, target, member.getMemberKey(), member.getFirstName(),
                member.getLastName(), member.getDateOfBirth(), member.getSalary(), effective, null);
        moved.carryOver(member);
        em.persist(moved);
        records.memberChanged(new MemberChange(moved, "CATEGORY_IN", effective, null, before, null, "Moved from category "
                + member.getCategory().getCode() + (SchemeMember.UNDERWRITING_PENDING.equals(moved.getStatus())
                        ? "; cover above what was accepted and the free-cover limit is referred" : ""),
                user.getUsername()));
        if (supplementary != null) {
            // NS-GL05-002: a transfer dated into invoiced months re-prices them with supplementary adjustments.
            em.flush();
            supplementary.reconcile(scheme, Map.of(member.getId(), effective, moved.getId(), effective),
                    "Category transfer " + member.getMemberKey(), user.getUsername());
        }
        records.refreshTotals(scheme);
        em.flush();
        return GroupRecords.memberView(moved);
    }

    // ── the underwriting file ──────────────────────────────────────────────────────────────────────

    /**
     * The member's underwriting file (GL03 workspace): cover, evidence (medical requirements shown to underwriters
     * only), decision history, the member's episodes and category history, and aggregate exposure for the same person
     * across group schemes (matched on name and date of birth).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> file(Long memberId) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("READ_SCHEME");
        SchemeMember m = member(memberId);
        boolean assessor = user.hasPermissionTo("UNDERWRITE_MEMBER");
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("member", GroupRecords.memberView(m));
        v.put("schemeRef", m.getScheme().getRef());
        v.put("aawRule", m.getScheme().getAawRule());
        v.put("partialCoverAllowed", m.getScheme().isPartialCoverAllowed());
        v.put("freeCoverLimit", m.getCategory().getFreeCoverLimit());
        v.put("loadingPercent", m.getLoadingPercent());
        v.put("exclusions", m.getExclusions());
        v.put("authorityLimit", authorityLimit);
        v.put("benefits", schemeBenefits(m));
        v.put("evidence", evidenceOf(m).stream().map(e -> {
            Map<String, Object> ev = evidenceView(e);
            if (!assessor && "YES".equals(e.restricted)) {
                ev.put("note", null); // restricted evidence stays with authorised assessors
            }
            return ev;
        }).toList());
        v.put("outstanding", outstanding(m));
        v.put("decisions", em.createQuery("select d from GroupMemberDecision d where d.member.memberKey = :k"
                + " and d.member.scheme = :s order by d.id desc", MemberDecision.class).setParameter("k", m.getMemberKey())
                .setParameter("s", m.getScheme()).getResultList().stream().map(MemberUnderwriting::decisionView).toList());
        v.put("episodes", em.createQuery("select x from SchemeMember x join fetch x.category where x.scheme = :s"
                + " and x.memberKey = :k order by x.joinDate, x.id", SchemeMember.class).setParameter("s", m.getScheme())
                .setParameter("k", m.getMemberKey()).getResultList().stream().map(x -> {
                    Map<String, Object> ep = new LinkedHashMap<>();
                    ep.put("memberId", x.getId());
                    ep.put("category", x.getCategory().getCode());
                    ep.put("from", x.getJoinDate());
                    ep.put("to", x.getExitDate());
                    ep.put("status", x.getStatus());
                    ep.put("acceptedCover", x.getAcceptedCover());
                    return ep;
                }).toList());
        List<Map<String, Object>> elsewhere = new ArrayList<>();
        BigDecimal total = m.getAcceptedCover().add(m.getPendingCover());
        for (SchemeMember o : em.createQuery("select x from SchemeMember x join fetch x.scheme where x.id <> :id"
                        + " and lower(x.firstName) = :f and lower(x.lastName) = :l and x.dateOfBirth = :d"
                        + " and x.status in :statuses and x.scheme <> :s", SchemeMember.class)
                .setParameter("id", m.getId()).setParameter("f", m.getFirstName().toLowerCase(Locale.ROOT))
                .setParameter("l", m.getLastName().toLowerCase(Locale.ROOT)).setParameter("d", m.getDateOfBirth())
                .setParameter("statuses", SchemeMember.ON_COVER).setParameter("s", m.getScheme()).getResultList()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("schemeRef", o.getScheme().getRef());
            e.put("memberKey", o.getMemberKey());
            e.put("acceptedCover", o.getAcceptedCover());
            e.put("pendingCover", o.getPendingCover());
            elsewhere.add(e);
            total = total.add(o.getAcceptedCover()).add(o.getPendingCover());
        }
        v.put("otherGroupCover", elsewhere);
        v.put("aggregateExposure", total);
        return v;
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private SchemeMember member(Long id) {
        SchemeMember m = em.find(SchemeMember.class, id);
        if (m == null) {
            throw new NotFoundException("Member " + id + " not found");
        }
        return m;
    }

    private List<MemberDecision> open(SchemeMember m) {
        return em.createQuery("select d from GroupMemberDecision d where d.member = :m and d.status in :open"
                + " order by d.id", MemberDecision.class).setParameter("m", m)
                .setParameter("open", Set.of(MemberDecision.AWAITING_AUTHORITY, MemberDecision.OFFERED)).getResultList();
    }

    private List<MemberEvidence> evidenceOf(SchemeMember m) {
        return em.createQuery("select e from GroupMemberEvidence e where e.member = :m order by e.id", MemberEvidence.class)
                .setParameter("m", m).getResultList();
    }

    private List<String> outstanding(SchemeMember m) {
        return evidenceOf(m).stream().filter(e -> MemberEvidence.REQUESTED.equals(e.status))
                .map(e -> label(e.requirement)).toList();
    }

    /** The scheme benefits the member's category has (a category may leave one out). */
    private List<String> schemeBenefits(SchemeMember m) {
        List<String> all = em.createQuery("select b.benefitType from SchemeBenefit b where b.schemeRef = :r"
                + " order by b.benefitType", String.class).setParameter("r", m.getScheme().getRef()).getResultList();
        return (all.isEmpty() ? List.of("GLA") : all).stream()
                .filter(t -> benefitRules.included(m.getScheme().getRef(), m.getCategory().getCode(), t, LocalDate.now()))
                .toList();
    }

    private MemberDecision row(SchemeMember m, String benefit, String decision, BigDecimal requested, BigDecimal accepted,
            BigDecimal loading, String exclusions, LocalDate effective, String reason, AppUser user) {
        MemberDecision d = new MemberDecision();
        d.member = m;
        d.benefitType = benefit;
        d.decision = decision;
        d.requestedAmount = requested;
        d.acceptedAmount = accepted;
        d.pendingAmount = BigDecimal.ZERO.setScale(2);
        d.loadingPercent = loading;
        d.exclusions = exclusions;
        d.effectiveDate = effective;
        d.reason = reason;
        d.decidedBy = user.getUsername();
        d.decidedAt = LocalDateTime.now();
        return d;
    }

    private static LocalDate effectiveDate(JsonNode body, SchemeMember m) {
        LocalDate d = GroupRecords.text(body, "effectiveDate") == null ? LocalDate.now()
                : GroupRecords.date(body, "effectiveDate", "Effective date");
        if (d.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The effective date cannot be in the future");
        }
        if (d.isBefore(m.getJoinDate())) {
            throw new IllegalArgumentException("The effective date cannot be before the member's entry on " + m.getJoinDate());
        }
        return d;
    }

    private static BigDecimal loading(JsonNode body) {
        BigDecimal l = GroupRecords.decimal(body, "loadingPercent", "Loading");
        if (l == null || l.signum() == 0) {
            return null;
        }
        if (l.signum() < 0 || l.compareTo(BigDecimal.valueOf(300)) > 0) {
            throw new IllegalArgumentException("Loading must be from 0 to 300 percent");
        }
        return l.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static String exclusions(JsonNode body) {
        String e = GroupRecords.text(body, "exclusions");
        if (e != null && e.length() > 500) {
            throw new IllegalArgumentException("Exclusions must be at most 500 characters");
        }
        return e;
    }

    private static String note(JsonNode body) {
        String n = GroupRecords.text(body, "note");
        if (n != null && n.length() > 500) {
            throw new IllegalArgumentException("Note must be at most 500 characters");
        }
        return n;
    }

    static Map<String, Object> evidenceView(MemberEvidence e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", e.id);
        v.put("requirement", e.requirement);
        v.put("reason", e.reason);
        v.put("restricted", "YES".equals(e.restricted));
        v.put("status", e.status);
        v.put("note", e.note);
        v.put("requestedAt", e.requestedAt);
        v.put("receivedOn", e.receivedOn);
        v.put("receivedBy", e.receivedBy);
        v.put("validUntil", e.validUntil);
        return v;
    }

    static Map<String, Object> decisionView(MemberDecision d) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", d.id);
        v.put("memberId", d.member.getId());
        v.put("benefitType", d.benefitType);
        v.put("decision", d.decision);
        v.put("requestedAmount", d.requestedAmount);
        v.put("acceptedAmount", d.acceptedAmount);
        v.put("loadingPercent", d.loadingPercent);
        v.put("exclusions", d.exclusions);
        v.put("effectiveDate", d.effectiveDate);
        v.put("reason", d.reason);
        v.put("status", d.status);
        v.put("decidedBy", d.decidedBy);
        v.put("decidedAt", d.decidedAt);
        v.put("authorisedBy", d.authorisedBy);
        v.put("offerExpiresOn", d.offerExpiresOn);
        v.put("memberResponse", d.memberResponse);
        return v;
    }

    static String label(String code) {
        String t = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }
}
