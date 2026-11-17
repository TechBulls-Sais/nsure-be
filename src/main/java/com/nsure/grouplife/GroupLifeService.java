package com.nsure.grouplife;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.group.GroupClaim;
import com.nsure.group.GroupSchemeService;
import com.nsure.group.SchemeLifecycle;
import com.nsure.group.SchemeInvoice;
import com.nsure.group.SchemeMember;
import com.nsure.grouplife.GroupLifeRecords.Benefit;
import com.nsure.grouplife.GroupLifeRecords.CreditLoan;
import com.nsure.grouplife.GroupLifeRecords.Quote;
import com.nsure.grouplife.GroupLifeRecords.SchemeEvent;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.GroupScheme;
import com.nsure.portfolio.ReferenceNumbers;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Group scheme lifecycle around membership and billing (Nsure Life GL01, GL02, GL05, GL06).
 *
 * <p>A scheme starts as a quotation: versions are kept, an underwriter approves, the sponsor accepts, and only then
 * is the master policy activated, with its wording and free-cover limit confirmed. Each scheme lists the benefits it
 * provides, each with its own definition and the product's maximums. Renewal, suspension and closure are requested
 * with an impact preview and decided by a second person; a scheme is never deleted. Lender schemes take loan
 * bordereaux: each loan gets a certificate once, above-cap loans are referred, and an early settlement refunds once.
 */
@Service
public class GroupLifeService {

    static final Set<String> BENEFIT_TYPES = Set.of("GLA", "LUMP_SUM_DISABILITY", "CRITICAL_ILLNESS", "FAMILY",
            "INCOME_PROTECTION", "CREDIT");
    /** The product's maximums a scheme cannot override (specification section 08). */
    static final Map<String, BigDecimal> PRODUCT_MAXIMUM = Map.of(
            "FAMILY", new BigDecimal("40000"),
            "CREDIT", new BigDecimal("750000"),
            "CRITICAL_ILLNESS", new BigDecimal("350000"));
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final EvidenceChain audit;
    private final GroupSchemeService schemes;
    private final ObjectMapper mapper;
    private final SchemeLifecycle lifecycle;

    public GroupLifeService(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers,
            EvidenceChain audit, GroupSchemeService schemes, ObjectMapper mapper, SchemeLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.audit = audit;
        this.schemes = schemes;
        this.mapper = mapper;
    }

    // ── quotations (NS-GL01-001..004) ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> quotes() {
        user("READ_SCHEME");
        return em.createQuery("select q from SchemeQuote q order by q.ref desc, q.versionNo desc", Quote.class)
                .getResultList().stream().map(this::quoteView).toList();
    }

    /**
     * {sponsorName, sector, lives, salaryRoll, benefits [..], ratingBasis, salaryMultiple, ratePerMille,
     * freeCoverLimit?, notes?}: a new quotation, version 1.
     */
    @Transactional
    public Map<String, Object> quote(JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        Quote q = fill(new Quote(), body);
        q.ref = numbers.next("GQ-", 4, () -> 0);
        q.versionNo = 1;
        q.status = "DRAFT";
        q.createdBy = user.getUsername();
        q.createdAt = LocalDateTime.now();
        em.persist(q);
        return quoteView(q);
    }

    /** A negotiated revision: a new version beside the earlier ones, which stay as they were offered. */
    @Transactional
    public Map<String, Object> revise(String ref, JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        Quote latest = latest(ref);
        if (Set.of("ACTIVATED").contains(latest.status)) {
            throw new ConflictException(ref + " has been activated as " + latest.schemeRef + ".");
        }
        if (Set.of("DRAFT", "SUBMITTED", "APPROVED").contains(latest.status)) {
            latest.status = "SUPERSEDED";
        }
        ObjectNode merged = mapper.createObjectNode();
        merged.put("sponsorName", latest.sponsorName).put("sector", latest.sector).put("lives", latest.lives)
                .put("salaryRoll", latest.salaryRoll).put("ratingBasis", latest.ratingBasis)
                .put("salaryMultiple", latest.salaryMultiple).put("ratePerMille", latest.ratePerMille)
                .put("benefits", latest.benefits);
        if (latest.freeCoverLimit != null) {
            merged.put("freeCoverLimit", latest.freeCoverLimit);
        }
        body.fields().forEachRemaining(f -> merged.set(f.getKey(), f.getValue()));
        Quote q = fill(new Quote(), merged);
        q.ref = ref;
        q.versionNo = latest.versionNo + 1;
        q.status = "DRAFT";
        q.createdBy = user.getUsername();
        q.createdAt = LocalDateTime.now();
        em.persist(q);
        return quoteView(q);
    }

    /** {action SUBMIT|APPROVE|DECLINE|ACCEPT, note?}: moves the latest version through approval and acceptance. */
    @Transactional
    public Map<String, Object> progress(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        Quote q = latest(ref);
        String action = required(body, "action").toUpperCase(Locale.ROOT);
        switch (action) {
            case "SUBMIT" -> {
                user.validateHasPermissionTo("MANAGE_SCHEME");
                expect(q, "DRAFT");
                q.status = "SUBMITTED";
            }
            case "APPROVE", "DECLINE" -> {
                user.validateHasPermissionTo("UNDERWRITE_MEMBER");
                expect(q, "SUBMITTED");
                if (user.getUsername().equalsIgnoreCase(q.createdBy)) {
                    throw new NoAuthorizationException("You prepared " + ref + ", so another underwriter must decide it.");
                }
                q.status = "APPROVE".equals(action) ? "APPROVED" : "DECLINED";
                q.decidedBy = user.getUsername();
                q.decidedAt = LocalDateTime.now();
                q.decisionNote = optional(body, "note");
                if ("APPROVED".equals(q.status)) {
                    outbox.publish(Events.SCHEME_QUOTED, ref, Map.of("quoteRef", ref, "version", q.versionNo,
                            "sponsor", q.sponsorName, "annualPremium", q.annualPremium), user.getUsername());
                }
            }
            case "ACCEPT" -> {
                user.validateHasPermissionTo("MANAGE_SCHEME");
                expect(q, "APPROVED");
                if (q.validUntil.isBefore(LocalDate.now())) {
                    throw new IllegalArgumentException(ref + " v" + q.versionNo + " expired on " + q.validUntil
                            + ". Revise it to quote again.");
                }
                q.status = "ACCEPTED";
            }
            default -> throw new IllegalArgumentException("action must be SUBMIT, APPROVE, DECLINE or ACCEPT.");
        }
        audit.record(user.getUsername(), "Scheme quote " + q.status.toLowerCase(Locale.ROOT), "SchemeQuote", ref,
                "v" + q.versionNo);
        return quoteView(q);
    }

    /**
     * {inceptionDate, wordingConfirmed}: activates the master policy from the accepted quote. Refused without the
     * approved wording and a free-cover limit (AT-GL01-01).
     */
    @Transactional
    public Map<String, Object> activate(String ref, JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        Quote q = latest(ref);
        expect(q, "ACCEPTED");
        if (!body.path("wordingConfirmed").asBoolean(false)) {
            throw new IllegalArgumentException("Confirm the approved scheme wording before activating the master policy.");
        }
        if (q.freeCoverLimit == null) {
            throw new IllegalArgumentException(ref + " has no free-cover limit. Revise the quote to set it before"
                    + " activation.");
        }
        LocalDate inception = LocalDate.parse(required(body, "inceptionDate"));
        ObjectNode scheme = mapper.createObjectNode();
        scheme.put("name", q.sponsorName).put("sector", q.sector).put("inceptionDate", inception.toString())
                .put("renewalDate", inception.plusYears(1).toString());
        // SchemeActivated is published by the scheme's creation, with the quote it came from.
        String schemeRef = schemes.createScheme(scheme, ref).scheme().ref();
        q.status = "ACTIVATED";
        q.schemeRef = schemeRef;
        for (String type : q.benefits.split(",")) {
            Benefit b = new Benefit();
            b.schemeRef = schemeRef;
            b.benefitType = type.trim();
            b.basis = "GLA".equals(b.benefitType) || "LUMP_SUM_DISABILITY".equals(b.benefitType) ? "SALARY_MULTIPLE"
                    : "INCOME_PROTECTION".equals(b.benefitType) ? "MONTHLY_INCOME_PERCENT" : "FIXED";
            b.multiple = "SALARY_MULTIPLE".equals(b.basis) ? q.salaryMultiple
                    : "MONTHLY_INCOME_PERCENT".equals(b.basis) ? new BigDecimal("75.00") : null;
            b.fixedAmount = "FIXED".equals(b.basis) ? PRODUCT_MAXIMUM.get(b.benefitType) : null;
            b.terms = defaultTerms(b.benefitType);
            b.updatedBy = user.getUsername();
            b.updatedAt = LocalDateTime.now();
            // The plan comes from the quote the underwriter approved.
            b.approvalStatus = "APPROVED";
            b.approvedBy = q.decidedBy;
            b.approvedAt = LocalDateTime.now();
            em.persist(b);
        }
        outbox.publish(Events.SCHEME_PLAN_APPROVED, schemeRef, Map.of("schemeRef", schemeRef, "benefits",
                List.of(q.benefits.split(",")), "approvedBy", q.decidedBy == null ? "" : q.decidedBy,
                "source", ref + " v" + q.versionNo), user.getUsername());
        audit.record(user.getUsername(), "Scheme activated", "Scheme", schemeRef, "From " + ref + " v" + q.versionNo);
        return quoteView(q);
    }

    private Quote fill(Quote q, JsonNode body) {
        q.sponsorName = required(body, "sponsorName");
        q.sector = required(body, "sector");
        q.lives = body.path("lives").asInt(0);
        q.salaryRoll = Money.of(new BigDecimal(required(body, "salaryRoll")));
        List<String> benefits = new ArrayList<>();
        JsonNode list = body.get("benefits");
        if (list != null && list.isArray()) {
            list.forEach(b -> benefits.add(b.asText().trim().toUpperCase(Locale.ROOT)));
        } else if (list != null) {
            for (String b : list.asText().split(",")) {
                benefits.add(b.trim().toUpperCase(Locale.ROOT));
            }
        }
        if (benefits.isEmpty() || !BENEFIT_TYPES.containsAll(benefits)) {
            throw new IllegalArgumentException("Choose the scheme's benefits from " + BENEFIT_TYPES.stream().sorted()
                    .toList() + ".");
        }
        q.benefits = String.join(",", benefits);
        // NS-GL01-002: the rating basis is stated, never assumed.
        q.ratingBasis = optional(body, "ratingBasis") == null ? "SALARY" : optional(body, "ratingBasis")
                .toUpperCase(Locale.ROOT);
        if (!Set.of("SALARY", "SUM_ASSURED", "PER_MEMBER", "LOAN_BALANCE").contains(q.ratingBasis)) {
            throw new IllegalArgumentException("ratingBasis must be SALARY, SUM_ASSURED, PER_MEMBER or LOAN_BALANCE.");
        }
        q.salaryMultiple = new BigDecimal(optional(body, "salaryMultiple") == null ? "1" : optional(body,
                "salaryMultiple")).setScale(2, RoundingMode.HALF_UP);
        q.ratePerMille = new BigDecimal(required(body, "ratePerMille")).setScale(4, RoundingMode.HALF_UP);
        if (q.lives < 1 || q.salaryRoll.signum() <= 0 || q.ratePerMille.signum() <= 0 || q.salaryMultiple.signum() <= 0) {
            throw new IllegalArgumentException("The census needs at least one life, a salary roll, a multiple and a"
                    + " rate above zero.");
        }
        BigDecimal sumAssured = q.salaryRoll.multiply(q.salaryMultiple);
        q.annualPremium = sumAssured.multiply(q.ratePerMille).divide(THOUSAND, 2, RoundingMode.HALF_UP);
        q.freeCoverLimit = optional(body, "freeCoverLimit") == null ? null
                : Money.of(new BigDecimal(optional(body, "freeCoverLimit")));
        q.validUntil = LocalDate.now().plusDays(30);
        q.notes = optional(body, "notes");
        return q;
    }

    // ── scheme benefits (NS-GL02-002..005) ────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> benefits(String schemeRef) {
        user("READ_SCHEME");
        return em.createQuery("select b from SchemeBenefit b where b.schemeRef = :ref order by b.benefitType",
                Benefit.class).setParameter("ref", schemeRef).getResultList().stream().map(this::benefitView).toList();
    }

    /** {benefitType, basis, multiple?, fixedAmount?, waitingMonths?, terms?}: defines or amends one benefit. */
    @Transactional
    public Map<String, Object> saveBenefit(String schemeRef, JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        if ("CLOSED".equals(scheme(schemeRef).getStatus())) {
            throw new IllegalArgumentException("Scheme " + schemeRef + " is closed; its benefits can no longer change.");
        }
        String type = required(body, "benefitType").toUpperCase(Locale.ROOT);
        if (!BENEFIT_TYPES.contains(type)) {
            throw new IllegalArgumentException("benefitType must be one of " + BENEFIT_TYPES.stream().sorted().toList());
        }
        String basis = required(body, "basis").toUpperCase(Locale.ROOT);
        if (!Set.of("SALARY_MULTIPLE", "FIXED", "MONTHLY_INCOME_PERCENT", "LOAN_BALANCE").contains(basis)) {
            throw new IllegalArgumentException("basis must be SALARY_MULTIPLE, FIXED, MONTHLY_INCOME_PERCENT or"
                    + " LOAN_BALANCE.");
        }
        BigDecimal fixed = optional(body, "fixedAmount") == null ? null
                : Money.of(new BigDecimal(optional(body, "fixedAmount")));
        // AT-GL02-01: a scheme may not go above the product's maximum for the benefit.
        BigDecimal max = PRODUCT_MAXIMUM.get(type);
        if (max != null && fixed != null && fixed.compareTo(max) > 0) {
            throw new IllegalArgumentException(label(type) + " cannot exceed the product maximum of " + Money.text(max)
                    + "; " + Money.text(fixed) + " was asked for.");
        }
        Benefit b = em.createQuery("select b from SchemeBenefit b where b.schemeRef = :ref and b.benefitType = :t",
                Benefit.class).setParameter("ref", schemeRef).setParameter("t", type).getResultStream().findFirst()
                .orElseGet(Benefit::new);
        b.schemeRef = schemeRef;
        b.benefitType = type;
        b.basis = basis;
        b.multiple = optional(body, "multiple") == null ? null
                : new BigDecimal(optional(body, "multiple")).setScale(2, RoundingMode.HALF_UP);
        b.fixedAmount = fixed;
        b.waitingMonths = optional(body, "waitingMonths") == null ? null : Integer.valueOf(optional(body, "waitingMonths"));
        b.terms = optional(body, "terms") == null ? defaultTerms(type) : optional(body, "terms");
        b.updatedBy = user.getUsername();
        b.updatedAt = LocalDateTime.now();
        // GL02: a changed benefit waits for a second person to approve the plan; only that publishes SchemePlanApproved.
        b.approvalStatus = "PENDING";
        b.approvedBy = null;
        b.approvedAt = null;
        em.persist(b);
        return benefitView(b);
    }

    /** {note}: approves every benefit change waiting on the scheme; not by the person who made any of them. */
    @Transactional
    public List<Map<String, Object>> approvePlan(String schemeRef, JsonNode body) {
        AppUser user = user("UNDERWRITE_MEMBER");
        scheme(schemeRef);
        String note = required(body, "note");
        List<Benefit> pending = em.createQuery("select b from SchemeBenefit b where b.schemeRef = :ref"
                + " and b.approvalStatus = 'PENDING' order by b.benefitType", Benefit.class)
                .setParameter("ref", schemeRef).getResultList();
        if (pending.isEmpty()) {
            throw new ConflictException(schemeRef + " has no benefit change waiting for approval.");
        }
        if (pending.stream().anyMatch(b -> user.getUsername().equalsIgnoreCase(b.updatedBy))) {
            throw new NoAuthorizationException("You changed this plan, so someone else must approve it.");
        }
        for (Benefit b : pending) {
            b.approvalStatus = "APPROVED";
            b.approvedBy = user.getUsername();
            b.approvedAt = LocalDateTime.now();
        }
        outbox.publish(Events.SCHEME_PLAN_APPROVED, schemeRef, Map.of("schemeRef", schemeRef, "benefits",
                pending.stream().map(b -> b.benefitType).toList(), "approvedBy", user.getUsername(), "source", note),
                user.getUsername());
        audit.record(user.getUsername(), "Scheme plan approved", "Scheme", schemeRef, note);
        return benefits(schemeRef);
    }

    private static String defaultTerms(String type) {
        return switch (type) {
            case "GLA" -> "Death benefit as a multiple of annual salary. Actively-at-work and free-cover rules apply.";
            case "LUMP_SUM_DISABILITY" -> "Own-occupation disability after a 6-month assessment period; 50% partial"
                    + " benefit for limited duties.";
            case "CRITICAL_ILLNESS" -> "Core condition set; 30-day survival period; unrelated claims capped at 100%"
                    + " per condition.";
            case "FAMILY" -> "Member 40,000; spouse and child 20,000 plus 2,000 funeral support; up to two spouses;"
                    + " stillbirth from 26 weeks.";
            case "INCOME_PROTECTION" -> "75% of monthly income after a 3-month deferred period, to retirement (GIP) or"
                    + " for up to 24 months (TIP); reviewed every 12 months.";
            default -> "Death and disability up to 750,000; critical illness up to 350,000; temporary disability up"
                    + " to 15,000 a month. Early settlement refunds the unearned premium.";
        };
    }

    // ── renewal, suspension, closure, transfer (NS-GL01-006, NS-GL05-005/006) ─────────────────────

    static final Set<String> EVENT_TYPES = Set.of("RENEWAL", "SUSPENSION", "CLOSURE", "TRANSFER", "REACTIVATION");

    /**
     * What the event would touch before anyone approves it (AT-GL01-03): the members affected (listed), unpaid
     * invoices and the premium already invoiced past the effective date, pending claims and income benefits in
     * payment, borrower certificates, and for a transfer anything that stops the members moving.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> impact(String schemeRef, String eventType, String effectiveDate, String targetSchemeRef) {
        user("READ_SCHEME");
        GroupScheme s = scheme(schemeRef);
        String type = eventType.toUpperCase(Locale.ROOT);
        if (!EVENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("eventType must be RENEWAL, SUSPENSION, CLOSURE, TRANSFER or REACTIVATION.");
        }
        return impactOf(s, type, effective(s, type, effectiveDate), targetSchemeRef == null || targetSchemeRef.isBlank()
                ? null : scheme(targetSchemeRef.trim()));
    }

    private Map<String, Object> impactOf(GroupScheme s, String type, LocalDate effective, GroupScheme target) {
        List<SchemeMember> members = em.createQuery("select m from SchemeMember m where m.scheme = :s",
                SchemeMember.class).setParameter("s", s).getResultList();
        List<SchemeInvoice> unpaid = em.createQuery("select i from SchemeInvoice i where i.scheme = :s"
                + " and i.status <> 'PAID'", SchemeInvoice.class).setParameter("s", s).getResultList();
        LocalDate today = LocalDate.now();
        long active = members.stream().filter(m -> Set.of(SchemeMember.ACTIVE, SchemeMember.PARTIALLY_ACCEPTED)
                .contains(m.getStatus())).count();
        long pending = members.stream().filter(m -> SchemeMember.UNDERWRITING_PENDING.equals(m.getStatus())).count();
        long overAge = members.stream().filter(m -> m.getDateOfBirth() != null && !SchemeMember.CEASED.equals(
                m.getStatus()) && Period.between(m.getDateOfBirth(), today).getYears() >= 65).count();
        BigDecimal outstanding = unpaid.stream().map(SchemeInvoice::getOutstanding).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        long loans = em.createQuery("select count(l) from GroupCreditLoan l where l.schemeRef = :ref"
                + " and l.status = 'ACTIVE'", Long.class).setParameter("ref", s.getRef()).getSingleResult();
        List<Map<String, Object>> affected = lifecycle.affectedMembers(s, type);
        List<Map<String, Object>> pendingClaims = lifecycle.claims(s, GroupClaim.PENDING);
        List<Map<String, Object>> inPayment = lifecycle.claims(s, Set.of(GroupClaim.IN_PAYMENT));
        boolean endsCover = Set.of("SUSPENSION", "CLOSURE", "TRANSFER").contains(type);
        BigDecimal invoicedAfter = endsCover ? lifecycle.premiumInvoicedFrom(s, effective) : BigDecimal.ZERO.setScale(2);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("schemeRef", s.getRef());
        impact.put("schemeName", s.getName());
        impact.put("eventType", type);
        impact.put("effectiveDate", effective);
        impact.put("activeMembers", active);
        impact.put("pendingUnderwriting", pending);
        impact.put("membersAtOrOverCoverAge", overAge);
        impact.put("affectedMembers", affected.size());
        impact.put("members", affected);
        impact.put("unpaidInvoices", unpaid.size());
        impact.put("outstandingAmount", outstanding);
        impact.put("invoices", lifecycle.unpaidInvoices(s));
        impact.put("premiumInvoicedAfterEffective", invoicedAfter);
        impact.put("pendingClaims", pendingClaims.size());
        impact.put("claims", pendingClaims);
        impact.put("claimsInPayment", inPayment.size());
        impact.put("incomeClaims", inPayment);
        impact.put("activeCreditLoans", loans);
        impact.put("policyYear", s.getPolicyYear());
        impact.put("renewalDate", s.getRenewalDate());
        List<String> blockers = new ArrayList<>();
        if ("TRANSFER".equals(type)) {
            impact.put("targetSchemeRef", target == null ? null : target.getRef());
            blockers.addAll(lifecycle.transferBlockers(s, target));
            if (loans > 0) {
                blockers.add(loans + " borrower certificate(s) are in force; group credit loans do not transfer."
                        + " Settle or close them first.");
            }
        }
        impact.put("blockers", blockers);
        List<String> notes = new ArrayList<>();
        if ("RENEWAL".equals(type)) {
            impact.put("nextRenewalDate", s.getRenewalDate().plusYears(1));
            notes.add("Policy year " + s.getPolicyYear() + " ends " + s.getRenewalDate() + "; year "
                    + (s.getPolicyYear() + 1) + " follows with no gap or overlap.");
            notes.add("Approved income-protection claims in payment continue across the renewal.");
            if (overAge > 0) {
                notes.add(overAge + " member(s) are at or past the cover age and need review before renewal.");
            }
            if (pending > 0) {
                notes.add(pending + " member(s) have underwriting outstanding; their evidence stays valid.");
            }
        } else if ("REACTIVATION".equals(type)) {
            notes.add(affected.size() + " suspended member(s) return to cover from " + effective
                    + " in the status they had before; the suspended days stay uncovered and unbilled.");
        } else {
            String verb = "SUSPENSION".equals(type) ? "have cover suspended" : "TRANSFER".equals(type)
                    ? "move to " + (target == null ? "the target scheme" : target.getRef()) : "lose cover";
            notes.add(affected.size() + " member(s) " + verb + " from " + effective + "; their cover history is kept.");
            if ("CLOSURE".equals(type) && pending > 0) {
                notes.add(pending + " member(s) have cover pending underwriting; the pending amounts lapse.");
            }
            if (invoicedAfter.signum() > 0) {
                notes.add(Money.text(invoicedAfter) + " of premium is already invoiced for cover from " + effective
                        + " and is due back to the sponsor.");
            }
            if (loans > 0 && !"TRANSFER".equals(type)) {
                notes.add(loans + " borrower certificate(s) are in force under this scheme and stay in force until their"
                        + " loans end or settle; no new loans are accepted.");
            }
        }
        if (!unpaid.isEmpty()) {
            notes.add(unpaid.size() + " invoice(s) totalling " + Money.text(outstanding) + " stay receivable.");
        }
        if (!pendingClaims.isEmpty()) {
            notes.add(pendingClaims.size() + " claim(s) are pending a decision or payment ("
                    + String.join(", ", pendingClaims.stream().map(c -> (String) c.get("ref")).toList())
                    + "); they are decided on the cover in force on their event date.");
        }
        if (!inPayment.isEmpty() && !"RENEWAL".equals(type)) {
            notes.add(inPayment.size() + " income benefit(s) in payment continue under their own review and cessation"
                    + " terms.");
        }
        notes.addAll(blockers);
        impact.put("notes", notes);
        return impact;
    }

    private static LocalDate effective(GroupScheme s, String type, String text) {
        if ("RENEWAL".equals(type)) {
            return s.getRenewalDate();
        }
        return text == null || text.isBlank() ? LocalDate.now() : LocalDate.parse(text.trim());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(String schemeRef) {
        user("READ_SCHEME");
        return em.createQuery("select e from SchemeEvent e order by e.id desc", SchemeEvent.class).getResultList()
                .stream().filter(e -> schemeRef == null || schemeRef.equals(e.schemeRef)).map(this::eventView).toList();
    }

    /**
     * {eventType RENEWAL|SUSPENSION|CLOSURE|TRANSFER|REACTIVATION, effectiveDate?, rateChangePercent?, targetSchemeRef?,
     * reason}: raised with its impact for a second person to decide.
     */
    @Transactional
    public Map<String, Object> requestEvent(String schemeRef, JsonNode body) {
        AppUser user = user("MANAGE_SCHEME");
        GroupScheme s = scheme(schemeRef);
        String type = required(body, "eventType").toUpperCase(Locale.ROOT);
        if (!EVENT_TYPES.contains(type)) {
            throw new IllegalArgumentException("eventType must be RENEWAL, SUSPENSION, CLOSURE, TRANSFER or REACTIVATION.");
        }
        if ("CLOSED".equals(s.getStatus())) {
            throw new ConflictException(schemeRef + " is closed.");
        }
        if ("REACTIVATION".equals(type) != "SUSPENDED".equals(s.getStatus())) {
            throw new IllegalArgumentException("REACTIVATION".equals(type) ? schemeRef + " is not suspended."
                    : schemeRef + " is suspended; reactivate it first.");
        }
        if (em.createQuery("select count(e) from SchemeEvent e where e.schemeRef = :ref and e.status = 'PENDING'",
                Long.class).setParameter("ref", schemeRef).getSingleResult() > 0) {
            throw new ConflictException(schemeRef + " already has a request awaiting a decision.");
        }
        LocalDate effective = effective(s, type, optional(body, "effectiveDate"));
        if (!"RENEWAL".equals(type) && effective.isBefore(s.getInceptionDate())) {
            throw new IllegalArgumentException("The effective date cannot be before the scheme's inception on "
                    + s.getInceptionDate() + ".");
        }
        GroupScheme target = null;
        if ("TRANSFER".equals(type)) {
            target = scheme(required(body, "targetSchemeRef"));
        }
        Map<String, Object> impact = impactOf(s, type, effective, target);
        @SuppressWarnings("unchecked")
        List<String> blockers = (List<String>) impact.get("blockers");
        if (!blockers.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", blockers));
        }
        SchemeEvent e = new SchemeEvent();
        e.ref = numbers.next("GEV-", 5, () -> 0);
        e.schemeRef = schemeRef;
        e.eventType = type;
        e.status = "PENDING";
        e.effectiveDate = effective;
        e.targetSchemeRef = target == null ? null : target.getRef();
        e.rateChangePercent = optional(body, "rateChangePercent") == null ? null
                : new BigDecimal(optional(body, "rateChangePercent")).setScale(2, RoundingMode.HALF_UP);
        @SuppressWarnings("unchecked")
        String summary = String.join("\n", (List<String>) impact.get("notes"));
        e.impact = summary.length() <= 2000 ? summary : summary.substring(0, 1997) + "...";
        try {
            e.impactDetail = mapper.writeValueAsString(impact);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        e.reason = required(body, "reason");
        e.requestedBy = user.getUsername();
        e.requestedAt = LocalDateTime.now();
        em.persist(e);
        return eventView(e);
    }

    /** {approve, note}: decided by someone other than the requester; approval applies the event to member cover. */
    @Transactional
    public Map<String, Object> decideEvent(String ref, JsonNode body) {
        AppUser user = user("UNDERWRITE_MEMBER");
        SchemeEvent e = em.createQuery("select e from SchemeEvent e where e.ref = :ref", SchemeEvent.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Request " + ref + " not found"));
        if (!"PENDING".equals(e.status)) {
            throw new ConflictException(ref + " is already " + e.status.toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getUsername().equalsIgnoreCase(e.requestedBy)) {
            throw new NoAuthorizationException("You requested " + ref + ", so someone else must decide it.");
        }
        e.decidedBy = user.getUsername();
        e.decidedAt = LocalDateTime.now();
        e.decisionNote = required(body, "note");
        if (!body.path("approve").asBoolean(false)) {
            e.status = "REJECTED";
            return eventView(e);
        }
        e.status = "APPROVED";
        GroupScheme s = scheme(e.schemeRef);
        em.lock(s, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        String actor = user.getUsername();
        switch (e.eventType) {
            case "RENEWAL" -> {
                LocalDate next = s.getRenewalDate().plusYears(1);
                s.renewTo(next, next.format(DISPLAY));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("schemeRef", s.getRef());
                data.put("policyYear", s.getPolicyYear());
                data.put("renewalDate", next.toString());
                data.put("rateChangePercent", e.rateChangePercent == null ? BigDecimal.ZERO : e.rateChangePercent);
                outbox.publish(Events.SCHEME_RENEWED, s.getRef(), data, actor);
            }
            case "SUSPENSION" -> {
                s.setStatus("SUSPENDED");
                lifecycle.suspend(s, e.effectiveDate, actor, ref);
            }
            case "REACTIVATION" -> {
                s.setStatus("ACTIVE");
                lifecycle.reactivate(s, e.effectiveDate, actor, ref);
            }
            case "TRANSFER" -> {
                GroupScheme target = scheme(e.targetSchemeRef);
                em.lock(target, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                int moved = lifecycle.transfer(s, target, e.effectiveDate, actor, ref);
                s.setStatus("CLOSED");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("schemeRef", s.getRef());
                data.put("closedOn", e.effectiveDate.toString());
                data.put("reason", e.reason);
                data.put("transferredTo", target.getRef());
                data.put("membersTransferred", moved);
                outbox.publish(Events.SCHEME_CLOSED, s.getRef(), data, actor);
            }
            default -> {
                int ceased = lifecycle.close(s, e.effectiveDate, actor, ref);
                s.setStatus("CLOSED");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("schemeRef", s.getRef());
                data.put("closedOn", e.effectiveDate.toString());
                data.put("reason", e.reason);
                data.put("membersCeased", ceased);
                outbox.publish(Events.SCHEME_CLOSED, s.getRef(), data, actor);
            }
        }
        audit.record(actor, "Scheme " + e.eventType.toLowerCase(Locale.ROOT) + " approved", "Scheme",
                s.getRef(), ref + ": " + e.decisionNote);
        return eventView(e);
    }

    // ── certificates (NS-GL01-005) ────────────────────────────────────────────────────────────────

    /** A certificate per covered member: what is accepted, and what is still pending and so not yet covered. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> certificates(String schemeRef) {
        user("READ_SCHEME");
        return certificatesOf(scheme(schemeRef));
    }

    /**
     * The sponsor's own certificates for the employer portal (NS-GL01-005, role "its own scheme's members, invoices
     * and certificates"). The caller passes the one scheme the partner login is tied to; nothing else is reachable.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sponsorCertificates(String schemeRef) {
        user("USE_PARTNER_PORTAL");
        return certificatesOf(scheme(schemeRef));
    }

    private List<Map<String, Object>> certificatesOf(GroupScheme s) {
        String schemeRef = s.getRef();
        List<String> benefits = em.createQuery("select b.benefitType from SchemeBenefit b where b.schemeRef = :ref"
                + " order by b.benefitType", String.class).setParameter("ref", schemeRef).getResultList();
        return em.createQuery("select m from SchemeMember m where m.scheme = :s order by m.lastName, m.firstName",
                        SchemeMember.class).setParameter("s", s).getResultList().stream()
                // A member awaiting underwriting is certified for the free cover already in force (AT-GL03-01).
                .filter(m -> SchemeMember.ON_COVER.contains(m.getStatus()) && m.getAcceptedCover().signum() > 0)
                .map(m -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("certificateNo", s.getRef() + "-" + m.getMemberKey());
                    c.put("scheme", s.getName());
                    c.put("schemeRef", s.getRef());
                    c.put("policyYear", s.getPolicyYear());
                    c.put("member", m.getFirstName() + " " + m.getLastName());
                    c.put("memberKey", m.getMemberKey());
                    c.put("category", m.getCategory() == null ? null : m.getCategory().getName());
                    c.put("coverFrom", m.getJoinDate());
                    c.put("coverTo", s.getRenewalDate());
                    // AT-GL03-01: only accepted cover is certified; cover awaiting underwriting is shown as pending.
                    c.put("acceptedCover", m.getAcceptedCover());
                    c.put("pendingCover", m.getPendingCover());
                    c.put("benefits", benefits.isEmpty() ? List.of("GLA") : benefits);
                    return c;
                }).toList();
    }

    // ── group credit (NS-GL06-001..006) ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> creditLoans(String schemeRef) {
        user("READ_SCHEME");
        return em.createQuery("select l from GroupCreditLoan l where l.schemeRef = :ref order by l.id desc",
                CreditLoan.class).setParameter("ref", schemeRef).getResultList().stream().map(this::loanView).toList();
    }

    /**
     * {batchReference, ratePerMille, rows [{lenderLoanId, borrowerName, loanAmount, termMonths}]}: a lender's loan
     * bordereau. A loan already on the scheme is not added twice; one above the scheme's cap is referred.
     */
    @Transactional
    public Map<String, Object> bordereau(String schemeRef, JsonNode body) {
        AppUser user = user("PROCESS_CENSUS");
        GroupScheme s = scheme(schemeRef);
        if (!"ACTIVE".equals(s.getStatus())) {
            throw new IllegalArgumentException(schemeRef + " is " + s.getStatus().toLowerCase(Locale.ROOT)
                    + " and cannot take new loans.");
        }
        Benefit credit = em.createQuery("select b from SchemeBenefit b where b.schemeRef = :ref"
                        + " and b.benefitType = 'CREDIT'", Benefit.class).setParameter("ref", schemeRef)
                .getResultStream().findFirst().orElseThrow(() -> new IllegalArgumentException(schemeRef
                        + " has no Credit benefit. Add it under the scheme's benefits first."));
        BigDecimal cap = credit.fixedAmount == null ? PRODUCT_MAXIMUM.get("CREDIT") : credit.fixedAmount;
        String batch = required(body, "batchReference");
        BigDecimal rate = new BigDecimal(optional(body, "ratePerMille") == null ? "0.85" : optional(body, "ratePerMille"));
        JsonNode rows = body.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must list at least one loan.");
        }
        int accepted = 0;
        int referred = 0;
        int duplicates = 0;
        int rejected = 0;
        BigDecimal premiumTotal = BigDecimal.ZERO;
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode row : rows) {
            String id = row.path("lenderLoanId").asText("").trim();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("lenderLoanId", id);
            try {
                if (id.isEmpty() || row.path("borrowerName").asText("").isBlank()) {
                    throw new IllegalArgumentException("Loan id and borrower name are required.");
                }
                if (em.createQuery("select count(l) from GroupCreditLoan l where l.schemeRef = :ref"
                        + " and l.lenderLoanId = :id", Long.class).setParameter("ref", schemeRef).setParameter("id", id)
                        .getSingleResult() > 0) {
                    duplicates++;
                    result.put("outcome", "DUPLICATE");
                    results.add(result);
                    continue;
                }
                BigDecimal amount = Money.of(new BigDecimal(row.path("loanAmount").asText()));
                int term = row.path("termMonths").asInt(0);
                if (amount.signum() <= 0 || term < 1 || term > 120) {
                    throw new IllegalArgumentException("Loan amount and a term of 1–120 months are required.");
                }
                CreditLoan l = new CreditLoan();
                l.schemeRef = schemeRef;
                l.lenderLoanId = id;
                l.certificateNo = schemeRef + "-L" + id;
                l.borrowerName = row.path("borrowerName").asText().trim();
                l.loanAmount = amount;
                l.termMonths = term;
                l.batchRef = batch;
                l.startDate = LocalDate.now();
                l.createdAt = LocalDateTime.now();
                // AT-GL06-02: above the cap the loan is referred with the limit shown, not silently covered.
                boolean over = amount.compareTo(cap) > 0;
                l.coverAmount = over ? cap : amount;
                l.status = over ? "REFERRED" : "ACTIVE";
                l.note = over ? "Loan " + Money.text(amount) + " is above the scheme cap of " + Money.text(cap)
                        + ": referred to underwriting; cover held at the cap." : null;
                l.premium = l.coverAmount.multiply(rate).multiply(BigDecimal.valueOf(term)).divide(THOUSAND, 2,
                        RoundingMode.HALF_UP);
                em.persist(l);
                if (over) {
                    referred++;
                } else {
                    accepted++;
                    premiumTotal = premiumTotal.add(l.premium);
                    outbox.publish(Events.BORROWER_COVER_ACTIVATED, l.certificateNo, Map.of("schemeRef", schemeRef,
                            "certificateNo", l.certificateNo, "coverAmount", l.coverAmount), user.getUsername());
                }
                result.put("outcome", l.status);
                result.put("certificateNo", l.certificateNo);
                result.put("note", l.note);
            } catch (RuntimeException ex) {
                rejected++;
                result.put("outcome", "REJECTED");
                result.put("note", ex.getMessage());
            }
            results.add(result);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchReference", batch);
        out.put("rows", rows.size());
        out.put("accepted", accepted);
        out.put("referred", referred);
        out.put("duplicates", duplicates);
        out.put("rejected", rejected);
        out.put("premiumTotal", Money.of(premiumTotal));
        out.put("results", results);
        outbox.publish(Events.CREDIT_BORDEREAU_PROCESSED, schemeRef, Map.of("schemeRef", schemeRef, "batchReference",
                batch, "accepted", accepted, "referred", referred, "duplicates", duplicates), user.getUsername());
        return out;
    }

    /**
     * The early-settlement preview (GL06): the refund a settlement on the date would give, without settling. The
     * formula is the one settlement applies (unearned months pro rata, less 10%); the insurer confirms it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> settlementPreview(Long id, String settlementDate) {
        user("READ_SCHEME");
        CreditLoan l = em.find(CreditLoan.class, id);
        if (l == null) {
            throw new NotFoundException("Loan not found");
        }
        LocalDate date = settlementDate == null || settlementDate.isBlank() ? LocalDate.now()
                : LocalDate.parse(settlementDate.trim());
        long used = monthsUsed(l, date);
        Map<String, Object> v = loanView(l);
        v.put("settlementDate", date);
        v.put("monthsUsed", used);
        v.put("monthsUnearned", l.termMonths - used);
        v.put("refundPreview", "SETTLED".equals(l.status) ? l.refund : refund(l, used));
        v.put("alreadySettled", "SETTLED".equals(l.status));
        return v;
    }

    private static long monthsUsed(CreditLoan l, LocalDate date) {
        return Math.min(l.termMonths, Math.max(0, ChronoUnit.MONTHS.between(l.startDate, date))
                + (date.isAfter(l.startDate.plusMonths(ChronoUnit.MONTHS.between(l.startDate, date))) ? 1 : 0));
    }

    private static BigDecimal refund(CreditLoan l, long used) {
        return l.premium.multiply(BigDecimal.valueOf(l.termMonths - used)).divide(BigDecimal.valueOf(l.termMonths),
                2, RoundingMode.HALF_UP).multiply(new BigDecimal("0.90")).setScale(2, RoundingMode.HALF_UP);
    }

    /** {settlementDate?}: closes the borrower's cover and refunds the unearned premium, once. */
    @Transactional
    public Map<String, Object> settleLoan(Long id, JsonNode body) {
        AppUser user = user("BILL_SCHEME");
        CreditLoan l = em.find(CreditLoan.class, id);
        if (l == null) {
            throw new NotFoundException("Loan not found");
        }
        if ("SETTLED".equals(l.status)) {
            return loanView(l);
        }
        LocalDate date = optional(body, "settlementDate") == null ? LocalDate.now()
                : LocalDate.parse(optional(body, "settlementDate"));
        long used = monthsUsed(l, date);
        l.refund = refund(l, used);
        l.status = "SETTLED";
        l.settledOn = date;
        l.note = "Settled early: " + (l.termMonths - used) + " of " + l.termMonths + " months unearned, less 10%.";
        if (l.refund.signum() > 0) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("schemeRef", l.schemeRef);
            data.put("certificateNo", l.certificateNo);
            data.put("customerName", l.borrowerName);
            data.put("amount", l.refund.toPlainString());
            outbox.publish(Events.GROUP_PREMIUM_REFUNDED, l.certificateNo, data, user.getUsername());
            // GL06: the settling administrator (BILL_SCHEME) approves the refund as previewed.
            Map<String, Object> approved = new LinkedHashMap<>();
            approved.put("schemeRef", l.schemeRef);
            approved.put("certificateNo", l.certificateNo);
            approved.put("settlementDate", date.toString());
            approved.put("amount", l.refund.toPlainString());
            outbox.publish(Events.UNEARNED_PREMIUM_REFUND_APPROVED, l.certificateNo, approved, user.getUsername());
        }
        audit.record(user.getUsername(), "Group credit loan settled", "Scheme", l.schemeRef, l.certificateNo
                + " refund " + Money.text(l.refund));
        return loanView(l);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private GroupScheme scheme(String ref) {
        return em.createQuery("select s from GroupScheme s where s.ref = :ref", GroupScheme.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Scheme " + ref + " not found"));
    }

    private Quote latest(String ref) {
        return em.createQuery("select q from SchemeQuote q where q.ref = :ref order by q.versionNo desc", Quote.class)
                .setParameter("ref", ref).setMaxResults(1).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Quote " + ref + " not found"));
    }

    private static void expect(Quote q, String status) {
        if (!status.equals(q.status)) {
            throw new ConflictException(q.ref + " v" + q.versionNo + " is " + q.status.toLowerCase(Locale.ROOT)
                    + "; it must be " + status.toLowerCase(Locale.ROOT) + " for this step.");
        }
    }

    private Map<String, Object> quoteView(Quote q) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", q.ref);
        v.put("version", q.versionNo);
        v.put("sponsorName", q.sponsorName);
        v.put("sector", q.sector);
        v.put("lives", q.lives);
        v.put("salaryRoll", q.salaryRoll);
        v.put("benefits", List.of(q.benefits.split(",")));
        v.put("ratingBasis", q.ratingBasis);
        v.put("salaryMultiple", q.salaryMultiple);
        v.put("ratePerMille", q.ratePerMille);
        v.put("sumAssured", Money.of(q.salaryRoll.multiply(q.salaryMultiple)));
        v.put("annualPremium", q.annualPremium);
        v.put("freeCoverLimit", q.freeCoverLimit);
        v.put("validUntil", q.validUntil);
        v.put("status", q.status);
        v.put("notes", q.notes);
        v.put("schemeRef", q.schemeRef);
        v.put("createdBy", q.createdBy);
        v.put("createdAt", q.createdAt);
        v.put("decidedBy", q.decidedBy);
        v.put("decisionNote", q.decisionNote);
        return v;
    }

    private Map<String, Object> benefitView(Benefit b) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("benefitType", b.benefitType);
        v.put("basis", b.basis);
        v.put("multiple", b.multiple);
        v.put("fixedAmount", b.fixedAmount);
        v.put("waitingMonths", b.waitingMonths);
        v.put("terms", b.terms);
        v.put("productMaximum", PRODUCT_MAXIMUM.get(b.benefitType));
        v.put("updatedBy", b.updatedBy);
        v.put("updatedAt", b.updatedAt);
        v.put("approvalStatus", b.approvalStatus);
        v.put("approvedBy", b.approvedBy);
        v.put("approvedAt", b.approvedAt);
        return v;
    }

    private Map<String, Object> eventView(SchemeEvent e) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ref", e.ref);
        v.put("schemeRef", e.schemeRef);
        v.put("eventType", e.eventType);
        v.put("status", e.status);
        v.put("effectiveDate", e.effectiveDate);
        v.put("rateChangePercent", e.rateChangePercent);
        v.put("impact", List.of(e.impact.split("\n")));
        v.put("reason", e.reason);
        v.put("requestedBy", e.requestedBy);
        v.put("requestedAt", e.requestedAt);
        v.put("decidedBy", e.decidedBy);
        v.put("decisionNote", e.decisionNote);
        v.put("targetSchemeRef", e.targetSchemeRef);
        try {
            v.put("impactDetail", e.impactDetail == null ? null : mapper.readTree(e.impactDetail));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            v.put("impactDetail", null);
        }
        return v;
    }

    private Map<String, Object> loanView(CreditLoan l) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", l.id);
        v.put("certificateNo", l.certificateNo);
        v.put("lenderLoanId", l.lenderLoanId);
        v.put("borrowerName", l.borrowerName);
        v.put("loanAmount", l.loanAmount);
        v.put("coverAmount", l.coverAmount);
        v.put("termMonths", l.termMonths);
        v.put("premium", l.premium);
        v.put("status", l.status);
        v.put("note", l.note);
        v.put("batchRef", l.batchRef);
        v.put("startDate", l.startDate);
        v.put("settledOn", l.settledOn);
        v.put("refund", l.refund);
        return v;
    }

    private static String label(String code) {
        String t = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return t.substring(0, 1).toUpperCase(Locale.ROOT) + t.substring(1);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String required(JsonNode body, String field) {
        String v = optional(body, field);
        if (v == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return v;
    }

    private static String optional(JsonNode body, String field) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String v = body.get(field).asText().trim();
        return v.isEmpty() ? null : v;
    }
}
