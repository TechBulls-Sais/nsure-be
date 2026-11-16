package com.nsure.credit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.credit.CreditRecords.BalanceUpdate;
import com.nsure.credit.CreditRecords.Lender;
import com.nsure.credit.CreditRecords.Loan;
import com.nsure.credit.CreditRecords.Refund;
import com.nsure.credit.CreditRecords.Rule;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.portfolio.PortfolioService;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.portfolio.WorkflowHistory;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IL05 Individual credit life (Nsure Life NS-IL05-001..006).
 *
 * <p>A loan is registered for a lender and gets its own credit life policy. Cover is active only once the lender has
 * confirmed disbursement and the first premium has been received. The outstanding balance is the lender's figure,
 * taken from referenced balance messages: a repeated message changes nothing, and a stale or conflicting one is kept
 * as an exception that blocks automatic settlement. A claim pays the lender the balance at the event date and any
 * surplus to the estate, and only on a balance that is current. An early settlement closes the cover once and raises
 * at most one refund, worked out by the product's formula.
 */
@Service
public class CreditService {

    public static final String PRODUCT = "Individual Credit";
    static final String OPEN = "OPEN";
    static final String SETTLED = "SETTLED";
    static final String CLAIMED = "CLAIMED";
    static final String CANCELLED = "CANCELLED";
    static final String APPLIED = "APPLIED";
    static final String CONFLICT = "CONFLICT";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final EvidenceChain audit;
    private final WorkflowHistory history;
    private final PolicyWorkflow workflow;
    private final PortfolioService portfolio;
    private final ObjectMapper mapper;

    public CreditService(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers,
            EvidenceChain audit, WorkflowHistory history, PolicyWorkflow workflow, PortfolioService portfolio,
            ObjectMapper mapper) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.audit = audit;
        this.history = history;
        this.workflow = workflow;
        this.portfolio = portfolio;
        this.mapper = mapper;
    }

    // ── lenders and rules ─────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CreditViews.LenderView> lenders() {
        user("READ_CREDIT");
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : em.createQuery("select l.lenderRef, count(l) from Loan l group by l.lenderRef", Object[].class)
                .getResultList()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return em.createQuery("select l from Lender l order by l.name", Lender.class).getResultList().stream()
                .map(l -> new CreditViews.LenderView(l.ref, l.name, l.contactEmail, l.active,
                        counts.getOrDefault(l.ref, 0L)))
                .toList();
    }

    /** {name, contactEmail?} */
    @Transactional
    public CreditViews.LenderView addLender(JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        String name = required(body, "name", 150);
        if (em.createQuery("select count(l) from Lender l where lower(l.name) = :n", Long.class)
                .setParameter("n", name.toLowerCase(Locale.ROOT)).getSingleResult() > 0) {
            throw new ConflictException(name + " is already a lender.");
        }
        Lender l = new Lender();
        l.ref = numbers.next("LND-", 4, () -> numbers.highest("Lender", "LND-"));
        l.name = name;
        l.contactEmail = optional(body, "contactEmail");
        l.active = true;
        l.createdBy = user.getUsername();
        l.createdAt = LocalDateTime.now();
        em.persist(l);
        audit.record(user.getUsername(), "Lender registered", "Lender", l.ref, name);
        return new CreditViews.LenderView(l.ref, l.name, l.contactEmail, true, 0);
    }

    @Transactional(readOnly = true)
    public CreditViews.RuleView rule() {
        user("READ_CREDIT");
        return ruleView(productRule());
    }

    /** Changes the credit rule; loans already registered keep the premium and benefits they were given. */
    @Transactional
    public CreditViews.RuleView updateRule(JsonNode body) {
        AppUser user = user("MANAGE_CREDIT_RULE");
        Rule r = productRule();
        r.minEntryAge = body.path("minEntryAge").asInt(r.minEntryAge);
        r.maxExitAge = body.path("maxExitAge").asInt(r.maxExitAge);
        r.exceptionExitAge = body.path("exceptionExitAge").asInt(r.exceptionExitAge);
        r.minTermMonths = body.path("minTermMonths").asInt(r.minTermMonths);
        r.maxTermMonths = body.path("maxTermMonths").asInt(r.maxTermMonths);
        r.waitingMonths = body.path("waitingMonths").asInt(r.waitingMonths);
        r.staleBalanceDays = body.path("staleBalanceDays").asInt(r.staleBalanceDays);
        if (optional(body, "ratePerMille") != null) {
            r.ratePerMille = new BigDecimal(optional(body, "ratePerMille")).setScale(4, RoundingMode.HALF_UP);
        }
        if (optional(body, "refundDeductionPercent") != null) {
            r.refundDeductionPercent = new BigDecimal(optional(body, "refundDeductionPercent"))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        if (optional(body, "permanentDisability") != null) {
            r.permanentDisability = optional(body, "permanentDisability").toUpperCase(Locale.ROOT);
        }
        if (optional(body, "coverBasis") != null) {
            r.coverBasis = optional(body, "coverBasis").toUpperCase(Locale.ROOT);
        }
        r.temporaryDisabilityOffered = body.path("temporaryDisabilityOffered").asBoolean(r.temporaryDisabilityOffered);
        r.criticalIllnessOffered = body.path("criticalIllnessOffered").asBoolean(r.criticalIllnessOffered);
        if (r.minEntryAge < 0 || r.minEntryAge > r.maxExitAge || r.maxExitAge > r.exceptionExitAge
                || r.exceptionExitAge > 120 || r.minTermMonths < 1 || r.minTermMonths > r.maxTermMonths
                || r.ratePerMille.signum() <= 0 || r.waitingMonths < 0 || r.staleBalanceDays < 1
                || r.refundDeductionPercent.signum() < 0 || r.refundDeductionPercent.compareTo(HUNDRED) > 0
                || !Set.of("STANDARD", "OPTIONAL").contains(r.permanentDisability)
                || !Set.of("OUTSTANDING_BALANCE", "ORIGINAL_PRINCIPAL").contains(r.coverBasis)) {
            throw new IllegalArgumentException("The rule is not consistent: check the ages, terms, rate, deduction,"
                    + " permanent disability (STANDARD or OPTIONAL) and cover basis (OUTSTANDING_BALANCE or"
                    + " ORIGINAL_PRINCIPAL).");
        }
        r.updatedBy = user.getUsername();
        r.updatedAt = LocalDateTime.now();
        audit.record(user.getUsername(), "Credit rule changed", "Product", r.product, null);
        return ruleView(r);
    }

    // ── loan intake (NS-IL05-001/002/003) ─────────────────────────────────────────────────────────

    /**
     * Registers a loan and opens its credit life policy as an offer: {lenderRef, lenderLoanId, customerRef,
     * principal, termMonths, premiumBasis SINGLE|REGULAR, secured?, pensioner?, permanentDisability?,
     * temporaryDisability?, criticalIllness?, refinancedFrom?}. The same loan sent again returns the first result.
     */
    @Transactional
    public CreditViews.LoanView submit(JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        return view(register(body, user), productRule());
    }

    private Loan register(JsonNode body, AppUser user) {
        Rule rule = productRule();
        Lender lender = lender(required(body, "lenderRef", 20));
        if (!lender.active) {
            throw new IllegalArgumentException(lender.name + " is not an active lender.");
        }
        String lenderLoanId = required(body, "lenderLoanId", 60);
        BigDecimal principal = Money.of(new BigDecimal(required(body, "principal", 20)));
        int term = Integer.parseInt(required(body, "termMonths", 4));
        Optional<Loan> existing = em.createQuery("select l from Loan l where l.lenderRef = :lender"
                        + " and l.lenderLoanId = :id", Loan.class)
                .setParameter("lender", lender.ref).setParameter("id", lenderLoanId).getResultStream().findFirst();
        if (existing.isPresent()) {
            Loan l = existing.get();
            if (l.principal.compareTo(principal) == 0 && l.termMonths == term) {
                return l;
            }
            throw new ConflictException(lender.name + " loan " + lenderLoanId + " is already registered as " + l.ref
                    + " with different terms.");
        }
        if (principal.signum() <= 0) {
            throw new IllegalArgumentException("The principal must be above zero.");
        }
        if (term < rule.minTermMonths || term > rule.maxTermMonths) {
            throw new IllegalArgumentException("The loan term must be " + rule.minTermMonths + "–" + rule.maxTermMonths
                    + " months for " + rule.product + "; this loan runs " + term + ".");
        }
        CustomerAccount borrower = em.createQuery("select c from CustomerAccount c where c.ref = :ref",
                        CustomerAccount.class)
                .setParameter("ref", required(body, "customerRef", 40).toUpperCase(Locale.ROOT)).getResultStream()
                .findFirst().orElseThrow(() -> new NotFoundException("Borrower not found"));
        if (borrower.getDateOfBirth() == null) {
            throw new IllegalArgumentException(borrower.getName() + " has no date of birth on record, so entry and"
                    + " cessation ages cannot be checked.");
        }
        boolean secured = body.path("secured").asBoolean(false);
        boolean pensioner = body.path("pensioner").asBoolean(false);
        LocalDate today = LocalDate.now();
        int entryAge = Period.between(borrower.getDateOfBirth(), today).getYears();
        int exitAge = Period.between(borrower.getDateOfBirth(), today.plusMonths(term)).getYears();
        int exitLimit = secured || pensioner ? rule.exceptionExitAge : rule.maxExitAge;
        if (entryAge < rule.minEntryAge || entryAge > exitLimit) {
            throw new IllegalArgumentException(borrower.getName() + " is " + entryAge + "; entry age for "
                    + rule.product + " is " + rule.minEntryAge + "–" + exitLimit + ".");
        }
        if (exitAge > exitLimit) {
            throw new IllegalArgumentException("Cover would run until age " + exitAge + ", past the cessation age of "
                    + exitLimit + (secured || pensioner ? " for secured and pensioner loans." : ". Secured and"
                            + " pensioner loans may run to " + rule.exceptionExitAge + "."));
        }
        String basis = optional(body, "premiumBasis") == null ? "REGULAR"
                : optional(body, "premiumBasis").toUpperCase(Locale.ROOT);
        if (!Set.of("SINGLE", "REGULAR").contains(basis)) {
            throw new IllegalArgumentException("premiumBasis must be SINGLE or REGULAR.");
        }
        boolean td = body.path("temporaryDisability").asBoolean(false);
        boolean ci = body.path("criticalIllness").asBoolean(false);
        if (td && !rule.temporaryDisabilityOffered) {
            throw new IllegalArgumentException(rule.product + " does not offer the temporary disability benefit.");
        }
        if (ci && !rule.criticalIllnessOffered) {
            throw new IllegalArgumentException(rule.product + " does not offer the critical illness benefit.");
        }
        BigDecimal monthly = principal.multiply(rule.ratePerMille).divide(THOUSAND, 2, RoundingMode.HALF_UP);
        BigDecimal single = "SINGLE".equals(basis) ? Money.of(monthly.multiply(BigDecimal.valueOf(term))) : null;

        Loan l = new Loan();
        l.ref = numbers.next("LN-", 6, () -> 0);
        l.lenderRef = lender.ref;
        l.lenderLoanId = lenderLoanId;
        l.customerRef = borrower.getRef();
        l.borrowerName = borrower.getName();
        l.product = rule.product;
        l.principal = principal;
        l.termMonths = term;
        l.secured = secured;
        l.pensioner = pensioner;
        l.premiumBasis = basis;
        l.monthlyPremium = monthly;
        l.singlePremium = single;
        // The sign-off is contradictory on permanent disability, so the product rule settles it (NS-IL05-003).
        l.permanentDisability = "STANDARD".equals(rule.permanentDisability)
                || body.path("permanentDisability").asBoolean(false);
        l.temporaryDisability = td;
        l.criticalIllness = ci;
        l.waitingMonths = rule.waitingMonths;
        l.status = OPEN;
        l.balance = principal;
        l.refinancedFrom = optional(body, "refinancedFrom");
        l.createdBy = user.getUsername();
        l.createdAt = LocalDateTime.now();

        // The policy is an offer until its first premium arrives; the same loan never opens two policies.
        ObjectNode policy = mapper.createObjectNode();
        policy.put("customerRef", borrower.getRef());
        policy.put("product", rule.product);
        policy.put("amount", principal);
        policy.put("premium", single == null ? monthly : single);
        l.policyRef = portfolio.createPolicy(policy, "LOAN:" + lender.ref + ":" + lenderLoanId).id();
        if (single != null) {
            workflow.policy(l.policyRef).setPremiumFrequency("SINGLE");
        }
        em.persist(l);
        audit.record(user.getUsername(), "Loan registered", "Loan", l.ref, lender.name + " " + lenderLoanId
                + " · policy " + l.policyRef);
        return l;
    }

    /** {disbursementDate, reference}: the lender's confirmation that the money left. Cover still needs the premium. */
    @Transactional
    public CreditViews.LoanView disburse(String ref, JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        Loan l = loan(ref);
        requireOpen(l);
        LocalDate date = LocalDate.parse(required(body, "disbursementDate", 10));
        String reference = required(body, "reference", 80);
        if (l.disbursementDate != null) {
            if (reference.equals(l.disbursementReference)) {
                return view(l, productRule());
            }
            throw new ConflictException(ref + " was already disbursed on " + l.disbursementDate + ".");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The disbursement date cannot be in the future.");
        }
        l.disbursementDate = date;
        l.disbursementReference = reference;
        balanceRow(l, reference, date, l.principal, APPLIED, "Disbursed", user.getUsername());
        l.balanceAsOf = date;
        Map<String, Object> data = base(l);
        data.put("disbursementDate", date.toString());
        data.put("principal", l.principal);
        data.put("coverActive", "COVER_ACTIVE".equals(statusOf(l, policyOf(l))));
        outbox.publish(Events.LOAN_DISBURSED, l.ref, data, user.getUsername());
        return view(l, productRule());
    }

    // ── balances (NS-IL05-004) ────────────────────────────────────────────────────────────────────

    /** {sourceReference, effectiveDate, balance}: one balance message. Sent again, it changes nothing. */
    @Transactional
    public CreditViews.LoanDetail updateBalance(String ref, JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        Loan l = loan(ref);
        applyBalance(l, required(body, "sourceReference", 80), LocalDate.parse(required(body, "effectiveDate", 10)),
                Money.of(new BigDecimal(required(body, "balance", 20))), user.getUsername());
        return detail(l);
    }

    /** {batchReference, rows: [{lenderLoanId, effectiveDate, balance}]}: a lender's balance file, row by row. */
    @Transactional
    public CreditViews.BatchResult balanceBatch(String lenderRef, JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        Lender lender = lender(lenderRef);
        String batch = required(body, "batchReference", 60);
        JsonNode rows = body.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("rows must list at least one balance.");
        }
        List<CreditViews.BatchRow> results = new ArrayList<>();
        int applied = 0;
        int duplicates = 0;
        int conflicts = 0;
        int unknown = 0;
        for (JsonNode row : rows) {
            String id = row.path("lenderLoanId").asText("").trim();
            Loan l = em.createQuery("select l from Loan l where l.lenderRef = :lender and l.lenderLoanId = :id",
                            Loan.class)
                    .setParameter("lender", lender.ref).setParameter("id", id).getResultStream().findFirst()
                    .orElse(null);
            if (l == null) {
                unknown++;
                results.add(new CreditViews.BatchRow(id, "UNKNOWN_LOAN", "No loan with this id for " + lender.name));
                continue;
            }
            try {
                String outcome = applyBalance(l, batch + ":" + id, LocalDate.parse(row.path("effectiveDate").asText()),
                        Money.of(new BigDecimal(row.path("balance").asText())), user.getUsername());
                switch (outcome) {
                    case APPLIED -> applied++;
                    case "DUPLICATE" -> duplicates++;
                    default -> conflicts++;
                }
                results.add(new CreditViews.BatchRow(id, outcome, CONFLICT.equals(outcome) ? l.balanceException : null));
            } catch (RuntimeException ex) {
                conflicts++;
                results.add(new CreditViews.BatchRow(id, "REJECTED", ex.getMessage()));
            }
        }
        audit.record(user.getUsername(), "Balance file processed", "Lender", lender.ref, batch + ": " + applied
                + " applied, " + duplicates + " duplicate, " + conflicts + " held, " + unknown + " unknown");
        return new CreditViews.BatchResult(batch, rows.size(), applied, duplicates, conflicts, unknown, results);
    }

    /** APPLIED, DUPLICATE or CONFLICT. A conflict is stored and flags the loan; it never becomes the balance. */
    private String applyBalance(Loan l, String source, LocalDate effective, BigDecimal balance, String actor) {
        requireOpen(l);
        if (l.disbursementDate == null) {
            throw new IllegalArgumentException(l.ref + " has not been disbursed, so it has no balance to update.");
        }
        if (em.createQuery("select count(b) from LoanBalanceUpdate b where b.loanRef = :ref and b.sourceReference = :s",
                Long.class).setParameter("ref", l.ref).setParameter("s", source).getSingleResult() > 0) {
            return "DUPLICATE";
        }
        String problem = null;
        if (effective.isAfter(LocalDate.now())) {
            problem = "Balance dated " + effective + " is in the future.";
        } else if (effective.isBefore(l.disbursementDate)) {
            problem = "Balance dated " + effective + " is before disbursement on " + l.disbursementDate + ".";
        } else if (l.balanceAsOf != null && effective.isBefore(l.balanceAsOf)) {
            problem = "Balance dated " + effective + " is older than the balance held as of " + l.balanceAsOf + ".";
        } else if (balance.signum() < 0 || balance.compareTo(l.principal) > 0) {
            problem = "Balance " + Money.text(balance) + " is outside 0 to the principal of " + Money.text(l.principal)
                    + ".";
        }
        if (problem != null) {
            balanceRow(l, source, effective, balance, CONFLICT, problem, actor);
            l.balanceException = problem + " (" + source + ")";
            return CONFLICT;
        }
        balanceRow(l, source, effective, balance, APPLIED, null, actor);
        l.balance = balance;
        l.balanceAsOf = effective;
        Map<String, Object> data = base(l);
        data.put("balance", balance);
        data.put("effectiveDate", effective.toString());
        data.put("sourceReference", source);
        outbox.publish(Events.LOAN_BALANCE_UPDATED, l.ref, data, actor);
        return APPLIED;
    }

    /** {note}: clears a balance exception once the lender has confirmed the right figure. */
    @Transactional
    public CreditViews.LoanDetail resolveException(String ref, JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        Loan l = loan(ref);
        if (l.balanceException == null) {
            throw new ConflictException(ref + " has no balance exception.");
        }
        String note = required(body, "note", 300);
        audit.record(user.getUsername(), "Balance exception resolved", "Loan", ref, l.balanceException + " → " + note);
        l.balanceException = null;
        return detail(l);
    }

    private void balanceRow(Loan l, String source, LocalDate effective, BigDecimal balance, String status, String note,
            String actor) {
        BalanceUpdate b = new BalanceUpdate();
        b.loanRef = l.ref;
        b.sourceReference = source;
        b.effectiveDate = effective;
        b.balance = balance;
        b.status = status;
        b.note = note;
        b.receivedBy = actor;
        b.receivedAt = LocalDateTime.now();
        em.persist(b);
    }

    // ── claims (NS-IL05-005) ──────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CreditViews.ClaimAssessment assessClaim(String ref, LocalDate eventDate) {
        user("READ_CREDIT");
        Loan l = loan(ref);
        return assess(l, policyOf(l), productRule(), eventDate == null ? LocalDate.now() : eventDate);
    }

    /** {eventDate, claimRef, payeeVerified}: fixes who the claim pays. Refused without a current balance. */
    @Transactional(noRollbackFor = CreditManualResolutionException.class)
    public CreditViews.LoanDetail recordClaim(String ref, JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        Loan l = loan(ref);
        requireOpen(l);
        LocalDate eventDate = LocalDate.parse(required(body, "eventDate", 10));
        CreditViews.ClaimAssessment a = assess(l, policyOf(l), productRule(), eventDate);
        if (!a.coverActiveAtEvent()) {
            throw new IllegalArgumentException(ref + " had no active cover on " + eventDate + ": " + a.notes().get(0));
        }
        if (a.manualResolution()) {
            // AT-IL05-03: no assumed balance. The loan is flagged for the lender to confirm the figure.
            String why = String.join(" ", a.notes());
            l.balanceException = clip("Claim for " + eventDate + " needs a confirmed balance: " + why, 500);
            audit.record(user.getUsername(), "Credit claim routed to manual resolution", "Loan", ref, why);
            throw new CreditManualResolutionException(ref + " cannot be settled automatically: " + why
                    + " It has been routed for manual resolution.");
        }
        if (!body.path("payeeVerified").asBoolean(false)) {
            throw new IllegalArgumentException("Confirm the lender's payment details have been verified.");
        }
        l.claimRef = optional(body, "claimRef");
        l.claimEventDate = eventDate;
        l.lenderAmount = a.lenderAmount();
        l.estateAmount = a.estateAmount();
        l.status = CLAIMED;
        Map<String, Object> data = base(l);
        data.put("reason", "CLAIM");
        data.put("eventDate", eventDate.toString());
        data.put("lenderAmount", a.lenderAmount());
        data.put("estateAmount", a.estateAmount());
        outbox.publish(Events.CREDIT_COVER_TERMINATED, l.ref, data, user.getUsername());
        audit.record(user.getUsername(), "Credit claim allocated", "Loan", ref, "Lender " + Money.text(a.lenderAmount())
                + ", estate " + Money.text(a.estateAmount()));
        return detail(l);
    }

    private CreditViews.ClaimAssessment assess(Loan l, InsurancePolicy policy, Rule rule, LocalDate eventDate) {
        List<String> notes = new ArrayList<>();
        LocalDate coverStart = coverStart(l, policy);
        boolean covered = coverStart != null && !eventDate.isBefore(coverStart)
                && !eventDate.isAfter(coverStart.plusMonths(l.termMonths));
        if (coverStart == null) {
            notes.add(l.disbursementDate == null ? "The loan has not been disbursed." : "The first premium has not"
                    + " been received.");
        } else if (!covered) {
            notes.add("Cover ran from " + coverStart + " to " + coverStart.plusMonths(l.termMonths) + ".");
        }
        boolean waiting = covered && eventDate.isBefore(coverStart.plusMonths(l.waitingMonths));
        if (waiting) {
            notes.add("Within the " + l.waitingMonths + "-month waiting period: only accidental events are covered.");
        }
        // The balance that applied on the event date: the latest applied message dated on or before it.
        BalanceUpdate at = em.createQuery("select b from LoanBalanceUpdate b where b.loanRef = :ref and b.status = 'APPLIED'"
                        + " and b.effectiveDate <= :d order by b.effectiveDate desc, b.id desc", BalanceUpdate.class)
                .setParameter("ref", l.ref).setParameter("d", eventDate).setMaxResults(1).getResultStream().findFirst()
                .orElse(null);
        boolean manual = false;
        if (at == null) {
            manual = true;
            notes.add("There is no lender balance dated on or before " + eventDate + ".");
        } else if (ChronoUnit.DAYS.between(at.effectiveDate, eventDate) > rule.staleBalanceDays) {
            manual = true;
            notes.add("The latest lender balance is dated " + at.effectiveDate + ", more than "
                    + rule.staleBalanceDays + " days before the event.");
        }
        if (l.balanceException != null) {
            manual = true;
            notes.add("A balance exception is open: " + l.balanceException);
        }
        BigDecimal sumAssured = l.principal;
        BigDecimal balance = at == null ? null : at.balance;
        BigDecimal lender = manual || balance == null ? null : balance.min(sumAssured);
        BigDecimal estate = lender == null ? null : "ORIGINAL_PRINCIPAL".equals(rule.coverBasis)
                ? sumAssured.subtract(lender) : Money.of(BigDecimal.ZERO);
        return new CreditViews.ClaimAssessment(eventDate, covered, waiting, sumAssured, balance,
                at == null ? null : at.effectiveDate, manual, lender, estate, notes);
    }

    // ── early settlement and refunds (NS-IL05-006) ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public CreditViews.SettlementPreview previewSettlement(String ref, LocalDate date) {
        user("READ_CREDIT");
        Loan l = loan(ref);
        return settlement(l, policyOf(l), productRule(), date == null ? LocalDate.now() : date);
    }

    /**
     * {settlementReference, settlementDate}: closes the loan and its cover and raises the refund. The same reference
     * sent again returns the first result: one closure and at most one refund (AT-IL05-02).
     */
    @Transactional
    public CreditViews.LoanDetail settle(String ref, JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        Loan l = loan(ref);
        closeBySettlement(l, required(body, "settlementReference", 80),
                LocalDate.parse(required(body, "settlementDate", 10)), user);
        return detail(l);
    }

    private void closeBySettlement(Loan l, String reference, LocalDate date, AppUser user) {
        if (SETTLED.equals(l.status)) {
            if (reference.equals(l.settlementReference)) {
                return;
            }
            throw new ConflictException(l.ref + " was already settled on " + l.settledOn + " under "
                    + l.settlementReference + ".");
        }
        requireOpen(l);
        InsurancePolicy policy = policyOf(l);
        CreditViews.SettlementPreview p = settlement(l, policy, productRule(), date);
        if (p.blocked()) {
            throw new IllegalArgumentException(l.ref + " cannot be settled automatically: " + p.blockedBecause());
        }
        l.status = SETTLED;
        l.settledOn = date;
        l.settlementReference = reference;
        balanceRow(l, reference, date, Money.of(BigDecimal.ZERO), APPLIED, "Settled early", user.getUsername());
        l.balance = Money.of(BigDecimal.ZERO);
        l.balanceAsOf = date;
        if (policy != null && Set.of(PolicyWorkflow.ACTIVE, PolicyWorkflow.ACTIVE_NO_COVER,
                PolicyWorkflow.REINSTATEMENT_PENDING).contains(policy.getStatus())) {
            workflow.terminate(policy.getRef(), "LOAN_SETTLED", "Loan " + l.lenderLoanId + " settled early ("
                    + reference + ")", date, user.getUsername());
        }
        if (p.refund().signum() > 0) {
            Refund r = new Refund();
            r.ref = numbers.next("RFD-", 6, () -> 0);
            r.loanRef = l.ref;
            r.policyRef = l.policyRef;
            r.borrowerName = l.borrowerName;
            r.amount = p.refund();
            r.formula = p.formula();
            r.status = "DUE";
            r.createdAt = LocalDateTime.now();
            em.persist(r);
        }
        Map<String, Object> data = base(l);
        data.put("settledOn", date.toString());
        data.put("settlementReference", reference);
        data.put("refund", p.refund());
        outbox.publish(Events.LOAN_SETTLED, l.ref, data, user.getUsername());
        Map<String, Object> ended = base(l);
        ended.put("reason", "EARLY_SETTLEMENT");
        ended.put("endedOn", date.toString());
        outbox.publish(Events.CREDIT_COVER_TERMINATED, l.ref, ended, user.getUsername());
        audit.record(user.getUsername(), "Loan settled early", "Loan", l.ref, reference + " · refund "
                + Money.text(p.refund()));
    }

    /**
     * {newLenderLoanId, principal, termMonths, settlementReference, premiumBasis?}: settles this loan and registers
     * the new one, which keeps a link back so the lineage is not lost.
     */
    @Transactional
    public CreditViews.LoanView refinance(String ref, JsonNode body) {
        AppUser user = user("MANAGE_CREDIT");
        Loan old = loan(ref);
        closeBySettlement(old, required(body, "settlementReference", 80), LocalDate.now(), user);
        ObjectNode next = mapper.createObjectNode();
        next.put("lenderRef", old.lenderRef);
        next.put("lenderLoanId", required(body, "newLenderLoanId", 60));
        next.put("customerRef", old.customerRef);
        next.put("principal", required(body, "principal", 20));
        next.put("termMonths", required(body, "termMonths", 4));
        next.put("premiumBasis", optional(body, "premiumBasis") == null ? old.premiumBasis : optional(body, "premiumBasis"));
        next.put("secured", old.secured);
        next.put("pensioner", old.pensioner);
        next.put("temporaryDisability", old.temporaryDisability);
        next.put("criticalIllness", old.criticalIllness);
        next.put("permanentDisability", old.permanentDisability);
        next.put("refinancedFrom", old.ref);
        return view(register(next, user), productRule());
    }

    private CreditViews.SettlementPreview settlement(Loan l, InsurancePolicy policy, Rule rule, LocalDate date) {
        LocalDate coverStart = coverStart(l, policy);
        String blocked = null;
        if (l.disbursementDate == null) {
            blocked = "the loan has not been disbursed.";
        } else if (date.isBefore(l.disbursementDate)) {
            blocked = "the settlement date is before disbursement on " + l.disbursementDate + ".";
        } else if (date.isAfter(LocalDate.now())) {
            blocked = "the settlement date is in the future.";
        } else if (l.balanceException != null) {
            // NS-IL05-004: a stale or conflicting balance blocks automatic settlement.
            blocked = "a balance exception is open (" + l.balanceException + ").";
        }
        long elapsed = coverStart == null || date.isBefore(coverStart) ? 0 : ChronoUnit.MONTHS.between(coverStart, date);
        // A month of cover that has started is earned.
        if (coverStart != null && date.isAfter(coverStart.plusMonths(elapsed))) {
            elapsed++;
        }
        long remaining = Math.max(0, l.termMonths - elapsed);
        BigDecimal refund = Money.of(BigDecimal.ZERO);
        String formula;
        if ("SINGLE".equals(l.premiumBasis) && coverStart != null && l.singlePremium != null) {
            BigDecimal unearned = l.singlePremium.multiply(BigDecimal.valueOf(remaining))
                    .divide(BigDecimal.valueOf(l.termMonths), 2, RoundingMode.HALF_UP);
            refund = unearned.multiply(HUNDRED.subtract(rule.refundDeductionPercent)).divide(HUNDRED, 2,
                    RoundingMode.HALF_UP);
            formula = "Single premium " + Money.text(l.singlePremium) + " × " + remaining + " of " + l.termMonths
                    + " months unearned = " + Money.text(unearned) + ", less " + rule.refundDeductionPercent
                            .stripTrailingZeros().toPlainString() + "% = " + Money.text(refund) + ".";
        } else {
            formula = "Premiums are paid monthly for the month of cover given, so nothing is unearned.";
        }
        return new CreditViews.SettlementPreview(date, elapsed, remaining, refund, formula, blocked != null, blocked);
    }

    @Transactional(readOnly = true)
    public List<CreditViews.RefundView> refunds() {
        user("READ_CREDIT");
        return em.createQuery("select r from CreditRefund r order by r.id desc", Refund.class).getResultList().stream()
                .map(this::refundView).toList();
    }

    /** {paymentReference}: pays the refund; the subledger posts it from the event. */
    @Transactional
    public CreditViews.RefundView payRefund(String ref, JsonNode body) {
        AppUser user = user("PAY_CREDIT_REFUND");
        Refund r = em.createQuery("select r from CreditRefund r where r.ref = :ref", Refund.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Refund " + ref + " not found"));
        if (!"DUE".equals(r.status)) {
            throw new ConflictException(ref + " is already paid.");
        }
        r.status = "PAID";
        r.paidBy = user.getUsername();
        r.paidAt = LocalDateTime.now();
        r.paymentReference = required(body, "paymentReference", 60);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundRef", r.ref);
        data.put("loanRef", r.loanRef);
        data.put("policyRef", r.policyRef == null ? "" : r.policyRef);
        data.put("customerName", r.borrowerName == null ? "" : r.borrowerName);
        data.put("product", PRODUCT);
        data.put("amount", r.amount.toPlainString());
        data.put("paymentReference", r.paymentReference);
        outbox.publish(Events.PREMIUM_REFUNDED, r.ref, data, user.getUsername());
        if (r.policyRef != null) {
            InsurancePolicy policy = workflow.policy(r.policyRef);
            history.record(WorkflowHistory.POLICY, r.policyRef, policy.getCustomerName(), "Premium Refunded",
                    policy.getStatus(), policy.getStatus(), "Unearned premium of " + Money.text(r.amount) + " refunded ("
                            + r.ref + ", " + r.paymentReference + ")", user.getUsername());
        }
        return refundView(r);
    }

    // ── queries ───────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CreditViews.LoanView> loans() {
        user("READ_CREDIT");
        Rule rule = productRule();
        return em.createQuery("select l from Loan l order by l.id desc", Loan.class).getResultList().stream()
                .map(l -> view(l, rule)).toList();
    }

    @Transactional(readOnly = true)
    public CreditViews.LoanDetail loanDetail(String ref) {
        user("READ_CREDIT");
        return detail(loan(ref));
    }

    @Transactional(readOnly = true)
    public CreditViews.Summary summary() {
        user("READ_CREDIT");
        Rule rule = productRule();
        List<CreditViews.LoanView> all = em.createQuery("select l from Loan l", Loan.class).getResultList().stream()
                .map(l -> view(l, rule)).toList();
        return new CreditViews.Summary(all.size(),
                all.stream().filter(l -> "COVER_ACTIVE".equals(l.status())).count(),
                all.stream().filter(l -> Set.of("QUOTED", "AWAITING_PREMIUM", "AWAITING_DISBURSEMENT").contains(l.status()))
                        .count(),
                all.stream().filter(l -> l.balanceException() != null).count(),
                em.createQuery("select count(r) from CreditRefund r where r.status = 'DUE'", Long.class).getSingleResult(),
                all.stream().filter(l -> "COVER_ACTIVE".equals(l.status())).map(CreditViews.LoanView::balance)
                        .reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private CreditViews.LoanDetail detail(Loan l) {
        Rule rule = productRule();
        InsurancePolicy policy = policyOf(l);
        Refund refund = em.createQuery("select r from CreditRefund r where r.loanRef = :ref", Refund.class)
                .setParameter("ref", l.ref).getResultStream().findFirst().orElse(null);
        boolean open = OPEN.equals(l.status);
        return new CreditViews.LoanDetail(view(l, rule),
                em.createQuery("select b from LoanBalanceUpdate b where b.loanRef = :ref order by b.id desc",
                                BalanceUpdate.class)
                        .setParameter("ref", l.ref).getResultList().stream()
                        .map(b -> new CreditViews.BalanceView(b.id, b.sourceReference, b.effectiveDate, b.balance,
                                b.status, b.note, b.receivedBy, b.receivedAt))
                        .toList(),
                refund == null ? null : refundView(refund),
                open ? assess(l, policy, rule, LocalDate.now()) : null,
                open ? settlement(l, policy, rule, LocalDate.now()) : null);
    }

    /**
     * What the loan's cover is doing. Cover needs both the disbursement and the first premium (NS-IL05-002,
     * AT-IL05-01): either alone leaves the loan waiting for the other.
     */
    private String statusOf(Loan l, InsurancePolicy policy) {
        if (!OPEN.equals(l.status)) {
            return l.status;
        }
        String ps = policy == null ? "" : policy.getStatus();
        if (Set.of(PolicyWorkflow.CANCELLED, PolicyWorkflow.EXPIRED, PolicyWorkflow.LAPSED, PolicyWorkflow.TERMINATED)
                .contains(ps)) {
            return CANCELLED;
        }
        boolean premium = PolicyWorkflow.ACTIVE.equals(ps);
        boolean everPaid = premium || Set.of(PolicyWorkflow.ACTIVE_NO_COVER, PolicyWorkflow.REINSTATEMENT_PENDING)
                .contains(ps);
        if (l.disbursementDate == null) {
            return everPaid ? "AWAITING_DISBURSEMENT" : "QUOTED";
        }
        if (!everPaid) {
            return "AWAITING_PREMIUM";
        }
        return premium ? "COVER_ACTIVE" : "COVER_OFF";
    }

    /** Cover starts when both conditions have been met: the later of disbursement and the first premium. */
    private LocalDate coverStart(Loan l, InsurancePolicy policy) {
        if (l.disbursementDate == null || policy == null || policy.getCoverStartDate() == null) {
            return null;
        }
        return policy.getCoverStartDate().isAfter(l.disbursementDate) ? policy.getCoverStartDate()
                : l.disbursementDate;
    }

    private CreditViews.LoanView view(Loan l, Rule rule) {
        InsurancePolicy policy = policyOf(l);
        LocalDate start = coverStart(l, policy);
        boolean stale = OPEN.equals(l.status) && l.balanceAsOf != null
                && ChronoUnit.DAYS.between(l.balanceAsOf, LocalDate.now()) > rule.staleBalanceDays;
        Lender lender = em.createQuery("select x from Lender x where x.ref = :ref", Lender.class)
                .setParameter("ref", l.lenderRef).getResultStream().findFirst().orElse(null);
        return new CreditViews.LoanView(l.ref, l.lenderRef, lender == null ? l.lenderRef : lender.name, l.lenderLoanId,
                l.policyRef, policy == null ? null : policy.getStatus(), l.customerRef, l.borrowerName, l.principal,
                l.termMonths, l.secured, l.pensioner, l.premiumBasis, l.monthlyPremium, l.singlePremium,
                l.permanentDisability, l.temporaryDisability, l.criticalIllness, l.waitingMonths, l.disbursementDate,
                start, start == null ? null : start.plusMonths(l.termMonths), statusOf(l, policy), l.balance,
                l.balanceAsOf, stale, l.balanceException, l.settledOn, l.settlementReference, l.claimRef, l.lenderAmount,
                l.estateAmount, l.refinancedFrom, l.createdAt);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    /** The loan's policy, for the single-premium schedule fix after issue; null when the loan has none. */
    Loan loanForPolicy(String policyRef) {
        return em.createQuery("select l from Loan l where l.policyRef = :ref", Loan.class)
                .setParameter("ref", policyRef).getResultStream().findFirst().orElse(null);
    }

    private InsurancePolicy policyOf(Loan l) {
        return l.policyRef == null ? null
                : em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                        .setParameter("ref", l.policyRef).getResultStream().findFirst().orElse(null);
    }

    private void requireOpen(Loan l) {
        if (!OPEN.equals(l.status)) {
            throw new ConflictException(l.ref + " is " + l.status.toLowerCase(Locale.ROOT) + ".");
        }
    }

    private Map<String, Object> base(Loan l) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loanRef", l.ref);
        data.put("lenderRef", l.lenderRef);
        data.put("lenderLoanId", l.lenderLoanId);
        data.put("policyRef", l.policyRef == null ? "" : l.policyRef);
        data.put("customerName", l.borrowerName);
        return data;
    }

    private Rule productRule() {
        return em.createQuery("select r from CreditRule r where r.product = :p", Rule.class)
                .setParameter("p", PRODUCT).getResultStream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No credit rule is configured for " + PRODUCT));
    }

    private Lender lender(String ref) {
        return em.createQuery("select l from Lender l where l.ref = :ref", Lender.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Lender " + ref + " not found"));
    }

    private Loan loan(String ref) {
        return em.createQuery("select l from Loan l where l.ref = :ref", Loan.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Loan " + ref + " not found"));
    }

    private CreditViews.RuleView ruleView(Rule r) {
        return new CreditViews.RuleView(r.id, r.product, r.minEntryAge, r.maxExitAge, r.exceptionExitAge,
                r.minTermMonths, r.maxTermMonths, r.ratePerMille, r.waitingMonths, r.permanentDisability,
                r.temporaryDisabilityOffered, r.criticalIllnessOffered, r.coverBasis, r.staleBalanceDays,
                r.refundDeductionPercent, r.updatedBy, r.updatedAt);
    }

    private CreditViews.RefundView refundView(Refund r) {
        return new CreditViews.RefundView(r.ref, r.loanRef, r.policyRef, r.borrowerName, r.amount, r.formula, r.status,
                r.createdAt, r.paidBy, r.paidAt, r.paymentReference);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
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
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
