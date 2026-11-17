package com.nsure.servicing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.evidence.EvidenceChain;
import com.nsure.common.Money;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.CoverTier;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.InsuranceClaim;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.portfolio.PolicyLifeCover;
import com.nsure.portfolio.PolicyWorkflow;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.portfolio.WorkflowHistory;
import com.nsure.rating.RateTableService;
import com.nsure.reinsurance.Treaty;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import com.nsure.servicing.ServicingRecords.BenefitEvent;
import com.nsure.servicing.ServicingRecords.BenefitRule;
import com.nsure.servicing.ServicingRecords.Endorsement;
import com.nsure.servicing.ServicingRecords.LifeReview;
import com.nsure.servicing.ServicingRecords.ServicingRule;
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
 * IL03 policy administration and IL04 family administration (Nsure Life NS-IL03-002..006, NS-IL04-001..006).
 *
 * <p>An endorsement is previewed before it changes anything: old and new cover, the premium adjustment and the
 * commission and reinsurance effects. A material increase in risk goes to underwriting, a backdated or ownership
 * change needs a second person, and a backdated change that crosses a paid claim is held for impact review.
 * Contractual benefits (cashback, return of premium, maturity, surrender, paid-up) exist only where the product has
 * a rule for them; the benefit run raises each one when it falls due. Insured lives are dated: a removed life keeps
 * its history, and a life added again keeps its original waiting-period start.
 */
@Service
public class ServicingService {

    public static final String PAYER = "PAYER";
    public static final String OWNERSHIP = "OWNERSHIP";
    public static final String SUM_ASSURED = "SUM_ASSURED";
    public static final String FREQUENCY = "FREQUENCY";
    public static final String ADD_LIFE = "ADD_LIFE";
    public static final String REMOVE_LIFE = "REMOVE_LIFE";
    public static final String REPLACE_LIFE = "REPLACE_LIFE";
    static final List<String> TYPES = List.of(PAYER, OWNERSHIP, SUM_ASSURED, FREQUENCY, ADD_LIFE, REMOVE_LIFE,
            REPLACE_LIFE);

    static final String APPLIED = "APPLIED";
    static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    static final String REFERRED_UNDERWRITING = "REFERRED_UNDERWRITING";
    static final String IMPACT_REVIEW = "IMPACT_REVIEW";
    static final String REJECTED = "REJECTED";
    private static final Set<String> HELD = Set.of(PENDING_APPROVAL, REFERRED_UNDERWRITING, IMPACT_REVIEW);

    static final String CASHBACK = "CASHBACK";
    static final String RETURN_OF_PREMIUM = "RETURN_OF_PREMIUM";
    static final String MATURITY = "MATURITY";
    static final String SURRENDER = "SURRENDER";
    static final String PAID_UP = "PAID_UP";
    static final Set<String> BENEFIT_TYPES = Set.of(CASHBACK, RETURN_OF_PREMIUM, MATURITY, SURRENDER, PAID_UP);

    static final String DUE = "DUE";
    static final String APPROVED = "APPROVED";
    static final String DECLINED = "DECLINED";
    static final String PAID = "PAID";

    private static final Map<String, Integer> FREQUENCY_MONTHS = Map.of("MONTHLY", 1, "QUARTERLY", 3,
            "SEMI_ANNUAL", 6, "ANNUAL", 12);
    private static final Set<String> IN_FORCE = Set.of(PolicyWorkflow.ACTIVE, PolicyWorkflow.ACTIVE_NO_COVER,
            PolicyWorkflow.REINSTATEMENT_PENDING);
    /** Causes a reasoned termination may give (IL03 records and workspace). */
    static final List<String> TERMINATION_CAUSES = List.of("DEATH", "CANCELLATION", "EXPIRY", "MATURITY", "OTHER");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int REVIEW_LEAD_DAYS = 90;

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final EvidenceChain audit;
    private final WorkflowHistory history;
    private final PolicyWorkflow workflow;
    private final RateTableService rates;
    private final ObjectMapper mapper;

    public ServicingService(EntityManager em, PlatformSecurityContext security, Outbox outbox,
            ReferenceNumbers numbers, EvidenceChain audit, WorkflowHistory history, PolicyWorkflow workflow,
            RateTableService rates, ObjectMapper mapper) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.audit = audit;
        this.history = history;
        this.workflow = workflow;
        this.rates = rates;
        this.mapper = mapper;
    }

    // ── the servicing view ────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ServicingViews.ServicingView view(String policyRef) {
        user("READ_SERVICING");
        InsurancePolicy policy = workflow.policy(policyRef);
        List<BenefitRule> rules = benefitRules(policy.getProduct());
        List<String> offered = rules.stream().map(r -> r.benefitType).sorted().toList();
        return new ServicingViews.ServicingView(
                policyView(policy),
                lives(policy).stream().map(this::lifeView).toList(),
                em.createQuery("select e from Endorsement e where e.policyRef = :ref order by e.id desc", Endorsement.class)
                        .setParameter("ref", policyRef).getResultList().stream().map(e -> endorsementView(e, policy))
                        .toList(),
                offered,
                offered.contains(SURRENDER),
                offered.contains(PAID_UP),
                schedule(policy, rules),
                em.createQuery("select b from BenefitEvent b where b.policyRef = :ref order by b.dueDate desc",
                                BenefitEvent.class)
                        .setParameter("ref", policyRef).getResultList().stream().map(this::benefitView).toList(),
                em.createQuery("select r from LifeReview r where r.policyRef = :ref order by r.dueDate", LifeReview.class)
                        .setParameter("ref", policyRef).getResultList().stream().map(this::reviewView).toList(),
                TYPES);
    }

    @Transactional(readOnly = true)
    public ServicingViews.Summary summary() {
        user("READ_SERVICING");
        return new ServicingViews.Summary(
                em.createQuery("select count(e) from Endorsement e where e.status in :held", Long.class)
                        .setParameter("held", HELD).getSingleResult(),
                em.createQuery("select count(p) from InsurancePolicy p where p.status = :s", Long.class)
                        .setParameter("s", PolicyWorkflow.REINSTATEMENT_PENDING).getSingleResult(),
                count("select count(b) from BenefitEvent b where b.status = 'DUE'"),
                count("select count(b) from BenefitEvent b where b.status = 'APPROVED'"),
                count("select count(r) from LifeReview r where r.status = 'OPEN'"));
    }

    // ── endorsements (NS-IL03-002/003) ────────────────────────────────────────────────────────────

    /** What the endorsement would do. Nothing is stored or changed. */
    @Transactional(readOnly = true)
    public ServicingViews.Preview preview(String policyRef, JsonNode body) {
        AppUser user = user("MANAGE_SERVICING");
        return plan(workflow.policy(policyRef), body, user).preview;
    }

    /** Requests the endorsement: applied at once, or held for the review its preview named. */
    @Transactional
    public ServicingViews.EndorsementView request(String policyRef, JsonNode body) {
        AppUser user = user("MANAGE_SERVICING");
        InsurancePolicy policy = workflow.policy(policyRef);
        em.lock(policy, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        Plan plan = plan(policy, body, user);
        String reason = required(body, "reason", 500);
        if (reason.length() < 5) {
            throw new IllegalArgumentException("Give the reason for the change.");
        }
        ServicingViews.Preview p = plan.preview;
        Endorsement e = new Endorsement();
        e.ref = numbers.next("END-", 6, () -> 0);
        e.policyRef = policyRef;
        e.type = p.type();
        e.effectiveDate = p.effectiveDate();
        e.backdated = p.backdated();
        e.reason = reason;
        e.changesJson = plan.changes.toString();
        e.beforeSummary = p.before();
        e.afterSummary = p.after();
        e.oldSumAssured = p.oldSumAssured();
        e.newSumAssured = p.newSumAssured();
        e.oldPremium = p.oldPremium();
        e.newPremium = p.newPremium();
        e.premiumAdjustment = p.premiumAdjustment();
        e.commissionEffect = p.commissionEffect();
        e.reinsuranceEffect = p.reinsuranceEffect();
        e.holdReason = p.holdReason();
        e.requestedBy = user.getUsername();
        e.requestedAt = LocalDateTime.now();
        e.status = switch (p.route()) {
            case "APPLY" -> APPLIED;
            case "UNDERWRITING" -> REFERRED_UNDERWRITING;
            case "IMPACT_REVIEW" -> IMPACT_REVIEW;
            default -> PENDING_APPROVAL;
        };
        em.persist(e);
        if (APPLIED.equals(e.status)) {
            apply(policy, e, plan.changes, user.getUsername());
        } else {
            audit.record(user.getUsername(), "Endorsement held: " + e.status, "Policy", policyRef,
                    e.ref + " " + e.type + ": " + e.holdReason);
        }
        return endorsementView(e, policy);
    }

    @Transactional(readOnly = true)
    public List<ServicingViews.EndorsementView> endorsements(String status) {
        user("READ_SERVICING");
        Map<String, InsurancePolicy> policies = new LinkedHashMap<>();
        return em.createQuery("select e from Endorsement e order by e.id desc", Endorsement.class).getResultList()
                .stream()
                .filter(e -> status == null || status.isBlank() || ("HELD".equalsIgnoreCase(status)
                        ? HELD.contains(e.status) : status.equalsIgnoreCase(e.status)))
                .map(e -> endorsementView(e, policies.computeIfAbsent(e.policyRef, workflow::policy)))
                .toList();
    }

    /** {approve true|false, note}: decides a held endorsement. Nobody decides their own request. */
    @Transactional
    public ServicingViews.EndorsementView decide(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        Endorsement e = em.createQuery("select e from Endorsement e where e.ref = :ref", Endorsement.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Endorsement " + ref + " not found"));
        if (!HELD.contains(e.status)) {
            throw new ConflictException(ref + " is already " + e.status.toLowerCase(Locale.ROOT).replace('_', ' ') + ".");
        }
        // NS-IL03-003: a material increase in risk is decided by underwriting; everything else by an approver.
        user.validateHasPermissionTo(REFERRED_UNDERWRITING.equals(e.status) ? "UNDERWRITE_APPLICATION"
                : "APPROVE_ENDORSEMENT");
        if (user.getUsername().equalsIgnoreCase(e.requestedBy)) {
            throw new NoAuthorizationException("You requested " + ref + ", so someone else must decide it.");
        }
        String note = required(body, "note", 500);
        if (note.length() < 5) {
            throw new IllegalArgumentException("Give a note for the decision.");
        }
        InsurancePolicy policy = workflow.policy(e.policyRef);
        em.lock(policy, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        e.decidedBy = user.getUsername();
        e.decidedAt = LocalDateTime.now();
        e.decisionNote = note;
        if (body.path("approve").asBoolean(false)) {
            JsonNode changes;
            try {
                changes = mapper.readTree(e.changesJson);
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalStateException("Stored endorsement " + ref + " is unreadable", ex);
            }
            // The policy may have changed since the request: plan it again against what is true now.
            Plan plan = plan(policy, changes, null);
            e.newPremium = plan.preview.newPremium();
            e.newSumAssured = plan.preview.newSumAssured();
            e.afterSummary = plan.preview.after();
            e.status = APPLIED;
            apply(policy, e, changes, user.getUsername());
        } else {
            e.status = REJECTED;
            audit.record(user.getUsername(), "Endorsement rejected", "Policy", e.policyRef, ref + ": " + note);
        }
        return endorsementView(e, policy);
    }

    private record Plan(ServicingViews.Preview preview, JsonNode changes) {
    }

    /** Validates the request and works out its effect. {@code requester} is null when re-planning at approval. */
    private Plan plan(InsurancePolicy policy, JsonNode body, AppUser requester) {
        if (!IN_FORCE.contains(policy.getStatus())) {
            throw new IllegalArgumentException("Policy " + policy.getRef() + " is " + policy.getStatus()
                    + "; only an in-force policy can be endorsed.");
        }
        String type = required(body, "type", 30).toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("type must be one of " + String.join(", ", TYPES) + ".");
        }
        LocalDate today = LocalDate.now();
        LocalDate effective = optional(body, "effectiveDate") == null ? today : LocalDate.parse(optional(body, "effectiveDate"));
        boolean backdated = effective.isBefore(today);
        ServicingRule rule = servicingRule(policy.getProduct());
        if (requester != null) {
            if (effective.isAfter(today.plusYears(1))) {
                throw new IllegalArgumentException("The effective date cannot be more than a year ahead.");
            }
            if (policy.getCoverStartDate() != null && effective.isBefore(policy.getCoverStartDate())) {
                throw new IllegalArgumentException("The effective date cannot be before cover started on "
                        + policy.getCoverStartDate() + ".");
            }
            if (backdated) {
                // NS-IL03-003: backdating needs explicit authority, and only as far back as the product allows.
                if (!requester.hasPermissionTo("BACKDATE_ENDORSEMENT")) {
                    throw new NoAuthorizationException("Backdating an endorsement needs the BACKDATE_ENDORSEMENT"
                            + " authority.");
                }
                if (effective.isBefore(today.minusDays(rule.maxBackdateDays))) {
                    throw new IllegalArgumentException(policy.getProduct() + " endorsements can be backdated at most "
                            + rule.maxBackdateDays + " days.");
                }
            }
        }

        BigDecimal oldSa = policy.getAmount();
        BigDecimal oldPremium = policy.getPremium();
        BigDecimal newSa = oldSa;
        BigDecimal newPremium = oldPremium;
        String before;
        String after;
        boolean riskIncrease = false;
        com.fasterxml.jackson.databind.node.ObjectNode changes = mapper.createObjectNode();
        changes.put("type", type);
        changes.put("effectiveDate", effective.toString());
        switch (type) {
            case PAYER -> {
                String name = required(body, "payerName", 100);
                String relationship = required(body, "payerRelationship", 60);
                before = "Payer: " + (policy.getPayerName() == null ? "the policyholder"
                        : policy.getPayerName() + " (" + policy.getPayerRelationship() + ")");
                after = "Payer: " + name + " (" + relationship + ")";
                changes.put("payerName", name).put("payerRelationship", relationship);
            }
            case OWNERSHIP -> {
                CustomerAccount owner = customer(required(body, "newCustomerRef", 40));
                if (owner.getRef().equals(policy.getCustomerRef())) {
                    throw new IllegalArgumentException(owner.getName() + " already owns this policy.");
                }
                if (!"VERIFIED".equals(owner.getKycStatus())) {
                    throw new IllegalArgumentException(owner.getName() + " must be KYC verified before owning a policy.");
                }
                before = "Owner: " + policy.getCustomerName() + " (" + policy.getCustomerRef() + ")";
                after = "Owner: " + owner.getName() + " (" + owner.getRef() + ")";
                changes.put("newCustomerRef", owner.getRef());
            }
            case SUM_ASSURED -> {
                String tierName = optional(body, "tier");
                if (tierName != null) {
                    CoverTier tier = tier(policy.getProduct(), tierName);
                    newSa = Money.of(BigDecimal.valueOf(tier.getCoverAmount()));
                    // The other lives keep their premiums; the plan's own premium moves with the tier.
                    BigDecimal otherLives = lives(policy).stream()
                            .filter(l -> l.isActiveLife() && !l.isMainInsured())
                            .map(PolicyLifeCover::getMonthlyPremium).reduce(BigDecimal.ZERO, BigDecimal::add);
                    // A plan priced at its flat premium moves to the new plan's premium; a rated or loaded premium
                    // moves in proportion to the cover, so its loading is kept.
                    BigDecimal main = oldPremium.subtract(otherLives);
                    boolean flat = policy.getTier() != null && em.createQuery("select t from CoverTier t where"
                                    + " t.product.name = :product and t.name = :tier", CoverTier.class)
                            .setParameter("product", policy.getProduct()).setParameter("tier", policy.getTier())
                            .getResultStream().findFirst()
                            .map(t -> main.compareTo(Money.of(BigDecimal.valueOf(t.getPremium()))) == 0).orElse(false);
                    newPremium = (flat || oldSa.signum() == 0 ? Money.of(BigDecimal.valueOf(tier.getPremium()))
                            : main.multiply(newSa).divide(oldSa, 2, RoundingMode.HALF_UP)).add(otherLives);
                    changes.put("tier", tier.getName());
                    before = "Plan " + (policy.getTier() == null ? "—" : policy.getTier()) + ", sum assured "
                            + Money.text(oldSa);
                    after = "Plan " + tier.getName() + ", sum assured " + Money.text(newSa);
                } else {
                    newSa = Money.of(new BigDecimal(required(body, "amount", 20)));
                    if (newSa.signum() <= 0) {
                        throw new IllegalArgumentException("The sum assured must be above zero.");
                    }
                    newPremium = oldSa.signum() == 0 ? oldPremium
                            : oldPremium.multiply(newSa).divide(oldSa, 2, RoundingMode.HALF_UP);
                    changes.put("amount", newSa.toPlainString());
                    before = "Sum assured " + Money.text(oldSa);
                    after = "Sum assured " + Money.text(newSa);
                }
                if (newSa.compareTo(oldSa) == 0) {
                    throw new IllegalArgumentException("That is already the policy's sum assured.");
                }
                riskIncrease = newSa.compareTo(oldSa) > 0;
            }
            case FREQUENCY -> {
                String frequency = required(body, "frequency", 20).toUpperCase(Locale.ROOT);
                if (!FREQUENCY_MONTHS.containsKey(frequency)) {
                    throw new IllegalArgumentException("frequency must be MONTHLY, QUARTERLY, SEMI_ANNUAL or ANNUAL.");
                }
                if (frequency.equals(policy.getPremiumFrequency())) {
                    throw new IllegalArgumentException("The policy is already paid " + label(frequency) + ".");
                }
                before = "Premium paid " + label(policy.getPremiumFrequency()) + ": "
                        + Money.text(modal(oldPremium, policy.getPremiumFrequency()));
                after = "Premium paid " + label(frequency) + ": " + Money.text(modal(oldPremium, frequency));
                changes.put("frequency", frequency);
            }
            case ADD_LIFE -> {
                RateTableService.PricedLife line = priceNewLife(policy, body, null, effective);
                newPremium = oldPremium.add(line.monthlyPremium());
                before = activeLives(policy) + " insured lives, premium " + Money.text(oldPremium);
                after = "Adds " + line.fullName() + " (" + line.relationship().getRelationshipName() + ", age "
                        + line.age() + ") with cover " + Money.text(line.coverAmount()) + " for "
                        + Money.text(line.monthlyPremium()) + " a month";
                copyLife(changes, body);
                riskIncrease = true;
            }
            case REMOVE_LIFE -> {
                PolicyLifeCover life = life(policy, body.path("lifeId").asLong(0));
                newPremium = oldPremium.subtract(life.getMonthlyPremium()).max(BigDecimal.ZERO);
                before = life.getFullName() + " (" + life.getLifeType() + ") covered for "
                        + Money.text(life.getCoverAmount());
                after = life.getFullName() + "'s cover ceases on " + effective + "; claims and limits already used stay"
                        + " on the record";
                changes.put("lifeId", life.getId());
            }
            default -> {
                PolicyLifeCover life = life(policy, body.path("lifeId").asLong(0));
                RateTableService.PricedLife line = priceNewLife(policy, body, life, effective);
                newPremium = oldPremium.subtract(life.getMonthlyPremium()).add(line.monthlyPremium());
                before = life.getFullName() + " (" + life.getLifeType() + ") covered for "
                        + Money.text(life.getCoverAmount());
                after = "Replaced by " + line.fullName() + " (" + line.relationship().getRelationshipName() + ", age "
                        + line.age() + "); waiting periods for the new life start on " + effective;
                changes.put("lifeId", life.getId());
                copyLife(changes, body);
                riskIncrease = true;
            }
        }
        newPremium = Money.of(newPremium);

        // AT-IL03-01: a backdated change across a paid claim is not applied until its impact has been reviewed.
        String route = "APPLY";
        String hold = null;
        Optional<InsuranceClaim> crossed = backdated ? claimPaidSince(policy.getRef(), effective) : Optional.empty();
        if (crossed.isPresent()) {
            route = "IMPACT_REVIEW";
            hold = "Backdated to " + effective + ", before claim " + crossed.get().getRef()
                    + " was paid: the effect on that claim must be reviewed first.";
        } else if (riskIncrease) {
            route = "UNDERWRITING";
            hold = "A material increase in risk is decided by underwriting.";
        } else if (backdated || OWNERSHIP.equals(type)) {
            route = "APPROVAL";
            hold = backdated ? "A backdated change needs a second person's approval."
                    : "A change of ownership needs a second person's approval.";
        }

        BigDecimal premiumChange = newPremium.subtract(oldPremium);
        ServicingViews.Preview preview = new ServicingViews.Preview(type, effective, backdated, before, after, oldSa,
                newSa, oldPremium, newPremium, proRata(policy, premiumChange, effective),
                commissionEffect(policy.getProduct(), premiumChange), reinsuranceEffect(policy.getProduct(), oldSa, newSa,
                        effective),
                route, hold, premiumChange.signum() == 0 && newSa.compareTo(oldSa) == 0
                        ? List.of("Endorsement letter") : List.of("Endorsement letter", "Revised policy schedule"));
        return new Plan(preview, changes);
    }

    /** Makes the change, records it on the policy's history and publishes PolicyChanged. */
    private void apply(InsurancePolicy policy, Endorsement e, JsonNode changes, String actor) {
        switch (e.type) {
            case PAYER -> policy.setPayer(changes.path("payerName").asText(), changes.path("payerRelationship").asText());
            case OWNERSHIP -> {
                CustomerAccount owner = customer(changes.path("newCustomerRef").asText());
                policy.setCustomerRef(owner.getRef());
                policy.setCustomerName(owner.getName());
            }
            case SUM_ASSURED -> {
                policy.setAmount(e.newSumAssured);
                policy.setPremium(e.newPremium);
                if (changes.hasNonNull("tier")) {
                    policy.setTier(changes.get("tier").asText());
                }
            }
            case FREQUENCY -> policy.setPremiumFrequency(changes.path("frequency").asText());
            case ADD_LIFE -> {
                addLife(policy, changes, null, e.effectiveDate, actor);
                policy.setPremium(e.newPremium);
            }
            case REMOVE_LIFE -> {
                ceaseLife(policy, life(policy, changes.path("lifeId").asLong()), e.effectiveDate, e.reason, actor);
                policy.setPremium(e.newPremium);
            }
            default -> {
                PolicyLifeCover old = life(policy, changes.path("lifeId").asLong());
                addLife(policy, changes, old, e.effectiveDate, actor);
                ceaseLife(policy, old, e.effectiveDate, "Replaced: " + e.reason, actor);
                policy.setPremium(e.newPremium);
            }
        }
        e.appliedAt = LocalDateTime.now();
        history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Endorsed",
                policy.getStatus(), policy.getStatus(), e.ref + " · " + e.afterSummary
                        + (e.newPremium.compareTo(e.oldPremium) == 0 ? ""
                                : " · premium " + Money.text(e.oldPremium) + " to " + Money.text(e.newPremium)),
                actor, e.effectiveDate);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("customerName", policy.getCustomerName());
        data.put("product", policy.getProduct());
        data.put("endorsementRef", e.ref);
        data.put("endorsementType", e.type);
        data.put("effectiveDate", e.effectiveDate.toString());
        data.put("before", e.beforeSummary);
        data.put("after", e.afterSummary);
        data.put("oldPremium", e.oldPremium);
        data.put("newPremium", e.newPremium);
        data.put("oldSumAssured", e.oldSumAssured);
        data.put("newSumAssured", e.newSumAssured);
        outbox.publish(Events.POLICY_CHANGED, policy.getRef(), data, actor);
        audit.record(actor, "Endorsement applied", "Policy", policy.getRef(), e.ref + " " + e.type);
    }

    // ── insured lives (NS-IL04-001..005) ──────────────────────────────────────────────────────────

    /** Prices the life to add beside the lives that stay, which also checks relationship, age and count limits. */
    private RateTableService.PricedLife priceNewLife(InsurancePolicy policy, JsonNode body, PolicyLifeCover replacing,
            LocalDate onDate) {
        if (policy.getTier() == null) {
            throw new IllegalArgumentException("Policy " + policy.getRef() + " has no plan, so lives cannot be added.");
        }
        String name = required(body, "fullName", 150);
        String relationship = required(body, "relationship", 60);
        LocalDate dob = LocalDate.parse(required(body, "dateOfBirth", 10));
        List<RateTableService.LifeToPrice> all = new ArrayList<>();
        for (PolicyLifeCover l : lives(policy)) {
            if (l.isActiveLife() && (replacing == null || !l.getId().equals(replacing.getId()))) {
                if (l.getFullName().equalsIgnoreCase(name) && dob.equals(l.getDateOfBirth())) {
                    throw new IllegalArgumentException(name + " is already covered on this policy.");
                }
                all.add(new RateTableService.LifeToPrice(l.getLifeType(), l.getFullName(), l.getDateOfBirth()));
            }
        }
        all.add(new RateTableService.LifeToPrice(relationship, name, dob));
        RateTableService.PolicyPrice price = rates.pricePolicy(tier(policy.getProduct(), policy.getTier()), all, onDate)
                .orElseThrow(() -> new IllegalArgumentException("The " + policy.getTier() + " plan has a flat premium and"
                        + " covers the main insured only, so lives cannot be added to it."));
        return price.lines().get(price.lines().size() - 1);
    }

    private void addLife(InsurancePolicy policy, JsonNode changes, PolicyLifeCover replacing, LocalDate effective,
            String actor) {
        RateTableService.PricedLife line = priceNewLife(policy, changes, replacing, effective);
        // NS-IL04-003, AT-IL04-02: a life that was covered here before keeps its original waiting-period start.
        LocalDate waitingFrom = lives(policy).stream()
                .filter(l -> !l.isActiveLife() && l.getFullName().equalsIgnoreCase(line.fullName())
                        && line.dateOfBirth().equals(l.getDateOfBirth()))
                .map(l -> l.getWaitingPeriodStart() != null ? l.getWaitingPeriodStart()
                        : l.getEffectiveFrom() != null ? l.getEffectiveFrom() : policy.getCoverStartDate())
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo).orElse(effective);
        int order = lives(policy).stream().mapToInt(PolicyLifeCover::getDisplayOrder).max().orElse(0) + 1;
        PolicyLifeCover life = new PolicyLifeCover(policy, line, order, effective, waitingFrom);
        em.persist(life);
        em.flush();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("lifeId", life.getId());
        data.put("fullName", line.fullName());
        data.put("relationship", line.relationship().getRelationshipName());
        data.put("effectiveFrom", effective.toString());
        data.put("waitingPeriodStart", waitingFrom.toString());
        data.put("coverAmount", line.coverAmount());
        outbox.publish(Events.INSURED_LIFE_ADDED, policy.getRef(), data, actor);
    }

    private void ceaseLife(InsurancePolicy policy, PolicyLifeCover life, LocalDate on, String reason, String actor) {
        ceaseLife(policy, life, on, reason, actor, null);
    }

    private void ceaseLife(InsurancePolicy policy, PolicyLifeCover life, LocalDate on, String reason, String actor,
            String correlationId) {
        life.cease(on, clip(reason, 300));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("lifeId", life.getId());
        data.put("fullName", life.getFullName());
        data.put("ceasedOn", on.toString());
        data.put("reason", reason);
        outbox.publish(Events.INSURED_LIFE_CEASED, policy.getRef(), data, actor, correlationId);
    }

    // ── insured lives following the policy (IL04 events) ──────────────────────────────────────────

    /**
     * On PolicyIssued: the lives captured at application are insured from issue, so each is announced with
     * InsuredLifeAdded (source ISSUE). A life already announced is skipped, so a replayed event adds nothing.
     */
    @Transactional
    public void announceLivesAtIssue(String policyRef, String correlationId) {
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", policyRef).getResultStream().findFirst().orElse(null);
        if (policy == null) {
            return;
        }
        for (PolicyLifeCover life : lives(policy)) {
            if (!life.isActiveLife() || announced(policyRef, life.getId())) {
                continue;
            }
            LocalDate from = life.getEffectiveFrom() != null ? life.getEffectiveFrom() : policy.getCoverStartDate();
            LocalDate waiting = life.getWaitingPeriodStart() != null ? life.getWaitingPeriodStart() : from;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("policyRef", policyRef);
            data.put("lifeId", life.getId());
            data.put("fullName", life.getFullName());
            data.put("relationship", life.getLifeType());
            data.put("effectiveFrom", from == null ? null : from.toString());
            data.put("waitingPeriodStart", waiting == null ? null : waiting.toString());
            data.put("coverAmount", life.getCoverAmount());
            data.put("source", "ISSUE");
            outbox.publish(Events.INSURED_LIFE_ADDED, policyRef, data, WorkflowHistory.SYSTEM, correlationId);
        }
    }

    private boolean announced(String policyRef, Long lifeId) {
        return em.createQuery("select count(e) from OutboxEvent e where e.eventType = :type and e.aggregateRef = :ref"
                        + " and e.payload like :life", Long.class)
                .setParameter("type", Events.INSURED_LIFE_ADDED).setParameter("ref", policyRef)
                .setParameter("life", "%\"lifeId\":" + lifeId + ",%").getSingleResult() > 0;
    }

    /**
     * On PolicyTerminated (termination, cancellation, lapse or a claim that ends the policy): the lives still covered
     * cease with it, each with InsuredLifeCeased. Lives already ceased are left alone, so a replay changes nothing.
     */
    @Transactional
    public void ceaseLivesWithPolicy(String policyRef, String cause, String endedOn, String correlationId) {
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", policyRef).getResultStream().findFirst().orElse(null);
        if (policy == null || IN_FORCE.contains(policy.getStatus())
                || PolicyWorkflow.PENDING_PAYMENT.equals(policy.getStatus())) {
            return;
        }
        LocalDate on = policy.getTerminatedOn() != null ? policy.getTerminatedOn()
                : endedOn != null && !"null".equals(endedOn) ? LocalDate.parse(endedOn) : LocalDate.now();
        String reason = "Policy ended: " + (cause == null ? policy.getStatus()
                : cause.charAt(0) + cause.substring(1).toLowerCase(Locale.ROOT).replace('_', ' '));
        for (PolicyLifeCover life : lives(policy)) {
            if (life.isActiveLife()) {
                ceaseLife(policy, life, on, reason, WorkflowHistory.SYSTEM, correlationId);
            }
        }
    }

    /** Raises a review task for every active life that reaches an age limit within the next 90 days. */
    @Transactional
    public ServicingViews.RunResult runLifeReviews() {
        AppUser user = user("MANAGE_SERVICING");
        LocalDate horizon = LocalDate.now().plusDays(REVIEW_LEAD_DAYS);
        List<String> created = new ArrayList<>();
        for (PolicyLifeCover life : em.createQuery("select l from PolicyLifeCover l where l.policy is not null"
                + " and l.lifeStatus = 'ACTIVE'", PolicyLifeCover.class).getResultList()) {
            InsurancePolicy policy = life.getPolicy();
            if (!IN_FORCE.contains(policy.getStatus()) || life.getDateOfBirth() == null) {
                continue;
            }
            ServicingRule rule = servicingRule(policy.getProduct());
            boolean child = life.getLifeType() != null && life.getLifeType().toLowerCase(Locale.ROOT).contains("child");
            if (child) {
                raiseReview(policy, life, "CHILD_AGE_LIMIT", life.getDateOfBirth().plusYears(rule.childReviewAge),
                        horizon, "Reaches " + rule.childReviewAge + ": confirm student or dependency status, or cease"
                                + " cover.", created, user.getUsername());
            }
            raiseReview(policy, life, "AGE_CESSATION", life.getDateOfBirth().plusYears(rule.maxCoverAge), horizon,
                    "Reaches the cover cessation age of " + rule.maxCoverAge + ".", created, user.getUsername());
        }
        return new ServicingViews.RunResult(created.size(), created);
    }

    private void raiseReview(InsurancePolicy policy, PolicyLifeCover life, String type, LocalDate due, LocalDate horizon,
            String note, List<String> created, String actor) {
        if (due.isAfter(horizon) || count("select count(r) from LifeReview r where r.lifeId = " + life.getId()
                + " and r.reviewType = '" + type + "'") > 0) {
            return;
        }
        LifeReview r = new LifeReview();
        r.policyRef = policy.getRef();
        r.lifeId = life.getId();
        r.lifeName = life.getFullName();
        r.reviewType = type;
        r.dueDate = due;
        r.status = "OPEN";
        r.note = note;
        r.createdAt = LocalDateTime.now();
        em.persist(r);
        created.add(policy.getRef() + " · " + life.getFullName());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policy.getRef());
        data.put("lifeId", life.getId());
        data.put("fullName", life.getFullName());
        data.put("reviewType", type);
        data.put("dueDate", due.toString());
        outbox.publish(Events.DEPENDANT_ELIGIBILITY_CHANGED, policy.getRef(), data, actor);
    }

    @Transactional(readOnly = true)
    public List<ServicingViews.LifeReviewView> lifeReviews() {
        user("READ_SERVICING");
        return em.createQuery("select r from LifeReview r order by r.status desc, r.dueDate", LifeReview.class)
                .getResultList().stream().map(this::reviewView).toList();
    }

    /** {outcome CONTINUE|CEASE, note}: closes a review; CEASE ends the life's cover from the due date or today. */
    @Transactional
    public ServicingViews.LifeReviewView closeReview(Long id, JsonNode body) {
        AppUser user = user("MANAGE_SERVICING");
        LifeReview r = em.find(LifeReview.class, id);
        if (r == null) {
            throw new NotFoundException("Review not found");
        }
        if (!"OPEN".equals(r.status)) {
            throw new ConflictException("This review is already closed.");
        }
        String outcome = required(body, "outcome", 20).toUpperCase(Locale.ROOT);
        String note = required(body, "note", 400);
        if (!Set.of("CONTINUE", "CEASE").contains(outcome)) {
            throw new IllegalArgumentException("Choose the outcome: continue cover or cease it.");
        }
        if ("CEASE".equals(outcome)) {
            InsurancePolicy policy = workflow.policy(r.policyRef);
            PolicyLifeCover life = em.find(PolicyLifeCover.class, r.lifeId);
            if (life != null && life.isActiveLife()) {
                if (life.isMainInsured()) {
                    throw new IllegalArgumentException("The main insured's cover ends by terminating the policy.");
                }
                LocalDate on = r.dueDate.isAfter(LocalDate.now()) ? r.dueDate : LocalDate.now();
                ceaseLife(policy, life, on, "Age review: " + note, user.getUsername());
                policy.setPremium(Money.of(policy.getPremium().subtract(life.getMonthlyPremium()).max(BigDecimal.ZERO)));
                history.record(WorkflowHistory.POLICY, policy.getRef(), policy.getCustomerName(), "Life Ceased",
                        policy.getStatus(), policy.getStatus(), life.getFullName() + " ceased after age review: " + note,
                        user.getUsername(), on);
            }
        }
        r.status = "CEASE".equals(outcome) ? "CEASED" : "CONTINUED";
        r.note = clip(r.note + " Outcome: " + note, 500);
        r.closedBy = user.getUsername();
        r.closedAt = LocalDateTime.now();
        // IL04: the review's outcome changes (or confirms) the dependant's eligibility.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", r.policyRef);
        data.put("lifeId", r.lifeId);
        data.put("fullName", r.lifeName);
        data.put("reviewType", r.reviewType);
        data.put("dueDate", r.dueDate.toString());
        data.put("outcome", r.status);
        outbox.publish(Events.DEPENDANT_ELIGIBILITY_CHANGED, r.policyRef, data, user.getUsername());
        return reviewView(r);
    }

    // ── contractual benefits (NS-IL03-005) ────────────────────────────────────────────────────────

    /** Raises every benefit that has fallen due by today. Running it again raises nothing twice. */
    @Transactional
    public ServicingViews.RunResult runBenefits() {
        AppUser user = user("MANAGE_SERVICING");
        LocalDate today = LocalDate.now();
        List<String> created = new ArrayList<>();
        for (InsurancePolicy policy : em.createQuery("select p from InsurancePolicy p where p.status = :s"
                + " and p.coverStartDate is not null", InsurancePolicy.class)
                .setParameter("s", PolicyWorkflow.ACTIVE).getResultList()) {
            for (ServicingViews.ScheduledBenefit due : dueBenefits(policy, benefitRules(policy.getProduct()), today)) {
                if (em.createQuery("select count(b) from BenefitEvent b where b.policyRef = :ref and b.benefitType = :type"
                                + " and b.dueDate = :due", Long.class)
                        .setParameter("ref", policy.getRef()).setParameter("type", due.benefitType())
                        .setParameter("due", due.dueDate()).getSingleResult() > 0) {
                    continue;
                }
                BenefitEvent b = benefitEvent(policy, due.benefitType(), due.dueDate(), due.amount(), due.basis());
                created.add(b.ref);
                publishDue(b, user.getUsername());
            }
        }
        return new ServicingViews.RunResult(created.size(), created);
    }

    @Transactional(readOnly = true)
    public List<ServicingViews.BenefitEventView> benefitEvents(String status) {
        user("READ_SERVICING");
        return em.createQuery("select b from BenefitEvent b order by b.id desc", BenefitEvent.class).getResultList()
                .stream().filter(b -> status == null || status.isBlank() || status.equalsIgnoreCase(b.status))
                .map(this::benefitView).toList();
    }

    /** {approve true|false, note}: assesses a benefit that has fallen due. */
    @Transactional
    public ServicingViews.BenefitEventView decideBenefit(String ref, JsonNode body) {
        AppUser user = user("APPROVE_BENEFIT");
        BenefitEvent b = benefit(ref);
        if (!DUE.equals(b.status)) {
            throw new ConflictException(ref + " is already " + b.status.toLowerCase(Locale.ROOT) + ".");
        }
        String note = required(body, "note", 500);
        b.decidedBy = user.getUsername();
        b.decidedAt = LocalDateTime.now();
        b.decisionNote = note;
        boolean approve = body.path("approve").asBoolean(false);
        b.status = approve ? APPROVED : DECLINED;
        if (approve && SURRENDER.equals(b.benefitType)) {
            // The contract ends when its surrender is approved; the value is then paid like any other benefit.
            workflow.terminate(b.policyRef, "SURRENDER", "Surrendered for " + Money.text(b.amount) + " (" + ref + ")",
                    LocalDate.now(), user.getUsername());
        }
        audit.record(user.getUsername(), "Benefit " + b.status.toLowerCase(Locale.ROOT), "Policy", b.policyRef,
                ref + " " + b.benefitType + " " + Money.text(b.amount) + ": " + note);
        return benefitView(b);
    }

    /** {paymentReference}: records the payout of an approved benefit; the subledger posts it from the event. */
    @Transactional
    public ServicingViews.BenefitEventView payBenefit(String ref, JsonNode body) {
        AppUser user = user("APPROVE_BENEFIT");
        BenefitEvent b = benefit(ref);
        if (!APPROVED.equals(b.status)) {
            throw new ConflictException(ref + " must be approved before it is paid; it is "
                    + b.status.toLowerCase(Locale.ROOT) + ".");
        }
        b.status = PAID;
        b.paidBy = user.getUsername();
        b.paidAt = LocalDateTime.now();
        b.paymentReference = required(body, "paymentReference", 60);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", b.policyRef);
        data.put("customerName", b.customerName == null ? "" : b.customerName);
        data.put("product", b.product);
        data.put("benefitRef", b.ref);
        data.put("benefitType", b.benefitType);
        data.put("amount", b.amount.toPlainString());
        data.put("paymentReference", b.paymentReference);
        outbox.publish(Events.BENEFIT_PAID, b.ref, data, user.getUsername());
        InsurancePolicy policy = workflow.policy(b.policyRef);
        history.record(WorkflowHistory.POLICY, b.policyRef, policy.getCustomerName(), "Benefit Paid",
                policy.getStatus(), policy.getStatus(), label(b.benefitType) + " of " + Money.text(b.amount) + " paid ("
                        + ref + ", " + b.paymentReference + ")", user.getUsername());
        if (Set.of(RETURN_OF_PREMIUM, MATURITY).contains(b.benefitType) && IN_FORCE.contains(policy.getStatus())) {
            workflow.terminate(b.policyRef, "MATURITY", label(b.benefitType) + " paid at the end of the term (" + ref
                    + ")", LocalDate.now(), user.getUsername());
        }
        return benefitView(b);
    }

    /** The surrender value, only for a product with an approved surrender rule (AT-IL03-03). */
    @Transactional(readOnly = true)
    public ServicingViews.SurrenderQuote surrenderQuote(String policyRef) {
        user("READ_SERVICING");
        return surrenderValue(workflow.policy(policyRef));
    }

    /** {reason}: asks to surrender; the value is raised as a benefit for approval and payment. */
    @Transactional
    public ServicingViews.BenefitEventView surrender(String policyRef, JsonNode body) {
        AppUser user = user("MANAGE_SERVICING");
        InsurancePolicy policy = workflow.policy(policyRef);
        ServicingViews.SurrenderQuote quote = surrenderValue(policy);
        String reason = required(body, "reason", 300);
        if (em.createQuery("select count(b) from BenefitEvent b where b.policyRef = :ref"
                        + " and b.benefitType = 'SURRENDER' and b.status in ('DUE', 'APPROVED')", Long.class)
                .setParameter("ref", policyRef).getSingleResult() > 0) {
            throw new ConflictException(policyRef + " already has a surrender in progress.");
        }
        BenefitEvent b = benefitEvent(policy, SURRENDER, LocalDate.now(), quote.value(), quote.basis()
                + " Requested: " + reason);
        publishDue(b, user.getUsername());
        return benefitView(b);
    }

    /** {reason}: stops premiums and reduces the sum assured, only for a product with a paid-up rule. */
    @Transactional
    public ServicingViews.ServicingView makePaidUp(String policyRef, JsonNode body) {
        AppUser user = user("APPROVE_ENDORSEMENT");
        InsurancePolicy policy = workflow.policy(policyRef);
        BenefitRule rule = benefitRules(policy.getProduct()).stream().filter(r -> PAID_UP.equals(r.benefitType))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(policy.getProduct()
                        + " has no approved paid-up rule, so its policies cannot be made paid-up."));
        if (!PolicyWorkflow.ACTIVE.equals(policy.getStatus())) {
            throw new IllegalArgumentException("Only an active policy can be made paid-up.");
        }
        long months = monthsInForce(policy);
        if (rule.minMonths != null && months < rule.minMonths) {
            throw new IllegalArgumentException("A paid-up value is available after " + rule.minMonths
                    + " months in force; this policy has " + months + ".");
        }
        String reason = required(body, "reason", 300);
        int termMonths = (rule.termYears == null ? 15 : rule.termYears) * 12;
        BigDecimal reduced = policy.getAmount().multiply(BigDecimal.valueOf(Math.min(months, termMonths)))
                .divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
        BigDecimal before = policy.getAmount();
        policy.setAmount(reduced);
        policy.setPremium(Money.of(BigDecimal.ZERO));
        policy.setPremiumFrequency("PAID_UP");
        history.record(WorkflowHistory.POLICY, policyRef, policy.getCustomerName(), "Made Paid-up", policy.getStatus(),
                policy.getStatus(), "Premiums stop; sum assured reduced from " + Money.text(before) + " to "
                        + Money.text(reduced) + " (" + months + " of " + termMonths + " months paid): " + reason,
                user.getUsername(), LocalDate.now());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", policyRef);
        data.put("endorsementType", PAID_UP);
        data.put("oldSumAssured", before);
        data.put("newSumAssured", reduced);
        outbox.publish(Events.POLICY_CHANGED, policyRef, data, user.getUsername());
        return view(policyRef);
    }

    private ServicingViews.SurrenderQuote surrenderValue(InsurancePolicy policy) {
        BenefitRule rule = benefitRules(policy.getProduct()).stream().filter(r -> SURRENDER.equals(r.benefitType))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(policy.getProduct()
                        + " has no approved surrender rule, so its policies have no surrender value."));
        if (!IN_FORCE.contains(policy.getStatus())) {
            throw new IllegalArgumentException("Policy " + policy.getRef() + " is " + policy.getStatus()
                    + " and cannot be surrendered.");
        }
        long months = monthsInForce(policy);
        if (rule.minMonths != null && months < rule.minMonths) {
            throw new IllegalArgumentException("A surrender value is available after " + rule.minMonths
                    + " months in force; this policy has " + months + ".");
        }
        BigDecimal paid = premiumsPaid(policy.getRef())[1];
        BigDecimal percent = rule.percent == null ? BigDecimal.ZERO : rule.percent;
        BigDecimal value = paid.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return new ServicingViews.SurrenderQuote(policy.getRef(), value, percent.stripTrailingZeros().toPlainString()
                + "% of premiums paid " + Money.text(paid) + " after " + months + " months in force.", months);
    }

    /** Benefits whose due date has passed and which the policy qualifies for. */
    private List<ServicingViews.ScheduledBenefit> dueBenefits(InsurancePolicy policy, List<BenefitRule> rules,
            LocalDate asOf) {
        return scheduleUpTo(policy, rules, asOf, true);
    }

    /** What the product's rules will make due next: shown on the policy before anything is owed. */
    private List<ServicingViews.ScheduledBenefit> schedule(InsurancePolicy policy, List<BenefitRule> rules) {
        if (policy.getCoverStartDate() == null || !IN_FORCE.contains(policy.getStatus())) {
            return List.of();
        }
        return scheduleUpTo(policy, rules, null, false);
    }

    private List<ServicingViews.ScheduledBenefit> scheduleUpTo(InsurancePolicy policy, List<BenefitRule> rules,
            LocalDate asOf, boolean dueOnly) {
        LocalDate start = policy.getCoverStartDate();
        LocalDate today = LocalDate.now();
        List<ServicingViews.ScheduledBenefit> out = new ArrayList<>();
        for (BenefitRule rule : rules) {
            if (CASHBACK.equals(rule.benefitType) && rule.intervalMonths != null && rule.premiumMultiple != null) {
                BigDecimal amount = Money.of(policy.getPremium().multiply(rule.premiumMultiple));
                String basis = rule.premiumMultiple.stripTrailingZeros().toPlainString() + " × monthly premium "
                        + Money.text(policy.getPremium()) + " after every " + rule.intervalMonths
                        + " months in force (cover from " + start + ").";
                if (dueOnly) {
                    for (LocalDate due = start.plusMonths(rule.intervalMonths); !due.isAfter(asOf);
                            due = due.plusMonths(rule.intervalMonths)) {
                        out.add(new ServicingViews.ScheduledBenefit(CASHBACK, due, amount, basis));
                    }
                } else {
                    LocalDate next = start.plusMonths(rule.intervalMonths);
                    while (!next.isAfter(today)) {
                        next = next.plusMonths(rule.intervalMonths);
                    }
                    out.add(new ServicingViews.ScheduledBenefit(CASHBACK, next, amount, basis));
                }
            } else if (Set.of(RETURN_OF_PREMIUM, MATURITY).contains(rule.benefitType) && rule.termYears != null) {
                LocalDate due = start.plusYears(rule.termYears);
                BigDecimal percent = rule.percent == null ? HUNDRED : rule.percent;
                if (dueOnly) {
                    if (!due.isAfter(asOf)) {
                        BigDecimal paid = premiumsPaid(policy.getRef())[1];
                        out.add(new ServicingViews.ScheduledBenefit(rule.benefitType, due,
                                paid.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP),
                                percent.stripTrailingZeros().toPlainString() + "% of premiums paid " + Money.text(paid)
                                        + " on survival to the end of the " + rule.termYears + "-year term."));
                    }
                } else if (due.isAfter(today)) {
                    BigDecimal projected = policy.getPremium().multiply(BigDecimal.valueOf(12L * rule.termYears))
                            .multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                    out.add(new ServicingViews.ScheduledBenefit(rule.benefitType, due, projected,
                            percent.stripTrailingZeros().toPlainString() + "% of the premiums paid by then, on survival"
                                    + " to the end of the " + rule.termYears + "-year term (shown if every premium is"
                                    + " paid at today's premium)."));
                }
            }
        }
        out.sort(java.util.Comparator.comparing(ServicingViews.ScheduledBenefit::dueDate));
        return out;
    }

    private BenefitEvent benefitEvent(InsurancePolicy policy, String type, LocalDate due, BigDecimal amount,
            String basis) {
        BenefitEvent b = new BenefitEvent();
        b.ref = numbers.next("BEN-", 6, () -> 0);
        b.policyRef = policy.getRef();
        b.customerName = policy.getCustomerName();
        b.product = policy.getProduct();
        b.benefitType = type;
        b.dueDate = due;
        b.amount = Money.of(amount);
        b.basis = clip(basis, 1000);
        b.status = DUE;
        b.createdAt = LocalDateTime.now();
        em.persist(b);
        return b;
    }

    private void publishDue(BenefitEvent b, String actor) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("policyRef", b.policyRef);
        data.put("customerName", b.customerName == null ? "" : b.customerName);
        data.put("product", b.product);
        data.put("benefitRef", b.ref);
        data.put("benefitType", b.benefitType);
        data.put("dueDate", b.dueDate.toString());
        data.put("amount", b.amount.toPlainString());
        outbox.publish(Events.BENEFIT_DUE, b.ref, data, actor);
    }

    // ── reinstatement and termination (NS-IL03-004) ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ServicingViews.PolicyView> reinstatements() {
        user("READ_SERVICING");
        return em.createQuery("select p from InsurancePolicy p where p.status = :s order by p.id desc",
                        InsurancePolicy.class)
                .setParameter("s", PolicyWorkflow.REINSTATEMENT_PENDING).getResultList().stream()
                .map(this::policyView).toList();
    }

    /** {approve true|false, note} */
    @Transactional
    public ServicingViews.PolicyView decideReinstatement(String policyRef, JsonNode body) {
        AppUser user = user("APPROVE_ENDORSEMENT");
        String note = required(body, "note", 400);
        InsurancePolicy policy = body.path("approve").asBoolean(false)
                ? workflow.approveReinstatement(policyRef, note, user.getUsername())
                : workflow.declineReinstatement(policyRef, note, user.getUsername());
        audit.record(user.getUsername(), "Reinstatement decided", "Policy", policyRef, policy.getStatus() + ": " + note);
        return policyView(policy);
    }

    /** {reason DEATH|MATURITY|OTHER, note, effectiveDate?}: ends the contract for a stated cause. */
    @Transactional
    public ServicingViews.PolicyView terminate(String policyRef, JsonNode body) {
        AppUser user = user("APPROVE_ENDORSEMENT");
        String cause = required(body, "reason", 30).toUpperCase(Locale.ROOT);
        // IL03: the cause distinguishes death, cancellation, expiry and other approved causes. Lapse and surrender
        // keep their own processes (the premium sweep and the product's surrender benefit).
        if (!TERMINATION_CAUSES.contains(cause)) {
            throw new IllegalArgumentException("Choose the cause: death, cancellation, expiry, maturity or other. Lapse"
                    + " and surrender have their own processes.");
        }
        String note = required(body, "note", 400);
        LocalDate on = optional(body, "effectiveDate") == null ? LocalDate.now()
                : LocalDate.parse(optional(body, "effectiveDate"));
        // A cancellation goes through the cancellation process: cooling-off is flagged, PolicyCancelled is published,
        // and cover runs to the end of today.
        InsurancePolicy policy = "CANCELLATION".equals(cause)
                ? workflow.cancel(policyRef, note, user.getUsername())
                : workflow.terminate(policyRef, cause, note, on, user.getUsername());
        audit.record(user.getUsername(), "Policy terminated", "Policy", policyRef, cause + ": " + note);
        return policyView(policy);
    }

    // ── rules ─────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ServicingViews.BenefitRuleView> benefitRuleViews() {
        user("READ_SERVICING");
        return em.createQuery("select r from BenefitRule r order by r.product, r.benefitType", BenefitRule.class)
                .getResultList().stream().map(this::ruleView).toList();
    }

    /** {product, benefitType, intervalMonths?, premiumMultiple?, percent?, minMonths?, termYears?, description, active} */
    @Transactional
    public ServicingViews.BenefitRuleView saveBenefitRule(JsonNode body) {
        AppUser user = user("MANAGE_BENEFIT_RULE");
        String product = required(body, "product", 60);
        String type = required(body, "benefitType", 30).toUpperCase(Locale.ROOT);
        if (!BENEFIT_TYPES.contains(type)) {
            throw new IllegalArgumentException("benefitType must be one of " + String.join(", ",
                    BENEFIT_TYPES.stream().sorted().toList()) + ".");
        }
        if (em.createQuery("select count(p) from InsuranceProduct p where p.name = :name", Long.class)
                .setParameter("name", product).getSingleResult() == 0) {
            throw new IllegalArgumentException(product + " is not a product.");
        }
        BenefitRule r = em.createQuery("select r from BenefitRule r where r.product = :p and r.benefitType = :t",
                BenefitRule.class).setParameter("p", product).setParameter("t", type).getResultStream().findFirst()
                .orElseGet(BenefitRule::new);
        r.product = product;
        r.benefitType = type;
        r.intervalMonths = integer(body, "intervalMonths");
        r.premiumMultiple = decimal(body, "premiumMultiple");
        r.percent = decimal(body, "percent");
        r.minMonths = integer(body, "minMonths");
        r.termYears = integer(body, "termYears");
        r.description = required(body, "description", 500);
        r.active = !body.has("active") || body.path("active").asBoolean(true);
        if (CASHBACK.equals(type) && (r.intervalMonths == null || r.intervalMonths < 1 || r.premiumMultiple == null)) {
            throw new IllegalArgumentException("A cashback rule needs intervalMonths and premiumMultiple.");
        }
        if (Set.of(RETURN_OF_PREMIUM, MATURITY).contains(type) && (r.termYears == null || r.termYears < 1)) {
            throw new IllegalArgumentException("A return-of-premium or maturity rule needs termYears.");
        }
        if (SURRENDER.equals(type) && (r.percent == null || r.percent.signum() <= 0)) {
            throw new IllegalArgumentException("A surrender rule needs the percent of premiums paid that is returned.");
        }
        r.updatedBy = user.getUsername();
        r.updatedAt = LocalDateTime.now();
        em.persist(r);
        em.flush();
        audit.record(user.getUsername(), "Benefit rule saved", "Product", product, type + (r.active ? "" : " (inactive)"));
        return ruleView(r);
    }

    @Transactional(readOnly = true)
    public List<ServicingViews.ServicingRuleView> servicingRules() {
        user("READ_SERVICING");
        return em.createQuery("select r from ServicingRule r order by r.product", ServicingRule.class).getResultList()
                .stream().map(this::servicingRuleView).toList();
    }

    /** {reinstatementNeedsApproval, maxBackdateDays, childReviewAge, maxCoverAge} */
    @Transactional
    public ServicingViews.ServicingRuleView saveServicingRule(Long id, JsonNode body) {
        AppUser user = user("MANAGE_BENEFIT_RULE");
        ServicingRule r = em.find(ServicingRule.class, id);
        if (r == null) {
            throw new NotFoundException("Servicing rule not found");
        }
        r.reinstatementNeedsApproval = body.path("reinstatementNeedsApproval").asBoolean(r.reinstatementNeedsApproval);
        r.maxBackdateDays = body.path("maxBackdateDays").asInt(r.maxBackdateDays);
        r.childReviewAge = body.path("childReviewAge").asInt(r.childReviewAge);
        r.maxCoverAge = body.path("maxCoverAge").asInt(r.maxCoverAge);
        if (r.maxBackdateDays < 0 || r.maxBackdateDays > 3650 || r.childReviewAge < 1 || r.maxCoverAge < r.childReviewAge
                || r.maxCoverAge > 120) {
            throw new IllegalArgumentException("Backdating is 0–3650 days and ages must satisfy 1 ≤ child review age ≤"
                    + " cover cessation age ≤ 120.");
        }
        r.updatedBy = user.getUsername();
        r.updatedAt = LocalDateTime.now();
        audit.record(user.getUsername(), "Servicing rule saved", "Product", r.product, null);
        return servicingRuleView(r);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private BigDecimal proRata(InsurancePolicy policy, BigDecimal monthlyChange, LocalDate effective) {
        if (monthlyChange.signum() == 0 || policy.getNextPremiumDue() == null) {
            return Money.of(BigDecimal.ZERO);
        }
        LocalDate periodEnd = policy.getNextPremiumDue();
        LocalDate periodStart = periodEnd.minusMonths(1);
        LocalDate from = effective.isBefore(periodStart) ? periodStart : effective;
        if (!from.isBefore(periodEnd)) {
            return Money.of(BigDecimal.ZERO);
        }
        long days = ChronoUnit.DAYS.between(periodStart, periodEnd);
        long remaining = ChronoUnit.DAYS.between(from, periodEnd);
        // A backdated change also owes (or refunds) every whole month since it took effect.
        long wholeMonths = effective.isBefore(periodStart) ? ChronoUnit.MONTHS.between(effective, periodStart) : 0;
        return monthlyChange.multiply(BigDecimal.valueOf(remaining)).divide(BigDecimal.valueOf(days), 2,
                RoundingMode.HALF_UP).add(monthlyChange.multiply(BigDecimal.valueOf(wholeMonths)));
    }

    /** The change in monthly commission at the product's renewal rate, when a rule exists. */
    private BigDecimal commissionEffect(String product, BigDecimal premiumChange) {
        BigDecimal rate = em.createQuery("select r.renewalRatePercent from CommissionRule r where r.product = :p"
                        + " order by r.effectiveFrom desc", BigDecimal.class)
                .setParameter("p", product).setMaxResults(1).getResultStream().findFirst().orElse(null);
        return rate == null ? Money.of(BigDecimal.ZERO)
                : premiumChange.multiply(rate).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** The change in sum assured ceded under the treaty in force for the product. */
    private BigDecimal reinsuranceEffect(String product, BigDecimal oldSa, BigDecimal newSa, LocalDate on) {
        Treaty treaty = em.createQuery("select t from Treaty t where t.status = :s", Treaty.class)
                .setParameter("s", Treaty.APPROVED).getResultList().stream()
                .filter(t -> t.covers(product) && t.inForceOn(on)).findFirst().orElse(null);
        if (treaty == null) {
            return Money.of(BigDecimal.ZERO);
        }
        if (Treaty.SURPLUS.equals(treaty.getTreatyType())) {
            BigDecimal retention = treaty.getRetention();
            return newSa.subtract(retention).max(BigDecimal.ZERO).subtract(oldSa.subtract(retention).max(BigDecimal.ZERO));
        }
        return newSa.subtract(oldSa).multiply(treaty.getCededPercent()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    private Optional<InsuranceClaim> claimPaidSince(String policyRef, LocalDate effective) {
        return em.createQuery("select c from InsuranceClaim c where c.policyRef = :ref", InsuranceClaim.class)
                .setParameter("ref", policyRef).getResultList().stream()
                .filter(c -> "Paid".equals(c.getStatus()))
                .filter(c -> {
                    try {
                        return !LocalDate.parse(c.getEventDate().trim()).isBefore(effective);
                    } catch (RuntimeException unparseable) {
                        return true;
                    }
                })
                .findFirst();
    }

    private long monthsInForce(InsurancePolicy policy) {
        return policy.getCoverStartDate() == null ? 0
                : ChronoUnit.MONTHS.between(policy.getCoverStartDate(), LocalDate.now());
    }

    /** {count, total} of premiums received and not reversed. */
    private BigDecimal[] premiumsPaid(String policyRef) {
        Object[] row = em.createQuery("select count(p), coalesce(sum(p.amount), 0) from PremiumPayment p"
                        + " where p.policyRef = :ref and p.status <> 'REVERSED'", Object[].class)
                .setParameter("ref", policyRef).getSingleResult();
        return new BigDecimal[] {BigDecimal.valueOf((Long) row[0]), Money.of(new BigDecimal(row[1].toString()))};
    }

    private List<PolicyLifeCover> lives(InsurancePolicy policy) {
        return em.createQuery("select l from PolicyLifeCover l where l.policy = :policy order by l.displayOrder",
                PolicyLifeCover.class).setParameter("policy", policy).getResultList();
    }

    private long activeLives(InsurancePolicy policy) {
        return lives(policy).stream().filter(PolicyLifeCover::isActiveLife).count();
    }

    private PolicyLifeCover life(InsurancePolicy policy, long id) {
        PolicyLifeCover life = lives(policy).stream().filter(l -> l.getId().equals(id)).findFirst()
                .orElseThrow(() -> new NotFoundException("That life is not on " + policy.getRef() + "."));
        if (!life.isActiveLife()) {
            throw new ConflictException(life.getFullName() + "'s cover has already ceased.");
        }
        if (life.isMainInsured()) {
            throw new IllegalArgumentException("The main insured cannot be removed; their cover ends with the policy.");
        }
        return life;
    }

    private static void copyLife(com.fasterxml.jackson.databind.node.ObjectNode changes, JsonNode body) {
        changes.put("fullName", body.path("fullName").asText().trim());
        changes.put("relationship", body.path("relationship").asText().trim());
        changes.put("dateOfBirth", body.path("dateOfBirth").asText().trim());
    }

    private CoverTier tier(String product, String tierName) {
        return em.createQuery("select t from CoverTier t where t.product.name = :product and t.name = :tier"
                        + " and t.active = true", CoverTier.class)
                .setParameter("product", product).setParameter("tier", tierName.trim()).getResultStream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Plan " + tierName + " is not available for " + product
                        + "."));
    }

    private CustomerAccount customer(String ref) {
        return em.createQuery("select c from CustomerAccount c where c.ref = :ref", CustomerAccount.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + ref + " not found"));
    }

    private List<BenefitRule> benefitRules(String product) {
        return em.createQuery("select r from BenefitRule r where r.product = :p and r.active = true", BenefitRule.class)
                .setParameter("p", product).getResultList();
    }

    private ServicingRule servicingRule(String product) {
        return em.createQuery("select r from ServicingRule r where r.product = :p", ServicingRule.class)
                .setParameter("p", product).getResultStream().findFirst().orElseGet(() -> {
                    ServicingRule fallback = new ServicingRule();
                    fallback.product = product;
                    fallback.maxBackdateDays = 90;
                    fallback.childReviewAge = 21;
                    fallback.maxCoverAge = 70;
                    return fallback;
                });
    }

    private BenefitEvent benefit(String ref) {
        return em.createQuery("select b from BenefitEvent b where b.ref = :ref", BenefitEvent.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Benefit " + ref + " not found"));
    }

    private long count(String jpql) {
        return em.createQuery(jpql, Long.class).getSingleResult();
    }

    private static BigDecimal modal(BigDecimal monthly, String frequency) {
        return Money.of(monthly.multiply(BigDecimal.valueOf(FREQUENCY_MONTHS.getOrDefault(frequency, 1))));
    }

    private static String label(String code) {
        String text = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    private ServicingViews.PolicyView policyView(InsurancePolicy p) {
        LocalDate today = LocalDate.now();
        String status = p.getStatus();
        String cover = PolicyWorkflow.ACTIVE.equals(status) ? "In cover"
                : Set.of(PolicyWorkflow.ACTIVE_NO_COVER, PolicyWorkflow.REINSTATEMENT_PENDING).contains(status)
                        ? "No cover" : PolicyWorkflow.PENDING_PAYMENT.equals(status) ? "Not started" : "Ended";
        String payment;
        if (PolicyWorkflow.PENDING_PAYMENT.equals(status)) {
            payment = "Awaiting first premium";
        } else if (!IN_FORCE.contains(status)) {
            payment = "Closed";
        } else if ("PAID_UP".equals(p.getPremiumFrequency())) {
            payment = "Paid-up";
        } else if (p.getNextPremiumDue() == null || !p.getNextPremiumDue().isBefore(today)) {
            payment = "Up to date";
        } else if (p.getGracePeriodEnd() != null && !p.getGracePeriodEnd().isBefore(today)) {
            payment = "In grace";
        } else {
            payment = "Overdue";
        }
        BigDecimal[] paid = premiumsPaid(p.getRef());
        // A contract that has ended has no premium due and no grace period (test C-37).
        boolean billed = IN_FORCE.contains(status) || PolicyWorkflow.PENDING_PAYMENT.equals(status);
        return new ServicingViews.PolicyView(p.getRef(), p.getCustomerRef(), p.getCustomerName(), p.getProduct(),
                p.getTier(), p.getAmount(), p.getPremium(), p.getPremiumFrequency(),
                modal(p.getPremium(), p.getPremiumFrequency()), status, cover, payment, p.getCoverStartDate(),
                billed ? p.getNextPremiumDue() : null, billed ? p.getGracePeriodEnd() : null,
                p.getTerminationReason(), p.getTerminatedOn(),
                p.getPayerName(), p.getPayerRelationship(), p.getUwTerms(), paid[0].longValue(), paid[1]);
    }

    private ServicingViews.LifeView lifeView(PolicyLifeCover l) {
        return new ServicingViews.LifeView(l.getId(), l.getFullName(), l.getLifeType(), l.isMainInsured(),
                l.getDateOfBirth(), l.getAgeAtEntry(),
                l.getDateOfBirth() == null ? null : Period.between(l.getDateOfBirth(), LocalDate.now()).getYears(),
                l.getCoverAmount(), l.getMonthlyPremium(), l.getLifeStatus(), l.getEffectiveFrom(), l.getEffectiveTo(),
                l.getWaitingPeriodStart(), l.getCeaseReason());
    }

    private ServicingViews.EndorsementView endorsementView(Endorsement e, InsurancePolicy policy) {
        return new ServicingViews.EndorsementView(e.ref, e.policyRef, policy.getCustomerName(), policy.getProduct(),
                e.type, e.effectiveDate, e.backdated, e.status, e.reason, e.beforeSummary, e.afterSummary,
                e.oldSumAssured, e.newSumAssured, e.oldPremium, e.newPremium, e.premiumAdjustment, e.commissionEffect,
                e.reinsuranceEffect, e.holdReason, e.requestedBy, e.requestedAt, e.decidedBy, e.decidedAt,
                e.decisionNote, e.appliedAt);
    }

    private ServicingViews.BenefitEventView benefitView(BenefitEvent b) {
        return new ServicingViews.BenefitEventView(b.ref, b.policyRef, b.customerName, b.product, b.benefitType,
                b.dueDate, b.amount, b.basis, b.status, b.createdAt, b.decidedBy, b.decidedAt, b.decisionNote, b.paidBy,
                b.paidAt, b.paymentReference);
    }

    private ServicingViews.LifeReviewView reviewView(LifeReview r) {
        return new ServicingViews.LifeReviewView(r.id, r.policyRef, r.lifeId, r.lifeName, r.reviewType, r.dueDate,
                r.status, r.note, r.closedBy, r.closedAt);
    }

    private ServicingViews.BenefitRuleView ruleView(BenefitRule r) {
        return new ServicingViews.BenefitRuleView(r.id, r.product, r.benefitType, r.intervalMonths, r.premiumMultiple,
                r.percent, r.minMonths, r.termYears, r.description, r.active, r.updatedBy, r.updatedAt);
    }

    private ServicingViews.ServicingRuleView servicingRuleView(ServicingRule r) {
        return new ServicingViews.ServicingRuleView(r.id, r.product, r.reinstatementNeedsApproval, r.maxBackdateDays,
                r.childReviewAge, r.maxCoverAge, r.updatedBy, r.updatedAt);
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static Integer integer(JsonNode body, String field) {
        String value = optional(body, field);
        return value == null ? null : Integer.valueOf(value);
    }

    private static BigDecimal decimal(JsonNode body, String field) {
        String value = optional(body, field);
        return value == null ? null : new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** Plain-language names for request fields, so a refusal never shows an API field name (test C-42). */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(Map.entry("amount", "the amount"),
            Map.entry("benefitType", "the benefit type"), Map.entry("dateOfBirth", "the date of birth"),
            Map.entry("description", "a description"), Map.entry("frequency", "the premium frequency"),
            Map.entry("fullName", "the person's full name"), Map.entry("newCustomerRef", "the new owner"),
            Map.entry("note", "a note"), Map.entry("outcome", "the outcome"),
            Map.entry("payerName", "the payer's name"),
            Map.entry("payerRelationship", "the payer's relationship to the policyholder"),
            Map.entry("paymentReference", "the payment reference"), Map.entry("product", "the product"),
            Map.entry("reason", "a reason"), Map.entry("relationship", "the relationship"),
            Map.entry("type", "the type of change"));

    private static String required(JsonNode body, String field, int max) {
        String value = optional(body, field);
        if (value == null) {
            throw new IllegalArgumentException("Enter " + FIELD_LABELS.getOrDefault(field, field) + ".");
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
