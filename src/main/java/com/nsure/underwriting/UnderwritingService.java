package com.nsure.underwriting;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.ApplicationRecords;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.InsuranceApplication;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyLifeCover;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Role;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.repository.RoleRepository;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.underwriting.UnderwritingRecords.Authority;
import com.nsure.underwriting.UnderwritingRecords.Case;
import com.nsure.underwriting.UnderwritingRecords.Decision;
import com.nsure.underwriting.UnderwritingRecords.Evidence;
import com.nsure.underwriting.UnderwritingRecords.RuleChange;
import com.nsure.underwriting.UnderwritingRecords.RuleSet;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IL02 Individual underwriting (Nsure Life NS-IL02-001..006).
 *
 * <p>Every submitted application gets a case. The product's rule set assesses it: a case inside every limit is
 * accepted on standard terms by the rules, anything else is referred with the reasons and the evidence asked for.
 * An underwriter then requests or clears evidence and records a decision within their authority: accept standard,
 * accept with a loading and/or exclusion, postpone or decline. Changed terms become a numbered offer the applicant
 * must accept before issue; a later decision never reuses an earlier acceptance. Nothing here creates cover.
 */
@Service
public class UnderwritingService {

    public static final String REFERRED = "REFERRED";
    public static final String EVIDENCE_PENDING = "EVIDENCE_PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String OFFERED = "OFFERED";
    public static final String POSTPONED = "POSTPONED";
    public static final String DECLINED = "DECLINED";

    public static final String ACCEPT_STANDARD = "ACCEPT_STANDARD";
    public static final String ACCEPT_WITH_TERMS = "ACCEPT_WITH_TERMS";
    public static final String POSTPONE = "POSTPONE";
    public static final String DECLINE = "DECLINE";

    static final String OFFER_NONE = "NONE";
    static final String OFFER_PENDING = "PENDING";
    static final String OFFER_ACCEPTED = "ACCEPTED";
    static final String OFFER_REFUSED = "REFUSED";

    static final String SCREEN_NOT_SCREENED = "NOT_SCREENED";
    static final String SCREEN_CLEAR = "CLEAR";
    static final String SCREEN_HIT = "HIT";
    static final String SCREEN_CLEARED = "CLEARED";
    static final String SCREEN_CONFIRMED = "CONFIRMED";

    static final String CHANGE_PENDING = "PENDING";
    static final String CHANGE_APPROVED = "APPROVED";
    static final String CHANGE_REJECTED = "REJECTED";

    static final String EV_REQUESTED = "REQUESTED";
    static final String EV_RECEIVED = "RECEIVED";
    static final String EV_WAIVED = "WAIVED";

    private static final String RULES_ACTOR = "rules";
    private static final Set<String> IN_FORCE = Set.of("Active", "Pending Payment", "Active - No Cover");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int EVIDENCE_DUE_DAYS = 14;
    /** Evidence due within this many days (or overdue) gets a reminder, at most once a week (NS-IL02-002). */
    private static final int REMINDER_LEAD_DAYS = 3;
    private static final int REMINDER_INTERVAL_DAYS = 7;
    private static final Set<String> DECISION_TYPES = Set.of(ACCEPT_STANDARD, ACCEPT_WITH_TERMS, POSTPONE, DECLINE);

    /**
     * What the signed-in user may decide (NS-IL02-004): the largest aggregate exposure and loading, and the decision
     * types. A null limit or type set means not limited.
     */
    record Mandate(BigDecimal maxSumAssured, BigDecimal maxLoadingPercent, Set<String> decisionTypes) {
        boolean covers(BigDecimal exposure) {
            return maxSumAssured == null || exposure.compareTo(maxSumAssured) <= 0;
        }

        boolean allows(String decision) {
            return decisionTypes == null || decisionTypes.contains(decision);
        }
    }

    /** The terms an issued policy takes from underwriting; {@code premium} is null when the applied premium stands. */
    public record Terms(String caseRef, BigDecimal premium, String description) {
    }

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final EvidenceChain audit;
    private final RoleRepository roles;
    /** The application's quote takes each accepted offer's premium as a new version (AT-IL02-02). */
    private final com.nsure.portfolio.QuoteService quotes;
    /** The application's timeline records each underwriting step that moves it (IL01 workspace). */
    private final com.nsure.portfolio.WorkflowHistory history;

    public UnderwritingService(EntityManager em, PlatformSecurityContext security, Outbox outbox,
            ReferenceNumbers numbers, EvidenceChain audit, RoleRepository roles,
            com.nsure.portfolio.QuoteService quotes, com.nsure.portfolio.WorkflowHistory history) {
        this.quotes = quotes;
        this.history = history;
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.audit = audit;
        this.roles = roles;
    }

    // ── assessment (NS-IL02-001, NS-IL01-005) ─────────────────────────────────────────────────────

    /**
     * Opens the case for a submitted application and assesses it against the product's rules. Called in the
     * submitting transaction. An undecided case is assessed again; a decided one is left as it is.
     */
    @Transactional
    public Case assess(InsuranceApplication app, CustomerAccount customer, String actor) {
        Case c = byApplication(app.getRef());
        LocalDateTime now = LocalDateTime.now();
        if (c == null) {
            c = new Case();
            c.ref = numbers.next("UW-", 6, () -> 0);
            c.applicationRef = app.getRef();
            c.createdAt = now;
            c.offerStatus = OFFER_NONE;
        } else if (c.decision != null) {
            return c;
        }
        RuleSet rules = rulesFor(app.getProduct());
        c.customerRef = app.getCustomerRef();
        c.customerName = app.getCustomerName();
        c.product = app.getProduct();
        c.sumAssured = Money.of(app.getAmount() == null ? BigDecimal.ZERO : app.getAmount());
        c.appliedPremium = Money.of(app.getPremium() == null ? BigDecimal.ZERO : app.getPremium());
        c.ruleSetVersion = rules.versionLabel;
        c.entryAge = entryAge(app, customer);
        c.aggregateExposure = exposureOf(app.getCustomerRef()).add(c.sumAssured);
        c.occupation = app.getOccupation();
        c.updatedAt = now;

        List<String> reasons = new ArrayList<>();
        List<String[]> wanted = new ArrayList<>(); // {requirement, restricted}
        // Rated products already checked each life's age when they were priced; an unknown age is not guessed here.
        if (c.entryAge != null && (c.entryAge < rules.minEntryAge || c.entryAge > rules.maxEntryAge)) {
            reasons.add("Entry age " + c.entryAge + " is outside " + rules.minEntryAge + "–" + rules.maxEntryAge
                    + " for " + c.product + ".");
        }
        if (c.sumAssured.compareTo(rules.autoMaxSumAssured) > 0) {
            reasons.add("Sum assured " + Money.text(c.sumAssured) + " is above the automatic acceptance limit of "
                    + Money.text(rules.autoMaxSumAssured) + ".");
        }
        if (c.sumAssured.compareTo(rules.medicalEvidenceAbove) > 0) {
            reasons.add("Sum assured is above the medical evidence limit of "
                    + Money.text(rules.medicalEvidenceAbove) + ".");
            wanted.add(new String[] {"Medical examination report", "true"});
        }
        if (c.aggregateExposure.compareTo(rules.aggregateExposureLimit) > 0) {
            reasons.add("Total cover with this application is " + Money.text(c.aggregateExposure)
                    + ", above the aggregate exposure limit of " + Money.text(rules.aggregateExposureLimit) + ".");
            wanted.add(new String[] {"Financial evidence (proof of income)", "false"});
        }
        Map<String, String> answers = answers(app.getRef());
        if (rules.referTobacco && yes(answers.get("TOBACCO"))) {
            reasons.add("Tobacco or nicotine use was disclosed.");
        }
        if (rules.referTreatment && yes(answers.get("TREATMENT"))) {
            reasons.add("Current treatment or pending results for a serious illness were disclosed.");
            wanted.add(new String[] {"Attending doctor's report", "true"});
        }
        // NS-IL02-001: further disclosure questions the rule set names refer on a YES answer.
        for (String code : codes(rules.referDisclosureCodes)) {
            if (yes(answers.get(code))) {
                reasons.add("The answer to disclosure " + code + " was YES (" + rules.versionLabel + ").");
            }
        }
        // NS-IL02-001: an occupation the rule set lists is referred, never assessed by a guessed threshold.
        String occupationHit = occupationMatch(rules.referOccupations, c.occupation);
        if (occupationHit != null) {
            reasons.add("Occupation \"" + c.occupation + "\" matches the referral list (" + occupationHit + ").");
            wanted.add(new String[] {"Occupation questionnaire", "false"});
        }
        // NS-IL01-006, NS-IL02-006: every name on the application is screened; a possible match blocks acceptance and
        // issue until compliance clears it. A match already cleared stays cleared when the case is reassessed.
        if (!SCREEN_CLEARED.equals(c.screeningStatus)) {
            String hit = screen(app, customer);
            c.screeningStatus = hit == null ? SCREEN_CLEAR : SCREEN_HIT;
            c.screeningDetail = hit;
            c.screeningResolvedBy = null;
            c.screeningResolvedAt = null;
            if (hit != null) {
                reasons.add("Screening: " + hit + " Compliance must clear or confirm the match before the case can be"
                        + " accepted.");
            }
        }

        boolean isNew = c.id == null;
        if (reasons.isEmpty()) {
            c.status = ACCEPTED;
            c.referralReasons = null;
            em.persist(c);
            em.flush();
            decideByRules(c, now);
            app.setStatus(applicationStatusWhenCleared(app));
        } else {
            c.referralReasons = String.join("\n", reasons);
            c.status = REFERRED;
            em.persist(c);
            em.flush();
            for (String[] w : wanted) {
                requestEvidenceRow(c, c.customerName, w[0], Boolean.parseBoolean(w[1]),
                        LocalDate.now().plusDays(EVIDENCE_DUE_DAYS), RULES_ACTOR);
            }
            if (!wanted.isEmpty()) {
                c.status = EVIDENCE_PENDING;
            }
            if (isNew) {
                audit.record(actor, "Referred to underwriting", "UnderwritingCase", c.ref,
                        reasons.size() + " reason(s) under " + rules.versionLabel);
            }
        }
        return c;
    }

    private void decideByRules(Case c, LocalDateTime now) {
        c.decision = ACCEPT_STANDARD;
        c.decisionReason = "Within every automatic acceptance limit of " + c.ruleSetVersion + ".";
        c.decidedBy = RULES_ACTOR;
        c.decidedAt = now;
        c.decisionValidUntil = now.toLocalDate().plusDays(rulesFor(c.product).decisionValidityDays);
        addDecision(c, now);
        publishDecided(c, RULES_ACTOR);
    }

    // ── queries ───────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UnderwritingViews.CaseView> cases(String status) {
        AppUser user = user("READ_UNDERWRITING");
        Mandate authority = mandateOf(user);
        Map<String, Long> outstanding = outstandingEvidence();
        return em.createQuery("select c from UwCase c order by c.id desc", Case.class).getResultList().stream()
                .filter(c -> status == null || status.isBlank() || status.equalsIgnoreCase(c.status))
                .map(c -> view(c, outstanding.getOrDefault(c.ref, 0L).intValue(), authority))
                .toList();
    }

    @Transactional(readOnly = true)
    public UnderwritingViews.Summary summary() {
        user("READ_UNDERWRITING");
        List<Case> all = em.createQuery("select c from UwCase c", Case.class).getResultList();
        LocalDate today = LocalDate.now();
        return new UnderwritingViews.Summary(
                all.stream().filter(c -> REFERRED.equals(c.status)).count(),
                all.stream().filter(c -> EVIDENCE_PENDING.equals(c.status)).count(),
                all.stream().filter(c -> OFFERED.equals(c.status)).count(),
                all.stream().filter(c -> ACCEPTED.equals(c.status) && c.decidedAt != null
                        && c.decidedAt.toLocalDate().equals(today)).count(),
                all.stream().filter(c -> RULES_ACTOR.equals(c.decidedBy)).count(),
                all.size());
    }

    @Transactional(readOnly = true)
    public UnderwritingViews.CaseDetail detail(String ref) {
        AppUser user = user("READ_UNDERWRITING");
        return detail(find(ref), user);
    }

    /** The case of an application, or 404 when it has not been submitted yet. */
    @Transactional(readOnly = true)
    public UnderwritingViews.CaseDetail detailForApplication(String applicationRef) {
        AppUser user = user("READ_UNDERWRITING");
        Case c = byApplication(applicationRef);
        if (c == null) {
            throw new NotFoundException(applicationRef + " has no underwriting case yet. It opens when the application"
                    + " is submitted.");
        }
        return detail(c, user);
    }

    private UnderwritingViews.CaseDetail detail(Case c, AppUser user) {
        boolean clinical = user.hasPermissionTo("UNDERWRITE_APPLICATION");
        LocalDate today = LocalDate.now();
        List<UnderwritingViews.EvidenceView> evidence = evidence(c.ref).stream()
                .map(e -> new UnderwritingViews.EvidenceView(e.id, e.lifeName, e.requirement, e.restricted, e.status,
                        e.dueDate, EV_REQUESTED.equals(e.status) && e.dueDate.isBefore(today), e.requestedBy,
                        e.requestedAt, e.resolvedBy, e.resolvedAt,
                        // NS-IL02-002, AT-IL02-03: the request is visible, the clinical detail is not.
                        e.restricted && !clinical ? null : e.note, e.lifeId, e.reminderCount, e.lastRemindedAt))
                .toList();
        List<UnderwritingViews.DecisionView> decisions = em.createQuery(
                        "select d from UwDecision d where d.caseRef = :ref order by d.sequenceNo", Decision.class)
                .setParameter("ref", c.ref).getResultList().stream()
                .map(d -> new UnderwritingViews.DecisionView(d.sequenceNo, d.decision, d.loadingPercent, d.exclusion,
                        d.postponeUntil, d.reason, d.offerVersion, d.offerPremium, d.decidedBy, d.decidedAt,
                        d.lifeRef, d.benefit, d.sumAssured, d.evidenceBasis))
                .toList();
        List<UnderwritingViews.ExposureLine> exposure = c.customerRef == null ? List.of()
                : em.createQuery("select p from InsurancePolicy p where p.customerRef = :ref order by p.id desc",
                                InsurancePolicy.class)
                        .setParameter("ref", c.customerRef).getResultList().stream()
                        .filter(p -> IN_FORCE.contains(p.getStatus()))
                        .map(p -> new UnderwritingViews.ExposureLine(p.getRef(), p.getProduct(), p.getAmount(),
                                p.getStatus()))
                        .toList();
        List<UnderwritingViews.LifeView> lives = em.createQuery(
                        "select l from PolicyLifeCover l where l.application.ref = :ref order by l.displayOrder",
                        PolicyLifeCover.class)
                .setParameter("ref", c.applicationRef).getResultList().stream()
                .map(l -> new UnderwritingViews.LifeView(l.getId(), l.getFullName(), l.getLifeType(),
                        l.getDateOfBirth(), l.getAgeAtEntry(), l.getCoverAmount()))
                .toList();
        int outstanding = (int) evidence.stream().filter(e -> EV_REQUESTED.equals(e.status())).count();
        return new UnderwritingViews.CaseDetail(view(c, outstanding, mandateOf(user)), lives, evidence, decisions,
                exposure, ruleView(rulesFor(c.product)));
    }

    // ── evidence (NS-IL02-002) ────────────────────────────────────────────────────────────────────

    /** {lifeName?, requirement, restricted?, dueDate?} */
    @Transactional
    public UnderwritingViews.CaseDetail requestEvidence(String ref, JsonNode body) {
        AppUser user = user("UNDERWRITE_APPLICATION");
        Case c = find(ref);
        requireOpen(c);
        String requirement = required(body, "requirement", 150);
        String life = optional(body, "lifeName");
        LocalDate due = optional(body, "dueDate") == null ? LocalDate.now().plusDays(EVIDENCE_DUE_DAYS)
                : LocalDate.parse(optional(body, "dueDate"));
        if (due.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("The due date cannot be in the past.");
        }
        // NS-IL02-002: evidence is asked of one insured life on the application when the caller names it.
        PolicyLifeCover insured = null;
        if (body.hasNonNull("lifeId") && !body.get("lifeId").asText().isBlank()) {
            long lifeId = body.get("lifeId").asLong();
            insured = em.createQuery("select l from PolicyLifeCover l where l.id = :id and l.application.ref = :app",
                            PolicyLifeCover.class)
                    .setParameter("id", lifeId).setParameter("app", c.applicationRef).getResultStream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("That life is not on " + c.applicationRef + "."));
            life = insured.getFullName();
        }
        Evidence row = requestEvidenceRow(c, life == null ? c.customerName : clip(life, 150), requirement,
                body.path("restricted").asBoolean(false), due, user.getUsername());
        row.lifeId = insured == null ? null : insured.getId();
        c.status = EVIDENCE_PENDING;
        c.updatedAt = LocalDateTime.now();
        return detail(c, user);
    }

    /** {status RECEIVED|WAIVED, note}: what arrived and was checked, or why it is no longer needed. */
    @Transactional
    public UnderwritingViews.CaseDetail resolveEvidence(String ref, Long evidenceId, JsonNode body) {
        AppUser user = user("UNDERWRITE_APPLICATION");
        Case c = find(ref);
        Evidence e = em.find(Evidence.class, evidenceId);
        if (e == null || !e.caseRef.equals(c.ref)) {
            throw new NotFoundException("That evidence request is not on " + ref + ".");
        }
        if (!EV_REQUESTED.equals(e.status)) {
            throw new ConflictException("This evidence is already " + e.status.toLowerCase(Locale.ROOT) + ".");
        }
        String status = required(body, "status", 20).toUpperCase(Locale.ROOT);
        if (!Set.of(EV_RECEIVED, EV_WAIVED).contains(status)) {
            throw new IllegalArgumentException("status must be RECEIVED or WAIVED.");
        }
        String note = required(body, "note", 500);
        if (note.length() < 5) {
            throw new IllegalArgumentException("Say what was received and checked, or why it is waived.");
        }
        e.status = status;
        e.note = note;
        e.resolvedBy = user.getUsername();
        e.resolvedAt = LocalDateTime.now();
        em.flush();
        if (EVIDENCE_PENDING.equals(c.status) && outstanding(c.ref) == 0) {
            c.status = REFERRED;
        }
        c.updatedAt = LocalDateTime.now();
        audit.record(user.getUsername(), "Evidence " + status.toLowerCase(Locale.ROOT), "UnderwritingCase", c.ref,
                e.requirement);
        return detail(c, user);
    }

    private Evidence requestEvidenceRow(Case c, String life, String requirement, boolean restricted, LocalDate due,
            String actor) {
        Evidence e = new Evidence();
        e.caseRef = c.ref;
        e.lifeName = life == null ? "Life insured" : life;
        e.requirement = requirement;
        e.restricted = restricted;
        e.status = EV_REQUESTED;
        e.dueDate = due;
        e.requestedBy = actor;
        e.requestedAt = LocalDateTime.now();
        em.persist(e);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseRef", c.ref);
        data.put("applicationRef", c.applicationRef);
        data.put("customerRef", c.customerRef == null ? "" : c.customerRef);
        data.put("requirement", requirement);
        data.put("dueDate", due.toString());
        outbox.publish(Events.EVIDENCE_REQUESTED, c.ref, data, actor);
        return e;
    }

    // ── decisions (NS-IL02-003/004/005) ───────────────────────────────────────────────────────────

    /** {decision, loadingPercent?, exclusion?, postponeUntil?, reason} */
    @Transactional
    public UnderwritingViews.CaseDetail decide(String ref, JsonNode body) {
        AppUser user = user("UNDERWRITE_APPLICATION");
        Case c = find(ref);
        InsuranceApplication app = application(c);
        if ("Issued".equalsIgnoreCase(app.getStatus())) {
            throw new ConflictException(app.getRef() + " is already issued; its underwriting cannot change.");
        }
        String decision = required(body, "decision", 30).toUpperCase(Locale.ROOT);
        if (!Set.of(ACCEPT_STANDARD, ACCEPT_WITH_TERMS, POSTPONE, DECLINE).contains(decision)) {
            throw new IllegalArgumentException(
                    "decision must be ACCEPT_STANDARD, ACCEPT_WITH_TERMS, POSTPONE or DECLINE.");
        }
        String reason = required(body, "reason", 1000);
        if (reason.length() < 10) {
            throw new IllegalArgumentException("Give the reason for the decision (at least 10 characters).");
        }
        // NS-IL02-004: nobody decides the case of an application they submitted, and nobody decides above their limit.
        if (user.getUsername().equalsIgnoreCase(app.getSubmittedBy())) {
            throw new NoAuthorizationException("You submitted " + app.getRef()
                    + ", so another underwriter must decide it.");
        }
        // The facts as they stand now (a revised quote may have changed them since the case was assessed).
        c.sumAssured = Money.of(app.getAmount() == null ? BigDecimal.ZERO : app.getAmount());
        c.aggregateExposure = exposureOf(app.getCustomerRef()).add(c.sumAssured);
        // AT-IL02-01: authority is measured on the aggregate exposure (all cover in force plus this application).
        Mandate mandate = mandateOf(user);
        if (!mandate.covers(c.aggregateExposure)) {
            throw new NoAuthorizationException("The total cover of " + Money.text(c.aggregateExposure)
                    + " (aggregate exposure) is above your underwriting authority of "
                    + Money.text(mandate.maxSumAssured()) + ". Refer the case to a senior underwriter.");
        }
        if (!mandate.allows(decision)) {
            throw new NoAuthorizationException("Your underwriting authority does not include "
                    + decision.replace('_', ' ').toLowerCase(Locale.ROOT) + ". Refer the case to a senior underwriter.");
        }
        boolean accepting = ACCEPT_STANDARD.equals(decision) || ACCEPT_WITH_TERMS.equals(decision);
        if (accepting && outstanding(c.ref) > 0) {
            throw new IllegalArgumentException(ref + " still has evidence outstanding. Record it as received or waive"
                    + " it before accepting.");
        }
        if (SCREEN_CONFIRMED.equals(c.screeningStatus)) {
            throw new ConflictException(ref + " was declined on a confirmed screening match.");
        }
        if (accepting && SCREEN_HIT.equals(c.screeningStatus)) {
            throw new IllegalArgumentException(ref + " has a possible screening match. Compliance must clear it before"
                    + " the case can be accepted.");
        }
        RuleSet rules = rulesFor(c.product);
        BigDecimal loading = null;
        String exclusion = null;
        LocalDate postponeUntil = null;
        if (ACCEPT_WITH_TERMS.equals(decision)) {
            if (body.hasNonNull("loadingPercent") && !body.get("loadingPercent").asText().isBlank()) {
                loading = new BigDecimal(body.get("loadingPercent").asText().trim()).setScale(2, RoundingMode.HALF_UP);
                // NS-IL02-005: the rule set's approved ceiling, and the decider's own (NS-IL02-004).
                if (loading.signum() < 0 || loading.compareTo(rules.maxLoadingPercent) > 0) {
                    throw new IllegalArgumentException("The loading must be between 0% and "
                            + rules.maxLoadingPercent.stripTrailingZeros().toPlainString() + "% under "
                            + rules.versionLabel + ".");
                }
                if (mandate.maxLoadingPercent() != null && loading.compareTo(mandate.maxLoadingPercent()) > 0) {
                    throw new NoAuthorizationException("A loading of " + loading.stripTrailingZeros().toPlainString()
                            + "% is above your authority of " + mandate.maxLoadingPercent().stripTrailingZeros()
                            .toPlainString() + "%. Refer the case to a senior underwriter.");
                }
                if (loading.signum() == 0) {
                    loading = null;
                }
            }
            exclusion = optional(body, "exclusion");
            if (loading == null && exclusion == null) {
                throw new IllegalArgumentException("Accepting with terms needs a loading, an exclusion, or both.");
            }
        } else if (POSTPONE.equals(decision)) {
            String until = optional(body, "postponeUntil");
            if (until == null) {
                throw new IllegalArgumentException("Say the date the application is postponed until.");
            }
            postponeUntil = LocalDate.parse(until);
            if (!postponeUntil.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("The postponement date must be in the future.");
            }
        }

        LocalDateTime now = LocalDateTime.now();
        c.decision = decision;
        c.loadingPercent = loading;
        c.exclusion = exclusion == null ? null : clip(exclusion, 1000);
        c.postponeUntil = postponeUntil;
        c.decisionReason = reason;
        c.decidedBy = user.getUsername();
        c.decidedAt = now;
        c.updatedAt = now;
        // NS-IL02-003: an acceptance or offer stands for the rule set's validity, then must be reassessed.
        c.decisionValidUntil = accepting ? now.toLocalDate().plusDays(rules.decisionValidityDays) : null;
        String before = app.getStatus();
        // NS-IL02-005, AT-IL02-02: any earlier acceptance belonged to earlier terms, and so did its price.
        c.offerAcceptedBy = null;
        c.offerAcceptedAt = null;
        restorePrice(c, app, user.getUsername(), reason);
        if (app.getPremium() != null) {
            c.appliedPremium = Money.of(app.getPremium());
        }
        switch (decision) {
            case ACCEPT_STANDARD -> {
                c.status = ACCEPTED;
                c.offerStatus = OFFER_NONE;
                c.offerPremium = null;
                app.setStatus(applicationStatusWhenCleared(app));
            }
            case ACCEPT_WITH_TERMS -> {
                c.status = OFFERED;
                c.offerVersion = c.offerVersion + 1;
                c.offerStatus = OFFER_PENDING;
                c.offerPremium = loading == null ? c.appliedPremium
                        : c.appliedPremium.multiply(HUNDRED.add(loading)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                app.setStatus("Offered");
            }
            case POSTPONE -> {
                c.status = POSTPONED;
                c.offerStatus = OFFER_NONE;
                app.setStatus("Postponed");
            }
            default -> {
                c.status = DECLINED;
                c.offerStatus = OFFER_NONE;
                app.setStatus("Declined");
            }
        }
        addDecision(c, now);
        // The timeline names the decision, not its clinical reasons (those stay on the underwriting case).
        logApplication(app, "Underwriting decision", before, decision.replace('_', ' ').toLowerCase(Locale.ROOT)
                + (ACCEPT_WITH_TERMS.equals(decision) ? ", offer v" + c.offerVersion : "") + " (" + c.ref + ")",
                user.getUsername());
        publishDecided(c, user.getUsername());
        if (ACCEPT_WITH_TERMS.equals(decision)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("caseRef", c.ref);
            data.put("applicationRef", c.applicationRef);
            data.put("customerRef", c.customerRef == null ? "" : c.customerRef);
            data.put("offerVersion", c.offerVersion);
            data.put("appliedPremium", c.appliedPremium);
            data.put("offerPremium", c.offerPremium);
            data.put("loadingPercent", loading == null ? BigDecimal.ZERO : loading);
            data.put("exclusion", c.exclusion == null ? "" : c.exclusion);
            outbox.publish(Events.OFFER_REVISED, c.ref, data, user.getUsername());
        }
        audit.record(user.getUsername(), "Underwriting decision " + decision, "UnderwritingCase", c.ref, reason);
        return detail(c, user);
    }

    /** {accept true|false, note}: the applicant's answer to the current offer, recorded by staff who took it. */
    @Transactional
    public UnderwritingViews.CaseDetail answerOffer(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasAnyPermission("UNDERWRITE_APPLICATION", "UPDATE_APPLICATION");
        Case c = find(ref);
        String note = required(body, "note", 500);
        if (note.length() < 5) {
            throw new IllegalArgumentException("Say how the applicant gave their answer.");
        }
        recordAnswer(c, body.path("accept").asBoolean(false), note, user.getUsername());
        return detail(c, user);
    }

    /** The applicant answers the offer on their own application themselves (customer portal). */
    @Transactional
    public void answerOfferAsApplicant(InsuranceApplication app, boolean accept, String note, String actor) {
        Case c = byApplication(app.getRef());
        if (c == null) {
            throw new ConflictException(app.getRef() + " has no offer waiting for an answer.");
        }
        recordAnswer(c, accept, note, actor);
    }

    private void recordAnswer(Case c, boolean accept, String note, String actor) {
        String ref = c.ref;
        if (!OFFERED.equals(c.status) || !OFFER_PENDING.equals(c.offerStatus)) {
            throw new ConflictException(ref + " has no offer waiting for an answer.");
        }
        if (expired(c)) {
            throw new ConflictException("Offer v" + c.offerVersion + " on " + ref + " expired on "
                    + c.decisionValidUntil + ". The case must be reassessed before a new offer can be accepted.");
        }
        InsuranceApplication app = application(c);
        String before = app.getStatus();
        LocalDateTime now = LocalDateTime.now();
        c.offerAcceptedBy = actor;
        c.offerAcceptedAt = now;
        c.updatedAt = now;
        if (accept) {
            c.offerStatus = OFFER_ACCEPTED;
            c.status = ACCEPTED;
            app.setStatus(applicationStatusWhenCleared(app));
            // The application and its price trace take the accepted offer's premium, as a new quote version.
            if (c.offerPremium != null && (app.getPremium() == null || c.offerPremium.compareTo(app.getPremium()) != 0)) {
                if (app.getQuoteRef() != null) {
                    com.nsure.portfolio.Quote version = quotes.underwritten(app.getQuoteRef(), c.appliedPremium,
                            c.offerPremium, c.loadingPercent, c.ref, c.offerVersion, actor, note);
                    app.setQuote(version.getRef(), version.getVersionNo());
                }
                app.setPremium(c.offerPremium);
            }
            // IL01: the spec's OfferAccepted for an underwriting offer (OfferAnswered below stays for its handlers).
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("caseRef", c.ref);
            accepted.put("applicationRef", c.applicationRef);
            accepted.put("customerRef", c.customerRef == null ? "" : c.customerRef);
            accepted.put("offerVersion", c.offerVersion);
            accepted.put("quoteRef", app.getQuoteRef() == null ? "" : app.getQuoteRef());
            accepted.put("version", app.getQuoteVersion() == null ? 0 : app.getQuoteVersion());
            accepted.put("premium", app.getPremium() == null ? "" : app.getPremium().toPlainString());
            outbox.publish(Events.OFFER_ACCEPTED, c.ref, accepted, actor);
        } else {
            c.offerStatus = OFFER_REFUSED;
            c.status = DECLINED;
            app.setStatus("Withdrawn");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseRef", c.ref);
        data.put("applicationRef", c.applicationRef);
        data.put("customerRef", c.customerRef == null ? "" : c.customerRef);
        data.put("offerVersion", c.offerVersion);
        data.put("accepted", accept);
        outbox.publish(Events.OFFER_ANSWERED, c.ref, data, actor);
        logApplication(app, accept ? "Offer accepted" : "Offer refused", before, "Offer v" + c.offerVersion + " ("
                + c.ref + ")", actor);
        audit.record(actor, accept ? "Offer accepted" : "Offer refused", "UnderwritingCase", c.ref,
                "Offer v" + c.offerVersion + ": " + note);
    }

    private void logApplication(InsuranceApplication app, String action, String previous, String details, String actor) {
        history.record(com.nsure.portfolio.ApplicationCapture.HISTORY, app.getRef(), app.getCustomerName(), action,
                previous, app.getStatus(), details, actor);
    }

    /**
     * Terms accepted earlier no longer stand (a new decision, or the case reopened): the application's price goes back
     * to the one quoted before them, as a new version of its quote (AT-IL02-02).
     */
    private void restorePrice(Case c, InsuranceApplication app, String actor, String reason) {
        if (app.getQuoteRef() != null) {
            com.nsure.portfolio.Quote restored = quotes.withdrawUnderwriting(app.getQuoteRef(), c.ref, actor, reason);
            if (restored != null) {
                app.setQuote(restored.getRef(), restored.getVersionNo());
                app.setPremium(restored.getPremium());
            }
        } else if (c.appliedPremium != null) {
            app.setPremium(c.appliedPremium);
        }
    }

    /** {reason}: reopens a decided case for reassessment (NS-IL02-006). Earlier decisions stay on the record. */
    @Transactional
    public UnderwritingViews.CaseDetail reopen(String ref, JsonNode body) {
        AppUser user = user("UNDERWRITE_APPLICATION");
        Case c = find(ref);
        InsuranceApplication app = application(c);
        if ("Issued".equalsIgnoreCase(app.getStatus())) {
            throw new ConflictException(app.getRef() + " is already issued; its underwriting cannot be reopened.");
        }
        if (c.decision == null) {
            throw new ConflictException(ref + " has not been decided yet.");
        }
        String reason = required(body, "reason", 500);
        if (reason.length() < 10) {
            throw new IllegalArgumentException("Say why the case is reopened (at least 10 characters).");
        }
        LocalDateTime now = LocalDateTime.now();
        c.decision = null;
        c.decidedBy = null;
        c.decidedAt = null;
        c.decisionReason = null;
        c.loadingPercent = null;
        c.exclusion = null;
        c.postponeUntil = null;
        c.decisionValidUntil = null;
        c.offerStatus = OFFER_NONE;
        c.offerPremium = null;
        c.offerAcceptedBy = null;
        c.offerAcceptedAt = null;
        c.status = outstanding(c.ref) > 0 ? EVIDENCE_PENDING : REFERRED;
        c.referralReasons = (c.referralReasons == null ? "" : c.referralReasons + "\n") + "Reopened: " + reason;
        c.updatedAt = now;
        String before = app.getStatus();
        app.setStatus("Under review");
        restorePrice(c, app, user.getUsername(), reason);
        logApplication(app, "Underwriting reopened", before, c.ref, user.getUsername());
        audit.record(user.getUsername(), "Underwriting reopened", "UnderwritingCase", c.ref, reason);
        return detail(c, user);
    }

    /** {assignee}: who is working the case; blank releases it. */
    @Transactional
    public UnderwritingViews.CaseDetail assign(String ref, JsonNode body) {
        AppUser user = user("UNDERWRITE_APPLICATION");
        Case c = find(ref);
        String assignee = optional(body, "assignee");
        c.assignedTo = assignee == null ? null : clip(assignee, 100);
        c.updatedAt = LocalDateTime.now();
        return detail(c, user);
    }

    // ── issue gate (NS-IL02-006) ──────────────────────────────────────────────────────────────────

    /**
     * The terms a policy issued from this application takes, or a refusal that says exactly what is still open.
     * An application submitted before underwriting existed is assessed now.
     */
    @Transactional
    public Terms clearedTerms(InsuranceApplication app, CustomerAccount customer, String actor) {
        Case c = byApplication(app.getRef());
        if (c == null) {
            c = assess(app, customer, actor);
        }
        if (outstanding(c.ref) > 0) {
            throw new IllegalArgumentException(app.getRef() + " cannot be issued: underwriting case " + c.ref
                    + " still has evidence outstanding.");
        }
        // NS-IL02-006: a possible screening match blocks issue until compliance clears it.
        if (SCREEN_HIT.equals(c.screeningStatus)) {
            throw new IllegalArgumentException(app.getRef() + " cannot be issued: a possible screening match on "
                    + c.ref + " is waiting for compliance.");
        }
        // NS-IL02-006: an expired decision is reassessed, never relied on.
        if (expired(c) && (ACCEPTED.equals(c.status) || OFFERED.equals(c.status))) {
            throw new IllegalArgumentException(app.getRef() + " cannot be issued: the underwriting decision on "
                    + c.ref + " expired on " + c.decisionValidUntil + " and must be reassessed.");
        }
        switch (c.status) {
            case ACCEPTED -> {
                // falls through to the terms below
            }
            case OFFERED -> throw new IllegalArgumentException(app.getRef() + " cannot be issued: offer v"
                    + c.offerVersion + " on " + c.ref + " has not been accepted by the applicant.");
            case POSTPONED -> throw new IllegalArgumentException(app.getRef() + " was postponed by underwriting until "
                    + c.postponeUntil + ".");
            case DECLINED -> throw new IllegalArgumentException(app.getRef() + " was declined by underwriting.");
            default -> throw new IllegalArgumentException(app.getRef() + " cannot be issued: underwriting case "
                    + c.ref + " is awaiting a decision.");
        }
        if (!ACCEPT_WITH_TERMS.equals(c.decision)) {
            return new Terms(c.ref, null, null);
        }
        StringBuilder text = new StringBuilder();
        if (c.loadingPercent != null) {
            text.append("Premium loading of ").append(c.loadingPercent.stripTrailingZeros().toPlainString())
                    .append("% applied (").append(Money.text(c.appliedPremium)).append(" to ")
                    .append(Money.text(c.offerPremium)).append("). ");
        }
        if (c.exclusion != null) {
            text.append("Exclusion: ").append(c.exclusion).append(' ');
        }
        text.append("Offer v").append(c.offerVersion).append(" accepted ")
                .append(c.offerAcceptedAt == null ? "" : c.offerAcceptedAt.toLocalDate()).append(" (").append(c.ref)
                .append(").");
        return new Terms(c.ref, c.loadingPercent == null ? null : c.offerPremium, clip(text.toString().trim(), 1000));
    }

    /**
     * NS-IL01-001: why a flat-priced plan cannot be quoted for this date of birth under the product's rule set entry
     * ages (age last birthday, the rule set's basis), or null. Rated plans check each life's age when priced.
     */
    @Transactional(readOnly = true)
    public String entryAgeProblem(String product, LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            return null;
        }
        RuleSet rules = rulesFor(product);
        int age = Period.between(dateOfBirth, LocalDate.now()).getYears();
        if (age < rules.minEntryAge || age > rules.maxEntryAge) {
            return product + " is open to entry ages " + rules.minEntryAge + "–" + rules.maxEntryAge + "; the life"
                    + " insured is " + age + ".";
        }
        return null;
    }

    /**
     * The offers waiting for the applicant's answer on these applications (customer portal): the terms the applicant
     * is asked to accept, never the underwriter's reasons.
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> pendingOffers(List<String> applicationRefs) {
        Map<String, Map<String, Object>> offers = new LinkedHashMap<>();
        if (applicationRefs.isEmpty()) {
            return offers;
        }
        for (Case c : em.createQuery("select c from UwCase c where c.applicationRef in :refs and c.status = :s",
                Case.class).setParameter("refs", applicationRefs).setParameter("s", OFFERED).getResultList()) {
            if (!OFFER_PENDING.equals(c.offerStatus)) {
                continue;
            }
            Map<String, Object> offer = new LinkedHashMap<>();
            offer.put("offerVersion", c.offerVersion);
            offer.put("premium", c.offerPremium);
            offer.put("previousPremium", c.appliedPremium);
            offer.put("loadingPercent", c.loadingPercent);
            offer.put("exclusion", c.exclusion);
            offer.put("validUntil", c.decisionValidUntil);
            offers.put(c.applicationRef, offer);
        }
        return offers;
    }

    /** Every evidence request still outstanding, for the new business missing-evidence queue (no clinical notes). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> outstandingEvidenceItems() {
        Map<String, String> applications = new LinkedHashMap<>();
        em.createQuery("select c from UwCase c", Case.class).getResultList()
                .forEach(c -> applications.put(c.ref, c.applicationRef));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Evidence e : em.createQuery("select e from UwEvidence e where e.status = :s order by e.dueDate",
                Evidence.class).setParameter("s", EV_REQUESTED).getResultList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseRef", e.caseRef);
            row.put("applicationRef", applications.get(e.caseRef));
            row.put("requirement", e.requirement);
            row.put("restricted", e.restricted);
            row.put("lifeName", e.lifeName);
            row.put("dueDate", e.dueDate);
            row.put("reminders", e.reminderCount);
            rows.add(row);
        }
        return rows;
    }

    /** Whether the application's case lets it move to "Ready to issue" (used when its last evidence arrives). */
    @Transactional(readOnly = true)
    public boolean isCleared(String applicationRef) {
        Case c = byApplication(applicationRef);
        return c != null && ACCEPTED.equals(c.status) && outstanding(c.ref) == 0;
    }

    // ── screening (NS-IL01-006, NS-IL02-006) ──────────────────────────────────────────────────────

    /**
     * {outcome CLEARED|CONFIRMED, note}: compliance's answer to a possible screening match. A cleared case whose only
     * referral was the screening is accepted by the rules; a confirmed match declines the application.
     */
    @Transactional
    public UnderwritingViews.CaseDetail resolveScreening(String ref, JsonNode body) {
        AppUser user = user("CLEAR_SCREENING");
        Case c = find(ref);
        if (!SCREEN_HIT.equals(c.screeningStatus)) {
            throw new ConflictException(ref + " has no screening match waiting for a decision.");
        }
        String outcome = required(body, "outcome", 20).toUpperCase(Locale.ROOT);
        if (!Set.of(SCREEN_CLEARED, SCREEN_CONFIRMED).contains(outcome)) {
            throw new IllegalArgumentException("outcome must be CLEARED or CONFIRMED.");
        }
        String note = required(body, "note", 500);
        if (note.length() < 10) {
            throw new IllegalArgumentException("Say how the match was checked (at least 10 characters).");
        }
        InsuranceApplication app = application(c);
        LocalDateTime now = LocalDateTime.now();
        c.screeningStatus = outcome;
        c.screeningResolvedBy = user.getUsername();
        c.screeningResolvedAt = now;
        c.updatedAt = now;
        String before = app.getStatus();
        if (SCREEN_CONFIRMED.equals(outcome)) {
            c.decision = DECLINE;
            c.decisionReason = "Screening match confirmed by compliance.";
            c.decidedBy = user.getUsername();
            c.decidedAt = now;
            c.decisionValidUntil = null;
            c.status = DECLINED;
            c.offerStatus = OFFER_NONE;
            app.setStatus("Declined");
            addDecision(c, now);
            publishDecided(c, user.getUsername());
        } else {
            List<String> reasons = c.referralReasons == null ? new ArrayList<>()
                    : new ArrayList<>(List.of(c.referralReasons.split("\n")));
            reasons.removeIf(r -> r.startsWith("Screening: "));
            c.referralReasons = reasons.isEmpty() ? null : String.join("\n", reasons);
            if (reasons.isEmpty() && c.decision == null && outstanding(c.ref) == 0) {
                c.status = ACCEPTED;
                decideByRules(c, now);
                app.setStatus(applicationStatusWhenCleared(app));
            }
        }
        logApplication(app, "Screening " + outcome.toLowerCase(Locale.ROOT), before, c.ref, user.getUsername());
        audit.record(user.getUsername(), "Screening " + outcome.toLowerCase(Locale.ROOT), "UnderwritingCase", c.ref,
                note);
        return detail(c, user);
    }

    // ── decisions that no longer stand (NS-IL02-006) ──────────────────────────────────────────────

    /** Runs the daily review now: expired decisions and ended postponements reopen, due evidence is reminded. */
    @Transactional
    public Map<String, List<String>> reviewDueNow() {
        AppUser user = user("UNDERWRITE_APPLICATION");
        return reviewDue(user.getUsername());
    }

    /**
     * Reopens every decision that no longer stands (an expired acceptance or offer, a postponement whose date has
     * come) for reassessment, keeping the earlier decision on the record, and reminds evidence that is due.
     */
    @Transactional
    public Map<String, List<String>> reviewDue(String actor) {
        LocalDate today = LocalDate.now();
        List<String> reopened = new ArrayList<>();
        for (Case c : em.createQuery("select c from UwCase c where c.decision is not null", Case.class).getResultList()) {
            InsuranceApplication app = application(c);
            if (Set.of("Issued", "Withdrawn", "Declined").contains(app.getStatus())) {
                continue;
            }
            String why = null;
            if ((ACCEPTED.equals(c.status) || OFFERED.equals(c.status)) && expired(c)) {
                why = "Decision expired on " + c.decisionValidUntil + "; reassess before issue.";
            } else if (POSTPONED.equals(c.status) && c.postponeUntil != null && !c.postponeUntil.isAfter(today)) {
                why = "Postponement ended on " + c.postponeUntil + "; reassess now.";
            }
            if (why != null) {
                reopenFor(c, app, why, actor);
                reopened.add(c.ref);
            }
        }
        List<String> reminded = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Evidence e : em.createQuery("select e from UwEvidence e where e.status = :s", Evidence.class)
                .setParameter("s", EV_REQUESTED).getResultList()) {
            boolean due = !e.dueDate.isAfter(today.plusDays(REMINDER_LEAD_DAYS));
            boolean quiet = e.lastRemindedAt == null || e.lastRemindedAt.isBefore(now.minusDays(REMINDER_INTERVAL_DAYS));
            if (due && quiet) {
                e.reminderCount++;
                e.lastRemindedAt = now;
                Case c = find(e.caseRef);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("caseRef", c.ref);
                data.put("applicationRef", c.applicationRef);
                data.put("customerRef", c.customerRef == null ? "" : c.customerRef);
                data.put("evidenceId", e.id);
                // Restricted (clinical) evidence is named only as such: no medical detail travels on the bus.
                data.put("requirement", e.restricted ? "Restricted medical evidence" : e.requirement);
                data.put("dueDate", e.dueDate.toString());
                data.put("reminder", e.reminderCount);
                outbox.publish(Events.EVIDENCE_REMINDER_SENT, c.ref, data, actor);
                reminded.add(c.ref + "#" + e.id);
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("reopened", reopened);
        result.put("reminded", reminded);
        return result;
    }

    /**
     * The application changed in a way its decision did not see (a revised quote accepted, changed facts): a rules
     * decision is made again by the rules; a person's decision is reopened for that person's reassessment. Nothing is
     * silently substituted (NS-IL01-004, NS-IL02-006).
     */
    @Transactional
    public void materialChange(InsuranceApplication app, CustomerAccount customer, String reason, String actor) {
        Case c = byApplication(app.getRef());
        if (c == null || c.decision == null || "Issued".equalsIgnoreCase(app.getStatus())) {
            return;
        }
        if (RULES_ACTOR.equals(c.decidedBy)) {
            restorePrice(c, app, actor, reason);
            clearDecision(c);
            assess(app, customer, actor);
            logApplication(app, "Reassessed by the rules", null, reason, actor);
            return;
        }
        reopenFor(c, app, reason, actor);
    }

    private void reopenFor(Case c, InsuranceApplication app, String why, String actor) {
        clearDecision(c);
        c.status = outstanding(c.ref) > 0 ? EVIDENCE_PENDING : REFERRED;
        c.referralReasons = (c.referralReasons == null ? "" : c.referralReasons + "\n") + "Reopened: " + why;
        String before = app.getStatus();
        app.setStatus("Under review");
        restorePrice(c, app, actor, why);
        logApplication(app, "Underwriting reopened", before, c.ref + ": " + why, actor);
        audit.record(actor, "Underwriting reopened", "UnderwritingCase", c.ref, why);
    }

    private void clearDecision(Case c) {
        c.decision = null;
        c.decidedBy = null;
        c.decidedAt = null;
        c.decisionReason = null;
        c.loadingPercent = null;
        c.exclusion = null;
        c.postponeUntil = null;
        c.decisionValidUntil = null;
        c.offerStatus = OFFER_NONE;
        c.offerPremium = null;
        c.offerAcceptedBy = null;
        c.offerAcceptedAt = null;
        c.updatedAt = LocalDateTime.now();
    }

    private static boolean expired(Case c) {
        return c.decisionValidUntil != null && c.decisionValidUntil.isBefore(LocalDate.now());
    }

    // ── rules and authority (NS-IL02-001/004) ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UnderwritingViews.RuleSetView> rules() {
        user("READ_UNDERWRITING");
        return em.createQuery("select r from UwRuleSet r order by r.product", RuleSet.class).getResultList().stream()
                .map(this::ruleView).toList();
    }

    /**
     * Proposes a rule set change (NS-IL01-005, NS-IL02-001): it is validated now but applies only when a second
     * person approves it, and the new version label then marks every case assessed under it.
     */
    @Transactional
    public UnderwritingViews.RuleSetView updateRules(Long id, JsonNode body) {
        AppUser user = user("MANAGE_UNDERWRITING");
        RuleSet r = em.find(RuleSet.class, id);
        if (r == null) {
            throw new NotFoundException("Rule set not found");
        }
        if (pendingChange(r.id) != null) {
            throw new ConflictException("A change to these rules is already waiting for approval.");
        }
        int min = body.path("minEntryAge").asInt(r.minEntryAge);
        int max = body.path("maxEntryAge").asInt(r.maxEntryAge);
        if (min < 0 || max > 120 || min > max) {
            throw new IllegalArgumentException("Entry ages must satisfy 0 ≤ minimum ≤ maximum ≤ 120.");
        }
        BigDecimal auto = amount(body, "autoMaxSumAssured", r.autoMaxSumAssured);
        BigDecimal medical = amount(body, "medicalEvidenceAbove", r.medicalEvidenceAbove);
        BigDecimal aggregate = amount(body, "aggregateExposureLimit", r.aggregateExposureLimit);
        BigDecimal maxLoading = body.hasNonNull("maxLoadingPercent") && !body.get("maxLoadingPercent").asText().isBlank()
                ? new BigDecimal(body.get("maxLoadingPercent").asText().trim()).setScale(2, RoundingMode.HALF_UP)
                : r.maxLoadingPercent;
        if (maxLoading.signum() < 0 || maxLoading.compareTo(BigDecimal.valueOf(500)) > 0) {
            throw new IllegalArgumentException("The loading ceiling must be between 0% and 500%.");
        }
        int validity = body.path("decisionValidityDays").asInt(r.decisionValidityDays);
        if (validity < 1 || validity > 730) {
            throw new IllegalArgumentException("A decision must stand between 1 and 730 days.");
        }
        String label = required(body, "versionLabel", 40);
        if (label.equals(r.versionLabel)) {
            throw new IllegalArgumentException("Give the changed rules a new version label, so earlier cases keep"
                    + " the version they were assessed under.");
        }
        Map<String, Object> proposal = new LinkedHashMap<>();
        proposal.put("minEntryAge", min);
        proposal.put("maxEntryAge", max);
        proposal.put("autoMaxSumAssured", auto);
        proposal.put("medicalEvidenceAbove", medical);
        proposal.put("aggregateExposureLimit", aggregate);
        proposal.put("referTobacco", body.path("referTobacco").asBoolean(r.referTobacco));
        proposal.put("referTreatment", body.path("referTreatment").asBoolean(r.referTreatment));
        proposal.put("referDisclosureCodes", body.has("referDisclosureCodes")
                ? String.join(",", codes(optional(body, "referDisclosureCodes"))) : blank(r.referDisclosureCodes));
        proposal.put("referOccupations", body.has("referOccupations")
                ? blank(optional(body, "referOccupations")) : blank(r.referOccupations));
        proposal.put("maxLoadingPercent", maxLoading);
        proposal.put("decisionValidityDays", validity);
        RuleChange change = new RuleChange();
        change.ruleSetId = r.id;
        change.versionLabel = label;
        change.proposal = json(proposal);
        change.previous = json(paramsOf(r));
        change.status = CHANGE_PENDING;
        change.proposedBy = user.getUsername();
        change.proposedAt = LocalDateTime.now();
        em.persist(change);
        em.flush();
        audit.record(user.getUsername(), "Underwriting rules change proposed", "UnderwritingRuleSet",
                r.product == null ? "Default" : r.product, label + " awaits a second approver");
        return ruleView(r);
    }

    /** Every proposed rule change, newest first: what changed, who proposed and who approved or rejected it. */
    @Transactional(readOnly = true)
    public List<UnderwritingViews.RuleChangeView> ruleChanges() {
        user("READ_UNDERWRITING");
        Map<Long, RuleSet> sets = new LinkedHashMap<>();
        em.createQuery("select r from UwRuleSet r", RuleSet.class).getResultList().forEach(r -> sets.put(r.id, r));
        return em.createQuery("select c from UwRuleChange c order by c.id desc", RuleChange.class).getResultList()
                .stream().map(c -> changeView(c, sets.get(c.ruleSetId))).toList();
    }

    /** {approve true|false, note}: a second MANAGE_UNDERWRITING holder applies or rejects a proposed change. */
    @Transactional
    public UnderwritingViews.RuleChangeView decideRuleChange(Long changeId, JsonNode body) {
        AppUser user = user("MANAGE_UNDERWRITING");
        RuleChange change = em.find(RuleChange.class, changeId);
        if (change == null) {
            throw new NotFoundException("Rule change not found");
        }
        if (!CHANGE_PENDING.equals(change.status)) {
            throw new ConflictException("This rule change is already " + change.status.toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getUsername().equalsIgnoreCase(change.proposedBy)) {
            throw new NoAuthorizationException("You proposed this change, so a second person must approve it.");
        }
        boolean approve = body.path("approve").asBoolean(false);
        String note = optional(body, "note");
        if (!approve && (note == null || note.length() < 5)) {
            throw new IllegalArgumentException("Say why the change is rejected.");
        }
        RuleSet r = em.find(RuleSet.class, change.ruleSetId);
        LocalDateTime now = LocalDateTime.now();
        change.status = approve ? CHANGE_APPROVED : CHANGE_REJECTED;
        change.decidedBy = user.getUsername();
        change.decidedAt = now;
        change.decisionNote = note == null ? null : clip(note, 500);
        if (approve) {
            JsonNode p = readJson(change.proposal);
            r.minEntryAge = p.path("minEntryAge").asInt();
            r.maxEntryAge = p.path("maxEntryAge").asInt();
            r.autoMaxSumAssured = new BigDecimal(p.path("autoMaxSumAssured").asText());
            r.medicalEvidenceAbove = new BigDecimal(p.path("medicalEvidenceAbove").asText());
            r.aggregateExposureLimit = new BigDecimal(p.path("aggregateExposureLimit").asText());
            r.referTobacco = p.path("referTobacco").asBoolean();
            r.referTreatment = p.path("referTreatment").asBoolean();
            r.referDisclosureCodes = emptyToNull(p.path("referDisclosureCodes").asText());
            r.referOccupations = emptyToNull(p.path("referOccupations").asText());
            r.maxLoadingPercent = new BigDecimal(p.path("maxLoadingPercent").asText());
            r.decisionValidityDays = p.path("decisionValidityDays").asInt();
            r.versionLabel = change.versionLabel;
            r.updatedBy = change.proposedBy + " (approved by " + user.getUsername() + ")";
            r.updatedAt = now;
        }
        audit.record(user.getUsername(), approve ? "Underwriting rules approved" : "Underwriting rules rejected",
                "UnderwritingRuleSet", r.product == null ? "Default" : r.product, change.versionLabel
                        + (note == null ? "" : ": " + note));
        return changeView(change, r);
    }

    private RuleChange pendingChange(Long ruleSetId) {
        return em.createQuery("select c from UwRuleChange c where c.ruleSetId = :id and c.status = :s", RuleChange.class)
                .setParameter("id", ruleSetId).setParameter("s", CHANGE_PENDING).getResultStream().findFirst()
                .orElse(null);
    }

    private static Map<String, Object> paramsOf(RuleSet r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("versionLabel", r.versionLabel);
        m.put("minEntryAge", r.minEntryAge);
        m.put("maxEntryAge", r.maxEntryAge);
        m.put("autoMaxSumAssured", r.autoMaxSumAssured);
        m.put("medicalEvidenceAbove", r.medicalEvidenceAbove);
        m.put("aggregateExposureLimit", r.aggregateExposureLimit);
        m.put("referTobacco", r.referTobacco);
        m.put("referTreatment", r.referTreatment);
        m.put("referDisclosureCodes", blank(r.referDisclosureCodes));
        m.put("referOccupations", blank(r.referOccupations));
        m.put("maxLoadingPercent", r.maxLoadingPercent);
        m.put("decisionValidityDays", r.decisionValidityDays);
        return m;
    }

    private UnderwritingViews.RuleChangeView changeView(RuleChange c, RuleSet r) {
        return new UnderwritingViews.RuleChangeView(c.id, c.ruleSetId, r == null ? null : r.product, c.versionLabel,
                readJson(c.proposal), c.previous == null ? null : readJson(c.previous), c.status, c.proposedBy,
                c.proposedAt, c.decidedBy, c.decidedAt, c.decisionNote);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode readJson(String value) {
        try {
            return JSON.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Transactional(readOnly = true)
    public List<UnderwritingViews.AuthorityView> authorities() {
        user("READ_UNDERWRITING");
        Map<Long, Authority> rows = new LinkedHashMap<>();
        em.createQuery("select a from UwAuthority a", Authority.class).getResultList()
                .forEach(a -> rows.put(a.roleId, a));
        return roles.findAllByOrderByNameAsc().stream()
                .filter(role -> role.getPermissions().stream()
                        .anyMatch(p -> "UNDERWRITE_APPLICATION".equals(p.getCode())))
                .map(role -> {
                    Authority a = rows.get(role.getId());
                    return new UnderwritingViews.AuthorityView(role.getId(), role.getName(),
                            a == null ? null : a.maxSumAssured, a == null ? null : a.updatedBy,
                            a == null ? null : a.updatedAt, a == null ? null : a.maxLoadingPercent,
                            a == null || a.decisionTypes == null ? null : codes(a.decisionTypes));
                })
                .toList();
    }

    /** {maxSumAssured}: a blank or missing amount removes the limit. */
    @Transactional
    public List<UnderwritingViews.AuthorityView> setAuthority(Long roleId, JsonNode body) {
        AppUser user = user("MANAGE_UNDERWRITING");
        Role role = roles.findById(roleId).orElseThrow(() -> new NotFoundException("Role not found"));
        Authority a = em.createQuery("select a from UwAuthority a where a.roleId = :id", Authority.class)
                .setParameter("id", roleId).getResultStream().findFirst().orElse(null);
        String raw = optional(body, "maxSumAssured");
        if (raw == null) {
            if (a != null) {
                em.remove(a);
            }
        } else {
            BigDecimal max = Money.of(new BigDecimal(raw));
            if (max.signum() <= 0) {
                throw new IllegalArgumentException("The authority limit must be above zero.");
            }
            if (a == null) {
                a = new Authority();
                a.roleId = roleId;
            }
            a.maxSumAssured = max;
            // NS-IL02-004: authority by decision type and loading as well as amount (blank is not limited).
            String loading = optional(body, "maxLoadingPercent");
            a.maxLoadingPercent = loading == null ? null : new BigDecimal(loading).setScale(2, RoundingMode.HALF_UP);
            if (a.maxLoadingPercent != null && a.maxLoadingPercent.signum() < 0) {
                throw new IllegalArgumentException("The loading limit cannot be negative.");
            }
            List<String> types = new ArrayList<>();
            if (body.has("decisionTypes") && body.get("decisionTypes").isArray()) {
                body.get("decisionTypes").forEach(t -> types.add(t.asText().trim().toUpperCase(Locale.ROOT)));
            } else if (optional(body, "decisionTypes") != null) {
                types.addAll(codes(optional(body, "decisionTypes")));
            }
            if (!DECISION_TYPES.containsAll(types)) {
                throw new IllegalArgumentException("Decision types are ACCEPT_STANDARD, ACCEPT_WITH_TERMS, POSTPONE and"
                        + " DECLINE.");
            }
            a.decisionTypes = types.isEmpty() || types.containsAll(DECISION_TYPES) ? null : String.join(",", types);
            a.updatedBy = user.getUsername();
            a.updatedAt = LocalDateTime.now();
            em.persist(a);
        }
        em.flush();
        audit.record(user.getUsername(), "Underwriting authority changed", "Role", role.getName(),
                raw == null ? "Limit removed" : "Limit " + raw);
        return authorities();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** "Ready to issue" once underwriting is clear and the application's own evidence checklist is complete. */
    private String applicationStatusWhenCleared(InsuranceApplication app) {
        return "Evidence needed".equals(app.getStatus()) ? app.getStatus() : "Ready to issue";
    }

    private void addDecision(Case c, LocalDateTime now) {
        Integer last = em.createQuery("select max(d.sequenceNo) from UwDecision d where d.caseRef = :ref",
                Integer.class).setParameter("ref", c.ref).getSingleResult();
        Decision d = new Decision();
        d.caseRef = c.ref;
        d.sequenceNo = last == null ? 1 : last + 1;
        d.decision = c.decision;
        d.loadingPercent = c.loadingPercent;
        d.exclusion = c.exclusion;
        d.postponeUntil = c.postponeUntil;
        d.reason = c.decisionReason;
        d.offerVersion = c.offerVersion;
        d.offerPremium = c.offerPremium;
        d.decidedBy = c.decidedBy;
        d.decidedAt = now;
        // IL02: the life, the benefit and the evidence (each item's id and status, never clinical notes) it rested on.
        d.lifeRef = c.customerRef;
        InsuranceApplication app = em.createQuery("select a from InsuranceApplication a where a.ref = :ref",
                InsuranceApplication.class).setParameter("ref", c.applicationRef).getResultStream().findFirst().orElse(null);
        d.benefit = clip(c.product + (app != null && app.getTier() != null ? " · " + app.getTier() : ""), 200);
        d.sumAssured = c.sumAssured;
        List<String> basis = em.createQuery("select e from UwEvidence e where e.caseRef = :ref order by e.id",
                        Evidence.class).setParameter("ref", c.ref).getResultStream()
                .map(e -> "#" + e.id + " " + e.requirement + ": " + e.status)
                .toList();
        d.evidenceBasis = basis.isEmpty() ? "No evidence requested" : clip(String.join("; ", basis), 1000);
        em.persist(d);
    }

    private void publishDecided(Case c, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseRef", c.ref);
        data.put("applicationRef", c.applicationRef);
        data.put("customerRef", c.customerRef == null ? "" : c.customerRef);
        data.put("product", c.product);
        data.put("decision", c.decision);
        data.put("decidedBy", c.decidedBy);
        data.put("ruleSetVersion", c.ruleSetVersion);
        outbox.publish(Events.UNDERWRITING_DECIDED, c.ref, data, actor);
    }

    /** The main life's age at entry when the application lists lives, otherwise the customer's age today. */
    private Integer entryAge(InsuranceApplication app, CustomerAccount customer) {
        Integer main = em.createQuery("select l from PolicyLifeCover l where l.application.ref = :ref"
                        + " order by l.displayOrder", PolicyLifeCover.class)
                .setParameter("ref", app.getRef()).getResultStream()
                .filter(PolicyLifeCover::isMainInsured).map(PolicyLifeCover::getAgeAtEntry).findFirst().orElse(null);
        if (main != null) {
            return main;
        }
        return customer.getDateOfBirth() == null ? null
                : Period.between(customer.getDateOfBirth(), LocalDate.now()).getYears();
    }

    private BigDecimal exposureOf(String customerRef) {
        if (customerRef == null) {
            return BigDecimal.ZERO;
        }
        return em.createQuery("select p from InsurancePolicy p where p.customerRef = :ref", InsurancePolicy.class)
                .setParameter("ref", customerRef).getResultList().stream()
                .filter(p -> IN_FORCE.contains(p.getStatus()) && p.getAmount() != null)
                .map(InsurancePolicy::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, String> answers(String applicationRef) {
        Map<String, String> answers = new LinkedHashMap<>();
        em.createQuery("select a from ApplicationAnswer a where a.applicationRef = :ref", ApplicationRecords.Answer.class)
                .setParameter("ref", applicationRef).getResultStream()
                .forEach(a -> answers.put(a.getCode(), a.getAnswer()));
        return answers;
    }

    private static boolean yes(String answer) {
        return answer != null && Set.of("YES", "Y", "TRUE").contains(answer.trim().toUpperCase(Locale.ROOT));
    }

    private RuleSet rulesFor(String product) {
        List<RuleSet> all = em.createQuery("select r from UwRuleSet r", RuleSet.class).getResultList();
        return all.stream().filter(r -> product != null && product.equals(r.product)).findFirst()
                .or(() -> all.stream().filter(r -> r.product == null).findFirst())
                .orElseThrow(() -> new IllegalStateException("No underwriting rule set is configured."));
    }

    /**
     * The user's underwriting mandate across their enabled underwriting roles (NS-IL02-004): the highest exposure and
     * loading limits and every decision type any role allows; a role without a row, or with no limit, is not limited.
     */
    Mandate mandateOf(AppUser user) {
        Map<Long, Authority> rows = new LinkedHashMap<>();
        em.createQuery("select a from UwAuthority a", Authority.class).getResultList().forEach(a -> rows.put(a.roleId, a));
        BigDecimal sum = null;
        BigDecimal loading = null;
        Set<String> types = new java.util.HashSet<>();
        boolean anyRole = false;
        boolean sumUnlimited = false;
        boolean loadingUnlimited = false;
        boolean typesUnlimited = false;
        for (Role role : user.getRoles()) {
            if (role.isDisabled() || role.getPermissions().stream()
                    .noneMatch(p -> "UNDERWRITE_APPLICATION".equals(p.getCode()))) {
                continue;
            }
            anyRole = true;
            Authority a = rows.get(role.getId());
            if (a == null) {
                sumUnlimited = loadingUnlimited = typesUnlimited = true;
                continue;
            }
            sum = sum == null || a.maxSumAssured.compareTo(sum) > 0 ? a.maxSumAssured : sum;
            if (a.maxLoadingPercent == null) {
                loadingUnlimited = true;
            } else {
                loading = loading == null || a.maxLoadingPercent.compareTo(loading) > 0 ? a.maxLoadingPercent : loading;
            }
            if (a.decisionTypes == null) {
                typesUnlimited = true;
            } else {
                types.addAll(codes(a.decisionTypes));
            }
        }
        if (!anyRole) {
            // Super users holding the code through no underwriting role row are not limited (as before).
            return new Mandate(null, null, null);
        }
        return new Mandate(sumUnlimited ? null : sum, loadingUnlimited ? null : loading, typesUnlimited ? null : types);
    }

    /** Comma-separated codes, trimmed and upper-cased, blanks dropped. */
    private static List<String> codes(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(",")).map(v -> v.trim().toUpperCase(Locale.ROOT))
                .filter(v -> !v.isEmpty()).distinct().toList();
    }

    /** The referral-list word the occupation contains, or null (case-insensitive whole words or phrases). */
    private static String occupationMatch(String list, String occupation) {
        if (occupation == null || occupation.isBlank() || list == null || list.isBlank()) {
            return null;
        }
        String o = " " + normalise(occupation) + " ";
        for (String word : list.split(",")) {
            String w = normalise(word);
            if (!w.isEmpty() && o.contains(" " + w + " ")) {
                return word.trim();
            }
        }
        return null;
    }

    /**
     * Simulated screening (no provider is connected): every name on the application (the policyholder and each
     * insured life) against the screening lists. A list entry matches when all of its name parts appear in the name.
     */
    private String screen(InsuranceApplication app, CustomerAccount customer) {
        List<String> names = new ArrayList<>();
        if (customer != null && customer.getName() != null) {
            names.add(customer.getName());
        }
        em.createQuery("select l from PolicyLifeCover l where l.application.ref = :ref", PolicyLifeCover.class)
                .setParameter("ref", app.getRef()).getResultStream()
                .map(PolicyLifeCover::getFullName).filter(n -> n != null && !names.contains(n)).forEach(names::add);
        if (app.getPayerName() != null && !names.contains(app.getPayerName())) {
            names.add(app.getPayerName());
        }
        List<UnderwritingRecords.WatchEntry> list = em.createQuery("select w from UwWatchEntry w",
                UnderwritingRecords.WatchEntry.class).getResultList();
        for (String name : names) {
            Set<String> parts = new java.util.HashSet<>(List.of(normalise(name).split(" ")));
            for (UnderwritingRecords.WatchEntry w : list) {
                List<String> wanted = List.of(normalise(w.fullName).split(" "));
                if (!wanted.isEmpty() && parts.containsAll(wanted)) {
                    return "possible match for " + name + " on " + w.listName + ".";
                }
            }
        }
        return null;
    }

    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private long outstanding(String caseRef) {
        return em.createQuery("select count(e) from UwEvidence e where e.caseRef = :ref and e.status = :s", Long.class)
                .setParameter("ref", caseRef).setParameter("s", EV_REQUESTED).getSingleResult();
    }

    private Map<String, Long> outstandingEvidence() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : em.createQuery("select e.caseRef, count(e) from UwEvidence e where e.status = :s"
                + " group by e.caseRef", Object[].class).setParameter("s", EV_REQUESTED).getResultList()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private List<Evidence> evidence(String caseRef) {
        return em.createQuery("select e from UwEvidence e where e.caseRef = :ref order by e.id", Evidence.class)
                .setParameter("ref", caseRef).getResultList();
    }

    private void requireOpen(Case c) {
        if (Set.of(DECLINED, POSTPONED).contains(c.status) || "Issued".equalsIgnoreCase(application(c).getStatus())) {
            throw new ConflictException(c.ref + " is closed. Reopen it to ask for more evidence.");
        }
    }

    private InsuranceApplication application(Case c) {
        return em.createQuery("select a from InsuranceApplication a where a.ref = :ref", InsuranceApplication.class)
                .setParameter("ref", c.applicationRef).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Application " + c.applicationRef + " not found"));
    }

    private Case byApplication(String applicationRef) {
        return em.createQuery("select c from UwCase c where c.applicationRef = :ref", Case.class)
                .setParameter("ref", applicationRef).getResultStream().findFirst().orElse(null);
    }

    private Case find(String ref) {
        return em.createQuery("select c from UwCase c where c.ref = :ref", Case.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Underwriting case " + ref + " not found"));
    }

    private UnderwritingViews.CaseView view(Case c, int outstanding, Mandate authority) {
        return new UnderwritingViews.CaseView(c.ref, c.applicationRef, c.customerRef, c.customerName, c.product,
                c.sumAssured, c.appliedPremium, c.aggregateExposure, c.entryAge, c.ruleSetVersion, c.status,
                c.referralReasons == null ? List.of() : List.of(c.referralReasons.split("\n")), c.decision,
                c.loadingPercent, c.exclusion, c.postponeUntil, c.decisionReason, c.decidedBy, c.decidedAt,
                c.offerVersion, c.offerPremium, c.offerStatus, c.offerAcceptedBy, c.offerAcceptedAt, c.assignedTo,
                c.createdAt, c.updatedAt, c.version, outstanding, authority.maxSumAssured(),
                authority.covers(c.aggregateExposure), c.occupation, c.decisionValidUntil, expired(c),
                c.screeningStatus, c.screeningDetail, c.screeningResolvedBy, c.screeningResolvedAt,
                authority.maxLoadingPercent(),
                authority.decisionTypes() == null ? null : List.copyOf(new java.util.TreeSet<>(authority.decisionTypes())));
    }

    private UnderwritingViews.RuleSetView ruleView(RuleSet r) {
        RuleChange pending = pendingChange(r.id);
        return new UnderwritingViews.RuleSetView(r.id, r.product, r.versionLabel, r.minEntryAge, r.maxEntryAge,
                r.autoMaxSumAssured, r.medicalEvidenceAbove, r.aggregateExposureLimit, r.referTobacco,
                r.referTreatment, r.updatedBy, r.updatedAt, codes(r.referDisclosureCodes), r.referOccupations,
                r.maxLoadingPercent, r.decisionValidityDays, pending == null ? null : changeView(pending, r));
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static BigDecimal amount(JsonNode body, String field, BigDecimal current) {
        if (!body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return current;
        }
        BigDecimal value = Money.of(new BigDecimal(body.get(field).asText().trim()));
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be above zero.");
        }
        return value;
    }

    private static String required(JsonNode body, String field, int max) {
        String value = optional(body, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return clip(value, max);
    }

    private static String optional(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String value = body.get(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
