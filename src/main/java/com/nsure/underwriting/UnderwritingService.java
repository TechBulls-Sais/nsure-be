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

    static final String EV_REQUESTED = "REQUESTED";
    static final String EV_RECEIVED = "RECEIVED";
    static final String EV_WAIVED = "WAIVED";

    private static final String RULES_ACTOR = "rules";
    private static final Set<String> IN_FORCE = Set.of("Active", "Pending Payment", "Active - No Cover");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int EVIDENCE_DUE_DAYS = 14;

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
        addDecision(c, now);
        publishDecided(c, RULES_ACTOR);
    }

    // ── queries ───────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UnderwritingViews.CaseView> cases(String status) {
        AppUser user = user("READ_UNDERWRITING");
        BigDecimal authority = authorityOf(user);
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
                        e.restricted && !clinical ? null : e.note))
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
                .map(l -> new UnderwritingViews.LifeView(l.getFullName(), l.getLifeType(), l.getDateOfBirth(),
                        l.getAgeAtEntry(), l.getCoverAmount()))
                .toList();
        int outstanding = (int) evidence.stream().filter(e -> EV_REQUESTED.equals(e.status())).count();
        return new UnderwritingViews.CaseDetail(view(c, outstanding, authorityOf(user)), lives, evidence, decisions,
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
        requestEvidenceRow(c, life == null ? c.customerName : clip(life, 150), requirement,
                body.path("restricted").asBoolean(false), due, user.getUsername());
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

    private void requestEvidenceRow(Case c, String life, String requirement, boolean restricted, LocalDate due,
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
        BigDecimal authority = authorityOf(user);
        if (authority != null && c.sumAssured.compareTo(authority) > 0) {
            throw new NoAuthorizationException("The sum assured of " + Money.text(c.sumAssured)
                    + " is above your underwriting authority of " + Money.text(authority)
                    + ". Refer the case to a senior underwriter.");
        }
        boolean accepting = ACCEPT_STANDARD.equals(decision) || ACCEPT_WITH_TERMS.equals(decision);
        if (accepting && outstanding(c.ref) > 0) {
            throw new IllegalArgumentException(ref + " still has evidence outstanding. Record it as received or waive"
                    + " it before accepting.");
        }
        BigDecimal loading = null;
        String exclusion = null;
        LocalDate postponeUntil = null;
        if (ACCEPT_WITH_TERMS.equals(decision)) {
            if (body.hasNonNull("loadingPercent") && !body.get("loadingPercent").asText().isBlank()) {
                loading = new BigDecimal(body.get("loadingPercent").asText().trim()).setScale(2, RoundingMode.HALF_UP);
                if (loading.signum() < 0 || loading.compareTo(BigDecimal.valueOf(500)) > 0) {
                    throw new IllegalArgumentException("The loading must be between 0% and 500%.");
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
        String before = app.getStatus();
        // NS-IL02-005, AT-IL02-02: any earlier acceptance belonged to earlier terms, and so did its price.
        c.offerAcceptedBy = null;
        c.offerAcceptedAt = null;
        restorePrice(c, app, user.getUsername(), reason);
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
        if (!OFFERED.equals(c.status) || !OFFER_PENDING.equals(c.offerStatus)) {
            throw new ConflictException(ref + " has no offer waiting for an answer.");
        }
        String note = required(body, "note", 500);
        if (note.length() < 5) {
            throw new IllegalArgumentException("Say how the applicant gave their answer.");
        }
        InsuranceApplication app = application(c);
        String before = app.getStatus();
        LocalDateTime now = LocalDateTime.now();
        boolean accept = body.path("accept").asBoolean(false);
        c.offerAcceptedBy = user.getUsername();
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
                            c.offerPremium, c.loadingPercent, c.ref, c.offerVersion, user.getUsername(), note);
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
            outbox.publish(Events.OFFER_ACCEPTED, c.ref, accepted, user.getUsername());
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
        outbox.publish(Events.OFFER_ANSWERED, c.ref, data, user.getUsername());
        logApplication(app, accept ? "Offer accepted" : "Offer refused", before, "Offer v" + c.offerVersion + " ("
                + c.ref + ")", user.getUsername());
        audit.record(user.getUsername(), accept ? "Offer accepted" : "Offer refused", "UnderwritingCase", c.ref,
                "Offer v" + c.offerVersion + ": " + note);
        return detail(c, user);
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

    /** Whether the application's case lets it move to "Ready to issue" (used when its last evidence arrives). */
    @Transactional(readOnly = true)
    public boolean isCleared(String applicationRef) {
        Case c = byApplication(applicationRef);
        return c != null && ACCEPTED.equals(c.status) && outstanding(c.ref) == 0;
    }

    // ── rules and authority (NS-IL02-001/004) ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UnderwritingViews.RuleSetView> rules() {
        user("READ_UNDERWRITING");
        return em.createQuery("select r from UwRuleSet r order by r.product", RuleSet.class).getResultList().stream()
                .map(this::ruleView).toList();
    }

    /** Changes a rule set; the new version label marks every case assessed under it from now on. */
    @Transactional
    public UnderwritingViews.RuleSetView updateRules(Long id, JsonNode body) {
        AppUser user = user("MANAGE_UNDERWRITING");
        RuleSet r = em.find(RuleSet.class, id);
        if (r == null) {
            throw new NotFoundException("Rule set not found");
        }
        int min = body.path("minEntryAge").asInt(r.minEntryAge);
        int max = body.path("maxEntryAge").asInt(r.maxEntryAge);
        if (min < 0 || max > 120 || min > max) {
            throw new IllegalArgumentException("Entry ages must satisfy 0 ≤ minimum ≤ maximum ≤ 120.");
        }
        BigDecimal auto = amount(body, "autoMaxSumAssured", r.autoMaxSumAssured);
        BigDecimal medical = amount(body, "medicalEvidenceAbove", r.medicalEvidenceAbove);
        BigDecimal aggregate = amount(body, "aggregateExposureLimit", r.aggregateExposureLimit);
        String label = required(body, "versionLabel", 40);
        if (label.equals(r.versionLabel)) {
            throw new IllegalArgumentException("Give the changed rules a new version label, so earlier cases keep"
                    + " the version they were assessed under.");
        }
        r.minEntryAge = min;
        r.maxEntryAge = max;
        r.autoMaxSumAssured = auto;
        r.medicalEvidenceAbove = medical;
        r.aggregateExposureLimit = aggregate;
        r.referTobacco = body.path("referTobacco").asBoolean(r.referTobacco);
        r.referTreatment = body.path("referTreatment").asBoolean(r.referTreatment);
        r.versionLabel = label;
        r.updatedBy = user.getUsername();
        r.updatedAt = LocalDateTime.now();
        audit.record(user.getUsername(), "Underwriting rules changed", "UnderwritingRuleSet",
                r.product == null ? "Default" : r.product, "Now " + label);
        return ruleView(r);
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
                            a == null ? null : a.updatedAt);
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

    /** The highest limit among the user's enabled underwriting roles; null when any of them is not limited. */
    private BigDecimal authorityOf(AppUser user) {
        Map<Long, BigDecimal> limits = new LinkedHashMap<>();
        em.createQuery("select a from UwAuthority a", Authority.class).getResultList()
                .forEach(a -> limits.put(a.roleId, a.maxSumAssured));
        BigDecimal highest = null;
        for (Role role : user.getRoles()) {
            if (role.isDisabled() || role.getPermissions().stream()
                    .noneMatch(p -> "UNDERWRITE_APPLICATION".equals(p.getCode()))) {
                continue;
            }
            BigDecimal limit = limits.get(role.getId());
            if (limit == null) {
                return null;
            }
            highest = highest == null || limit.compareTo(highest) > 0 ? limit : highest;
        }
        return highest;
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

    private UnderwritingViews.CaseView view(Case c, int outstanding, BigDecimal authority) {
        return new UnderwritingViews.CaseView(c.ref, c.applicationRef, c.customerRef, c.customerName, c.product,
                c.sumAssured, c.appliedPremium, c.aggregateExposure, c.entryAge, c.ruleSetVersion, c.status,
                c.referralReasons == null ? List.of() : List.of(c.referralReasons.split("\n")), c.decision,
                c.loadingPercent, c.exclusion, c.postponeUntil, c.decisionReason, c.decidedBy, c.decidedAt,
                c.offerVersion, c.offerPremium, c.offerStatus, c.offerAcceptedBy, c.offerAcceptedAt, c.assignedTo,
                c.createdAt, c.updatedAt, c.version, outstanding, authority,
                authority == null || c.sumAssured.compareTo(authority) <= 0);
    }

    private UnderwritingViews.RuleSetView ruleView(RuleSet r) {
        return new UnderwritingViews.RuleSetView(r.id, r.product, r.versionLabel, r.minEntryAge, r.maxEntryAge,
                r.autoMaxSumAssured, r.medicalEvidenceAbove, r.aggregateExposureLimit, r.referTobacco,
                r.referTreatment, r.updatedBy, r.updatedAt);
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
