package com.nsure.portfolio;

import com.nsure.common.Money;
import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.ControlKey;
import com.nsure.controls.ControlService;
import com.nsure.controls.FourEyesControl;
import com.nsure.controls.ProductStpPolicy;
import com.nsure.controls.ProductStpPolicyRepository;
import com.nsure.security.approval.ApprovalAuthorityService;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.security.domain.AppUser;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claim lifecycle, ported from the demo server (claims/state_machine.py, claim_validator.py, payee.py and
 * api.py _lodge_claim / admin_update_claim):
 *
 * <pre>
 *   lodge ─▶ INITIATED ─five checks─┬─ all pass and STP-eligible ─▶ APPROVED (STP, no adjudicator)
 *                                    └─ otherwise ───────────────▶ UNDER REVIEW
 *   INITIATED / UNDER REVIEW ──assess──▶ RECOMMENDED ──approve (four-eyes)──▶ APPROVED
 *   APPROVED / PAYOUT FAILED ──pay──▶ PAYOUT SENT ─┬─ confirm-payout ─▶ PAID (ClaimPaid)
 *                                                  └─ payout-failed (reason) ─▶ PAYOUT FAILED
 *   INITIATED / UNDER REVIEW / RECOMMENDED ──reject (reason required)──▶ REJECTED
 * </pre>
 *
 * A payout is Paid only when the provider or bank confirms it settled (NS-SH05-007, AT-SH05-03): sending the
 * instruction leaves it in flight, and a failed or returned payout waits for corrected, re-verified details.
 *
 * The system transitions (straight-through approval and routing to review) are reachable only from lodging,
 * never from an action a person sends.
 */
@Service
public class ClaimWorkflow {

    public static final String INITIATED = "Initiated";
    public static final String UNDER_REVIEW = "Under Review";
    public static final String RECOMMENDED = "Recommended";
    public static final String APPROVED = "Approved";
    public static final String REJECTED = "Rejected";
    public static final String PAID = "Paid";
    public static final String PAYOUT_SENT = "Payout Sent";
    public static final String PAYOUT_FAILED = "Payout Failed";

    public static final Set<String> OPEN_STATES = Set.of(INITIATED, UNDER_REVIEW, RECOMMENDED);

    static final String STP = "STP";
    static final String MANUAL_REVIEW = "Manual Review";
    static final String PASS = "Pass";
    static final String FAIL = "Fail";
    static final String SYSTEM_VERIFIER = "System (registered beneficiary)";

    /** Person actions, the states each may start from, and the permission it needs. */
    private static final Map<String, Transition> TRANSITIONS = new LinkedHashMap<>();

    static {
        TRANSITIONS.put("assess", new Transition(Set.of(INITIATED, UNDER_REVIEW), RECOMMENDED, "ASSESS_CLAIM"));
        TRANSITIONS.put("approve", new Transition(Set.of(RECOMMENDED), APPROVED, "APPROVE_CLAIM"));
        TRANSITIONS.put("reject", new Transition(Set.of(INITIATED, UNDER_REVIEW, RECOMMENDED), REJECTED, "REJECT_CLAIM"));
        TRANSITIONS.put("pay", new Transition(Set.of(APPROVED, PAYOUT_FAILED), PAYOUT_SENT, "SETTLE_CLAIM"));
        TRANSITIONS.put("confirm-payout", new Transition(Set.of(PAYOUT_SENT), PAID, "SETTLE_CLAIM"));
        TRANSITIONS.put("payout-failed", new Transition(Set.of(PAYOUT_SENT), PAYOUT_FAILED, "SETTLE_CLAIM"));
        TRANSITIONS.put("reopen", new Transition(Set.of(REJECTED), UNDER_REVIEW, "APPROVE_CLAIM"));
    }

    private record Transition(Set<String> from, String to, String permission) {
    }

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final ControlService controls;
    private final FourEyesControl fourEyes;
    private final PolicyWorkflow policies;
    private final WorkflowHistory history;
    private final Outbox outbox;
    private final ApprovalAuthorityService approvalLimits;
    private final ProductStpPolicyRepository stpPolicies;
    private final ReferenceNumbers references;
    private final ClaimFinancials financials;

    public ClaimWorkflow(EntityManager em, ControlService controls, FourEyesControl fourEyes,
            PolicyWorkflow policies, WorkflowHistory history, Outbox outbox,
            ApprovalAuthorityService approvalLimits, ProductStpPolicyRepository stpPolicies,
            ReferenceNumbers references, ClaimFinancials financials) {
        this.references = references;
        this.financials = financials;
        this.approvalLimits = approvalLimits;
        this.stpPolicies = stpPolicies;
        this.em = em;
        this.controls = controls;
        this.fourEyes = fourEyes;
        this.policies = policies;
        this.history = history;
        this.outbox = outbox;
    }

    /** Actions legal from `state`, ignoring who is acting. */
    public static List<String> allowedActions(String state) {
        return TRANSITIONS.entrySet().stream()
                .filter(entry -> entry.getValue().from().contains(state))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** What a claim is lodged with: who claims, what happened, and when. */
    public record Lodgement(String policyRef, String claimantName, String claimantMobile,
            String claimantRelationship, String eventDate, String description, String claimType,
            String coveredLife) {

        /** A claim for the policyholder's own life. */
        public Lodgement(String policyRef, String claimantName, String claimantMobile, String claimantRelationship,
                String eventDate, String description, String claimType) {
            this(policyRef, claimantName, claimantMobile, claimantRelationship, eventDate, description, claimType,
                    null);
        }
    }

    /**
     * Creates the claim, runs the checks, and moves it to its first resting state: Approved straight through, or
     * Under Review. The caller has already decided the actor may claim on this policy.
     */
    @Transactional
    public InsuranceClaim lodge(Lodgement input, String actor) {
        InsurancePolicy policy = policies.policy(required(input.policyRef(), "policy"));
        String claimant = required(input.claimantName(), "claimantName");
        String mobile = required(input.claimantMobile(), "claimantMobile");
        String relationship = required(input.claimantRelationship(), "claimantRelationship");
        String description = required(input.description(), "description");
        LocalDate event = eventDate(input.eventDate());
        if (claimant.length() > 200 || mobile.length() > 40 || relationship.length() > 80 || description.length() > 1000) {
            throw new IllegalArgumentException("Claim details are too long.");
        }

        InsuranceClaim claim = new InsuranceClaim();
        claim.setRef(nextRef());
        claim.setCustomerName(policy.getCustomerName());
        claim.setCustomerRef(policy.getCustomerRef());
        claim.setProduct(policy.getProduct());
        claim.setPolicyRef(policy.getRef());
        // A claim pays the policy's cover; an assessor may approve a partial amount.
        claim.setAmount(policy.getAmount());
        coveredLife(claim, policy, input.coveredLife());
        claim.setPremium(BigDecimal.ZERO);
        claim.setStatus(INITIATED);
        claim.setRecordDate(LocalDate.now().format(DISPLAY));
        claim.setDisplayOrder(0);
        claim.setLodgedAt(LocalDateTime.now());
        claim.setFourEyesApplied(Boolean.FALSE);
        claim.setClaimantName(claimant);
        claim.setClaimantMobile(mobile);
        claim.setClaimantRelationship(relationship);
        claim.setEventDate(event.toString());
        claim.setDescription(description);
        claim.setClaimType(input.claimType() == null || input.claimType().isBlank() ? "Death benefit" : input.claimType().trim());
        payeeFromBeneficiary(claim, policy);
        em.persist(claim);
        history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Lodged", null, INITIATED,
                "On " + policy.getRef() + " by " + claimant + " (" + relationship + "), event " + event, actor);
        financials.reserve(claim, claim.getAmount(), "Initial case reserve on lodging", actor);

        boolean allPassed = runChecks(claim, policy);
        StpDecision stp = allPassed
                ? stpDecision(claim, policy)
                : new StpDecision(STP_CHECKS_FAILED, "Failed: " + String.join(", ", failedChecks(claim)));
        String ineligible = stp == null ? null : stp.text();
        // Spec 12 C-12: every claim records whether it was STP-eligible and, if not, which gate stopped it.
        claim.setStpEligible(stp == null);
        claim.setStpIneligibleCode(stp == null ? null : stp.code());
        outbox.publish(Events.CLAIM_RECEIVED, claim.getRef(), decisionPayload(claim, null), actor);
        if (stp == null) {
            claim.verifyPayout(SYSTEM_VERIFIER);
            claim.setStatus(APPROVED);
            claim.setProcessingType(STP);
            claim.setPayoutAmount(claim.getAmount());
            claim.resolve(null);
            financials.reserveLimit(claim, policy, claim.getAmount(), WorkflowHistory.SYSTEM);
            history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Auto Approved", INITIATED,
                    APPROVED, "Straight-through: all five checks passed · payout " + Money.display(claim.getAmount()),
                    WorkflowHistory.SYSTEM);
            outbox.publish(Events.CLAIM_APPROVED, claim.getRef(), decisionPayload(claim, null), WorkflowHistory.SYSTEM);
        } else {
            claim.setStatus(UNDER_REVIEW);
            claim.setProcessingType(MANUAL_REVIEW);
            claim.setStpIneligibleReason(allPassed ? ineligible : null);
            history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Review", INITIATED,
                    UNDER_REVIEW, "[" + stp.code() + "] " + ineligible, WorkflowHistory.SYSTEM);
        }
        return claim;
    }

    // ── STP eligibility (spec 12 §4.1) ────────────────────────────────────────────────────────────────────

    static final String STP_CHECKS_FAILED = "CHECKS_FAILED";
    static final String STP_DISABLED = "STP_DISABLED";
    static final String STP_OVER_CEILING = "OVER_CEILING";
    static final String STP_NO_VERIFIED_PAYOUT = "NO_VERIFIED_PAYOUT";
    static final String STP_PRODUCT_OPT_OUT = "PRODUCT_OPT_OUT";
    static final String STP_CLAIM_LIMIT = "CLAIM_LIMIT";
    static final String STP_OVER_LIMIT = "OVER_LIMIT";

    /** Why a claim whose checks all passed may not be approved straight through. */
    record StpDecision(String code, String text) {
    }

    /**
     * Null when the claim may be approved straight through. Each gate resolves product value, then the global
     * control, then deny (C-15) — never a hardcoded fallback.
     */
    private StpDecision stpDecision(InsuranceClaim claim, InsurancePolicy policy) {
        ProductStpPolicy product = stpPolicies.findByProductId(policies.product(policy).getId()).orElse(null);
        if (product != null && !product.isStpEnabled()) {
            return new StpDecision(STP_PRODUCT_OPT_OUT, "Straight-through processing is switched off for "
                    + policy.getProduct());
        }
        if (!controls.isEnabled(ControlKey.CLAIM_STP_ENABLED.key())) {
            return new StpDecision(STP_DISABLED, "Straight-through processing is switched off");
        }
        BigDecimal ceiling = product != null && product.getStpCeiling() != null
                ? product.getStpCeiling()
                : controls.valueOf(ControlKey.CLAIM_STP_CEILING.key());
        if (claim.getAmount().compareTo(ceiling) > 0) {
            return new StpDecision(STP_OVER_CEILING, "Payout of " + Money.display(claim.getAmount())
                    + " is over the straight-through ceiling of " + Money.display(ceiling));
        }
        boolean needsVerifiedPayout = controls.isEnabled(ControlKey.CLAIM_STP_REQUIRES_VERIFIED_PAYOUT.key())
                && (product == null || product.isRequiresVerifiedPayout());
        if (needsVerifiedPayout && !hasPayoutDetails(claim)) {
            return new StpDecision(STP_NO_VERIFIED_PAYOUT, "No registered beneficiary with payout details to pay");
        }
        ClaimFinancials.Limits limits = financials.limits(claim, policy);
        if (claim.getAmount().compareTo(limits.lifeRemaining().min(limits.policyRemaining())) > 0) {
            return new StpDecision(STP_OVER_LIMIT, "Payout of " + Money.display(claim.getAmount())
                    + " is more than what is left of the benefit limit ("
                    + Money.display(limits.lifeRemaining().min(limits.policyRemaining())) + ")");
        }
        if (product != null && product.getMaxClaimsPerPolicyPerYear() != null) {
            long recent = em.createQuery("select count(c) from InsuranceClaim c where c.policyRef = :policy"
                            + " and c.id <> :id and c.status <> :rejected and c.eventDate >= :since", Long.class)
                    .setParameter("policy", policy.getRef())
                    .setParameter("id", claim.getId())
                    .setParameter("rejected", REJECTED)
                    .setParameter("since", LocalDate.now().minusYears(1).toString())
                    .getSingleResult();
            if (recent >= product.getMaxClaimsPerPolicyPerYear()) {
                return new StpDecision(STP_CLAIM_LIMIT, "Policy " + policy.getRef() + " already has " + recent
                        + " claim(s) this year; the straight-through limit is "
                        + product.getMaxClaimsPerPolicyPerYear());
            }
        }
        return null;
    }

    private Map<String, Object> decisionPayload(InsuranceClaim claim, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claimRef", claim.getRef());
        payload.put("policyRef", claim.getPolicyRef());
        payload.put("customerName", claim.getCustomerName());
        payload.put("product", claim.getProduct());
        payload.put("amount", (claim.getPayoutAmount() != null ? claim.getPayoutAmount() : claim.getAmount()).toPlainString());
        payload.put("reason", reason == null ? "" : reason);
        if (claim.getCustomerRef() != null) {
            payload.put("customerRef", claim.getCustomerRef());
        }
        return payload;
    }

    private void publishDecision(String eventType, InsuranceClaim claim, String reason, AppUser actor) {
        outbox.publish(eventType, claim.getRef(), decisionPayload(claim, reason), actor.getUsername());
    }

    /** Re-evaluates the checks on an open claim; never moves it (demo server rerun_checks). */
    @Transactional
    public InsuranceClaim rerunChecks(String ref, AppUser actor) {
        actor.validateHasPermissionTo("UPDATE_CLAIM");
        InsuranceClaim claim = claim(ref);
        requireOpen(claim, "Checks can only be re-run while the claim is open");
        if (claim.getPolicyRef() == null) {
            throw new IllegalArgumentException(ref + " is not linked to a policy, so its checks cannot run.");
        }
        Map<String, String> before = results(claim);
        runChecks(claim, policies.policy(claim.getPolicyRef()));
        Map<String, String> after = results(claim);
        List<String> changed = new ArrayList<>();
        after.forEach((name, result) -> {
            if (!result.equals(before.get(name))) {
                changed.add(name + ": " + before.get(name) + " → " + result);
            }
        });
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Checks Re-run", claim.getStatus(),
                claim.getStatus(), changed.isEmpty() ? "No change" : String.join("; ", changed), actor.getUsername());
        return claim;
    }

    /** Payee and payout details; `verified` confirms them against the claim documents (demo admin_set_claim_payee). */
    public record Payee(String name, String relationship, String mobile, String payoutMethod, String accountRef,
            boolean verified) {
    }

    @Transactional
    public InsuranceClaim setPayee(String ref, Payee payee, AppUser actor) {
        if (!actor.hasPermissionTo("UPDATE_CLAIM") && !actor.hasPermissionTo("ASSESS_CLAIM")) {
            actor.validateHasPermissionTo("UPDATE_CLAIM");
        }
        InsuranceClaim claim = claim(ref);
        com.nsure.common.ExpectedVersion.check("Claim " + ref, claim.getRowVersion());
        if (!PAYOUT_FAILED.equals(claim.getStatus())) {
            requireOpen(claim, "The payee can only be changed while the claim is open or its payout has failed");
        }
        String name = trim(payee.name());
        String method = trim(payee.payoutMethod());
        String account = trim(payee.accountRef());
        if (name.isEmpty() || method.isEmpty() || account.isEmpty()) {
            throw new IllegalArgumentException("Payee name, payout method and account / wallet number are required");
        }
        boolean changed = !name.equals(trim(claim.getPayeeName())) || !method.equals(trim(claim.getPayeePayoutMethod()))
                || !account.equals(trim(claim.getPayeeAccountRef()))
                || !trim(payee.relationship()).equals(trim(claim.getPayeeRelationship()))
                || !trim(payee.mobile()).equals(trim(claim.getPayeeMobile()));
        claim.setPayeeName(name);
        claim.setPayeeRelationship(blankToNull(payee.relationship()));
        claim.setPayeeMobile(blankToNull(payee.mobile()));
        claim.setPayeePayoutMethod(method);
        claim.setPayeeAccountRef(account);
        if (changed) {
            claim.setPayeeSource("Entered by Assessor");
            claim.clearPayoutVerification();
        }
        if (payee.verified()) {
            claim.verifyPayout(actor.getUsername());
        }
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Payee Updated", claim.getStatus(),
                claim.getStatus(), (claim.isPayoutVerified() ? "Payout details verified: " : "Payee updated: ")
                        + name + " · " + method + " " + mask(account), actor.getUsername());
        return claim;
    }

    /** Assessor recommends the claim. Needs complete payout details, which the assessor thereby confirms. */
    @Transactional
    public InsuranceClaim assess(String ref, String note, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "assess", actor);
        requireCover(claim, "recommend");
        if (!hasPayoutDetails(claim)) {
            throw new IllegalArgumentException("This claim has no complete payout details. Add the payee's name, "
                    + "payout method and account / wallet number first.");
        }
        if (!claim.isPayoutVerified()) {
            claim.verifyPayout(actor.getUsername());
        }
        claim.recommend(actor.getUsername(), actor.getId());
        claim.setStatus(RECOMMENDED);
        // NS-SH05-003: outstanding mandatory evidence does not stop the recommendation, but it is on the record and
        // the approver must give a reason to approve without it.
        List<String> missing = financials.missingMandatory(claim);
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Assessed", previous, RECOMMENDED,
                "Recommended for payout of " + Money.display(claim.getAmount()) + (trim(note).isEmpty() ? "" : " · " + trim(note))
                        + (missing.isEmpty() ? "" : " · evidence outstanding: " + String.join(", ", missing)),
                actor.getUsername());
        return claim;
    }

    /**
     * Approves a recommended claim. Four-eyes: the assessor may not approve their own recommendation. The payout
     * defaults to the full cover; a partial payout must be more than zero and no more than the cover. A failed hard
     * check blocks it; a failed soft check needs {@code overrideReason}, which is kept with the claim.
     */
    @Transactional
    public InsuranceClaim approve(String ref, BigDecimal requestedPayout, String overrideReason, AppUser actor) {
        return approve(ref, requestedPayout, overrideReason, null, null, actor);
    }

    @Transactional
    public InsuranceClaim approve(String ref, BigDecimal requestedPayout, String overrideReason,
            String fourEyesOverrideReason, AppUser actor) {
        return approve(ref, requestedPayout, overrideReason, fourEyesOverrideReason, null, actor);
    }

    /**
     * Guard order (spec 10 §2.3, 06 §8.1): legal from this state, permission, four-eyes, then a verified payout
     * account, the payout amount, and the approver's authority limit (04 §5.3). Mandatory evidence that is not yet on
     * file does not block approval, but needs {@code evidenceOverrideReason}, which is kept with the claim
     * (NS-SH05-003; whether it should block is for the insurer to decide).
     */
    @Transactional
    public InsuranceClaim approve(String ref, BigDecimal requestedPayout, String overrideReason,
            String fourEyesOverrideReason, String evidenceOverrideReason, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "approve", actor);
        String fourEyesReason = trim(fourEyesOverrideReason);
        if (fourEyesReason.length() > 500) {
            throw new IllegalArgumentException("Keep the four-eyes override reason to 500 characters.");
        }
        FourEyesControl.Outcome separation =
                fourEyes.assertClaimApproval(claim.getRecommendedById(), actor, fourEyesReason);
        requireCover(claim, "approve");
        List<String> softFailed = failedSoftChecks(claim);
        String reason = trim(overrideReason);
        if (!softFailed.isEmpty() && reason.isEmpty()) {
            throw new IllegalArgumentException(String.join(" and ", softFailed) + " failed on " + ref
                    + ". Enter a reason for approving it anyway; the reason is kept with the claim.");
        }
        if (reason.length() > 500) {
            throw new IllegalArgumentException("Keep the reason to 500 characters.");
        }
        claim.setCheckOverrideReason(softFailed.isEmpty() ? null : reason);
        boolean fourEyesOverride = separation == FourEyesControl.Outcome.OVERRIDDEN;
        claim.setFourEyesApplied(separation.applied());
        claim.setFourEyesOverrideReason(fourEyesOverride ? fourEyesReason : null);
        if (!hasPayoutDetails(claim)) {
            throw new IllegalArgumentException("This claim has no complete payout details. Add the payee's name, "
                    + "payout method and account / wallet number on the claim first.");
        }
        if (!claim.isPayoutVerified()) {
            throw new IllegalArgumentException(
                    "Please verify the payee's payout details against the claim documents before approving.");
        }
        BigDecimal payout = requestedPayout == null ? claim.getAmount() : requestedPayout;
        if (payout.signum() <= 0) {
            throw new IllegalArgumentException("Payout amount must be greater than zero");
        }
        if (payout.compareTo(claim.getAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Payout of " + Money.display(payout) + " exceeds the policy cover of " + Money.display(claim.getAmount()));
        }
        approvalLimits.assertWithinLimit(actor, payout);
        List<String> missing = financials.missingMandatory(claim);
        String evidenceReason = trim(evidenceOverrideReason);
        if (!missing.isEmpty() && evidenceReason.isEmpty()) {
            throw new IllegalArgumentException("Required evidence is not yet on file for " + ref + ": "
                    + String.join(", ", missing) + ". Enter a reason for approving without it; the reason is kept with"
                    + " the claim.");
        }
        if (evidenceReason.length() > 500) {
            throw new IllegalArgumentException("Keep the reason for approving without the evidence to 500 characters.");
        }
        claim.setEvidenceOverrideReason(missing.isEmpty() ? null : evidenceReason);
        financials.reserveLimit(claim, policies.policy(claim.getPolicyRef()), payout, actor.getUsername());
        claim.setPayoutAmount(payout);
        financials.reserve(claim, payout, "Set to the approved payout", actor.getUsername());
        claim.setStatus(APPROVED);
        claim.resolve(actor.getUsername());
        String decisionReason = softFailed.isEmpty() ? null : reason;
        if (!missing.isEmpty()) {
            String withoutEvidence = "Approved without " + String.join(", ", missing) + ": " + evidenceReason;
            decisionReason = decisionReason == null ? withoutEvidence : decisionReason + " · " + withoutEvidence;
        }
        recordDecision(claim, ClaimDecision.APPROVED, decisionReason, payout, actor.getUsername());
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Approved", previous, APPROVED,
                clip("Payout: " + Money.display(payout) + (softFailed.isEmpty() ? ""
                        : " · approved despite " + String.join(", ", softFailed) + ": " + reason) + switch (separation) {
                            case OVERRIDDEN -> " · FOUR-EYES OVERRIDDEN by the assessor: " + fourEyesReason;
                            case CONTROL_OFF -> " · four-eyes control OFF: separation not enforced";
                            case ENFORCED -> " · four-eyes: assessed by " + claim.getRecommendedBy();
                        } + (missing.isEmpty() ? "" : " · evidence outstanding: " + String.join(", ", missing)
                                + " · approved without it: " + evidenceReason)),
                actor.getUsername());
        publishDecision(Events.CLAIM_APPROVED, claim, null, actor);
        return claim;
    }

    @Transactional
    public InsuranceClaim reject(String ref, String reason, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "reject", actor);
        String text = trim(reason);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Please enter a reason for rejecting this claim. The customer is told why.");
        }
        if (text.length() > 500) {
            throw new IllegalArgumentException("Keep the reason to 500 characters.");
        }
        claim.setRejectionReason(text);
        claim.setStatus(REJECTED);
        claim.resolve(actor.getUsername());
        recordDecision(claim, ClaimDecision.REJECTED, text, null, actor.getUsername());
        financials.reserve(claim, BigDecimal.ZERO, "Released: claim rejected", actor.getUsername());
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Rejected", previous, REJECTED,
                "Reason: " + text, actor.getUsername());
        publishDecision(Events.CLAIM_REJECTED, claim, text, actor);
        return claim;
    }

    /**
     * Sends the payout instruction for an approved claim (or resends one that failed) to the payout rail. The claim
     * waits in Payout Sent: nothing is paid, posted or reinsured until the provider confirms settlement.
     */
    @Transactional
    public InsuranceClaim pay(String ref, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "pay", actor);
        if (!hasPayoutDetails(claim)) {
            throw new IllegalArgumentException("This claim has no payout details to pay to.");
        }
        if (PAYOUT_FAILED.equals(previous) && !claim.isPayoutVerified()) {
            throw new IllegalArgumentException("The last payout failed. Correct and verify the payee's payout details"
                    + " before sending it again.");
        }
        String reference = "PAYOUT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        claim.sendPayout(reference);
        claim.setStatus(PAYOUT_SENT);
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(),
                PAYOUT_FAILED.equals(previous) ? "Payout Resent" : "Payout Sent", previous, PAYOUT_SENT,
                Money.display(claim.getPayoutAmount()) + " to " + claim.getPayeeName() + " by "
                        + claim.getPayeePayoutMethod() + " " + mask(claim.getPayeeAccountRef()) + " · " + reference
                        + " · awaiting the provider's confirmation",
                actor.getUsername());
        return claim;
    }

    /**
     * A rejected claim is reviewed again (NS-SH05-004): a senior decision with a written reason. The earlier
     * rejection stays in the history; the case reserve is re-established and the checks run again.
     */
    @Transactional
    public InsuranceClaim reopen(String ref, String reason, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "reopen", actor);
        String text = trim(reason);
        if (text.length() < 10) {
            throw new IllegalArgumentException("Give the reason for reopening this claim (at least 10 characters).");
        }
        if (text.length() > 500) {
            throw new IllegalArgumentException("Keep the reason to 500 characters.");
        }
        // The decision being set aside is kept in full (§09: reopening preserves the prior outcome), with the
        // reopening recorded against it, before the claim row is cleared for the new review.
        ClaimDecision setAside = decisions(ref).stream().filter(d -> d.getSupersededBy() == null)
                .reduce((first, second) -> second).orElse(null);
        ClaimDecision reopening = recordDecision(claim, ClaimDecision.REOPENED, text, null, actor.getUsername());
        if (setAside != null) {
            setAside.supersededBy(reopening.getSequenceNo());
        }
        claim.reopen();
        claim.setStatus(UNDER_REVIEW);
        financials.reserve(claim, claim.getAmount(), "Re-established on reopening", actor.getUsername());
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Reopened", previous, UNDER_REVIEW,
                "Reason: " + text + (setAside == null ? "" : " · setting aside the " + setAside.getDecision().toLowerCase(Locale.ROOT)
                        + " of " + setAside.getDecidedAt().toLocalDate() + " by " + setAside.getDecidedBy()
                        + (setAside.getReason() == null ? "" : ": " + setAside.getReason())), actor.getUsername());
        return claim;
    }

    /** Every decision ever made on the claim, oldest first; a superseded one names the decision that set it aside. */
    public List<ClaimDecision> decisions(String claimRef) {
        return em.createQuery("select d from ClaimDecision d where d.claimRef = :ref order by d.sequenceNo",
                ClaimDecision.class).setParameter("ref", claimRef).getResultList();
    }

    private ClaimDecision recordDecision(InsuranceClaim claim, String decision, String reason, BigDecimal amount,
            String actor) {
        int next = decisions(claim.getRef()).stream().mapToInt(ClaimDecision::getSequenceNo).max().orElse(0) + 1;
        ClaimDecision row = new ClaimDecision(claim.getRef(), next, decision, reason, amount, actor);
        em.persist(row);
        return row;
    }

    /**
     * The covered life a claim is for (NS-SH05-001): by its id or name on the policy, defaulting to the policyholder.
     * A claim for another covered life pays that life's cover.
     */
    private void coveredLife(InsuranceClaim claim, InsurancePolicy policy, String requested) {
        String wanted = trim(requested);
        if (wanted.isEmpty()) {
            claim.coveredLife(null, policy.getCustomerName());
            return;
        }
        for (PolicyLifeCover life : financials.lives(policy)) {
            if (String.valueOf(life.getId()).equals(wanted) || wanted.equalsIgnoreCase(trim(life.getFullName()))) {
                claim.coveredLife(life.getId(), life.getFullName());
                if (life.getCoverAmount() != null && life.getCoverAmount().signum() > 0) {
                    claim.setAmount(life.getCoverAmount());
                }
                return;
            }
        }
        throw new IllegalArgumentException("Nobody called " + wanted + " is covered by policy " + policy.getRef() + ".");
    }

    /** The provider or bank failed or returned the payout: the claim waits for corrected, re-verified details. */
    @Transactional
    public InsuranceClaim payoutFailed(String ref, String reason, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "payout-failed", actor);
        String text = trim(reason);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Give the reason the provider gave for the failed or returned payout.");
        }
        if (text.length() > 500) {
            throw new IllegalArgumentException("The reason is too long.");
        }
        claim.clearPayoutVerification();
        claim.setStatus(PAYOUT_FAILED);
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Payout Failed", previous, PAYOUT_FAILED,
                claim.getPayoutReference() + " · " + text + " · payout details need verifying again",
                actor.getUsername());
        return claim;
    }

    /**
     * The provider confirms the payout settled: only now is the claim Paid and ClaimPaid raised. A confirmation that
     * names another payout reference is refused, so an old instruction cannot settle a resent one.
     */
    @Transactional
    public InsuranceClaim confirmPayout(String ref, String providerReference, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "confirm-payout", actor);
        String confirmed = trim(providerReference);
        if (!confirmed.isEmpty() && !confirmed.equals(claim.getPayoutReference())) {
            throw new IllegalArgumentException("This confirmation is for " + confirmed + ", but the payout in flight"
                    + " on " + ref + " is " + claim.getPayoutReference() + ".");
        }
        String reference = claim.getPayoutReference();
        claim.confirmPayout();
        financials.consume(claim, actor.getUsername());
        financials.reserve(claim, BigDecimal.ZERO, "Released: payout settled", actor.getUsername());
        claim.setStatus(PAID);
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Paid", previous, PAID,
                Money.display(claim.getPayoutAmount()) + " to " + claim.getPayeeName() + " by "
                        + claim.getPayeePayoutMethod() + " " + mask(claim.getPayeeAccountRef()) + " · " + reference
                        + " · settlement confirmed",
                actor.getUsername());
        Map<String, Object> paid = new LinkedHashMap<>();
        paid.put("claimRef", ref);
        paid.put("policyRef", claim.getPolicyRef());
        paid.put("customerName", claim.getCustomerName());
        paid.put("product", claim.getProduct());
        paid.put("amount", claim.getPayoutAmount().toPlainString());
        paid.put("payoutRef", reference);
        outbox.publish(Events.CLAIM_PAID, ref, paid, actor.getUsername());
        if (claim.getPolicyRef() != null) {
            policies.terminateIfClaimEndsCover(policies.policy(claim.getPolicyRef()), claim, actor.getUsername());
        }
        return claim;
    }

    InsuranceClaim claim(String ref) {
        return em.createQuery("select c from InsuranceClaim c where c.ref = :ref", InsuranceClaim.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Claim " + ref + " not found"));
    }

    /** The guard order of the demo server's state machine: known action, legal from here, then permission. */
    private String guard(InsuranceClaim claim, String action, AppUser actor) {
        Transition transition = TRANSITIONS.get(action);
        com.nsure.common.ExpectedVersion.check("Claim " + claim.getRef(), claim.getRowVersion());
        String state = claim.getStatus();
        if (!transition.from().contains(state)) {
            List<String> allowed = allowedActions(state);
            throw new IllegalArgumentException("Cannot " + action + " a claim that is " + state
                    + ". Allowed from here: " + (allowed.isEmpty() ? "nothing" : String.join(", ", allowed)));
        }
        actor.validateHasPermissionTo(transition.permission());
        return state;
    }

    // ── the five checks (demo server claim_validator / stp_checks DEFAULT_CHECKS) ──────────────────

    private boolean runChecks(InsuranceClaim claim, InsurancePolicy policy) {
        LocalDate event;
        try {
            event = LocalDate.parse(trim(claim.getEventDate()));
        } catch (DateTimeParseException e) {
            // Claims recorded before the workflow existed may have no event date to check against.
            throw new IllegalArgumentException(claim.getRef() + " has no valid date of the event, so its checks cannot run.");
        }
        LocalDate start = policy.getCoverStartDate();
        // 1. Policy Active: cover was in force on the date of the event (NS-SH05-002, AT-SH05-01). It is judged on
        //    the event date, not on the policy's status today, so a claim reported after a lapse or cancellation is
        //    assessed on the cover that applied when the event happened.
        boolean active = coverInForceOn(policy, event);
        // 2. Waiting Period: the event is at least the product's waiting period after cover started.
        boolean waited = start != null
                && ChronoUnit.DAYS.between(start, event) >= policies.terms(policy).getWaitingPeriodDays();
        // 3. Premiums Paid: the event is on or after the cover start (premiums paid before then bought no cover
        //    for it), and the policy was paid up to it or the event fell within the grace period.
        boolean paid = start != null && !event.isBefore(start)
                && ((policy.getNextPremiumDue() != null && !policy.getNextPremiumDue().isBefore(event))
                        || (policy.getGracePeriodEnd() != null && !policy.getGracePeriodEnd().isBefore(event)));
        // 4. Beneficiary Match: the claimant was a beneficiary registered on the policy on the event date
        //    (NS-SH05-002): someone added after the event does not match, someone removed after it still does.
        String claimant = normalise(claim.getClaimantName());
        boolean beneficiary = policies.beneficiariesOn(policy, event).stream()
                .anyMatch(row -> normalise(row.getFullName()).equals(claimant));
        // 5. No Duplicate: no earlier claim, not rejected, on this policy for the same event (same date and claim
        //    type). Two hospital stays on one policy are two events, so both pass; a later duplicate never makes
        //    the earlier claim fail.
        boolean unique = em.createQuery(
                        "select count(c) from InsuranceClaim c where c.policyRef = :policy and c.status <> :rejected"
                                + " and c.eventDate = :event and c.claimType = :type and c.id < :id", Long.class)
                .setParameter("policy", policy.getRef())
                .setParameter("rejected", REJECTED)
                .setParameter("event", claim.getEventDate())
                .setParameter("type", claim.getClaimType())
                .setParameter("id", claim.getId())
                .getSingleResult() == 0;
        claim.setCheckPolicyActive(result(active));
        claim.setCheckWaitingPeriod(result(waited));
        claim.setCheckPremiumsPaid(result(paid));
        claim.setCheckBeneficiaryMatch(result(beneficiary));
        claim.setCheckNoDuplicate(result(unique));
        claim.setCheckRulesVersion(CHECK_RULES_VERSION);
        return active && waited && paid && beneficiary && unique;
    }

    /**
     * Whether cover was in force on {@code event}: cover had started by then, and no change that ends cover
     * (cancelled, lapsed, no cover, expired, claimed) is recorded before that day, unless a later change before the
     * event restored it. Cover that ends on a day still covers events that day; cover restored on a day covers events
     * from that day. Policies with no recorded changes fall back to having started cover.
     */
    private boolean coverInForceOn(InsurancePolicy policy, LocalDate event) {
        LocalDate start = policy.getCoverStartDate();
        if (start == null || start.isAfter(event)) {
            return false;
        }
        // NS-DAT-002: the state in force on the event date is the last change *effective* on or before it, whenever
        // it was recorded. A cover end recorded late (a returned premium noticed weeks after grace ran out) still
        // applies from the day it took effect, and a reinstatement after the event does not reach back to it.
        List<WorkflowLog> changes = new ArrayList<>(em.createQuery("select h from WorkflowLog h where h.entityType = :type"
                        + " and h.entityRef = :ref", WorkflowLog.class)
                .setParameter("type", WorkflowHistory.POLICY)
                .setParameter("ref", policy.getRef())
                .getResultList());
        changes.sort(java.util.Comparator.comparing(WorkflowLog::getEffectiveDate)
                .thenComparing(WorkflowLog::getPerformedAt).thenComparing(WorkflowLog::getId));
        String last = null;
        for (WorkflowLog change : changes) {
            String to = change.getNewState();
            if (to == null || to.equals(change.getPreviousState()) || change.getPerformedAt() == null) {
                continue;
            }
            if (!change.getEffectiveDate().isAfter(event)) {
                last = to;
            }
        }
        return last == null || PolicyWorkflow.ACTIVE.equals(last);
    }

    /** Checks that decide whether there was valid cover for the event. A failure cannot be overridden. */
    static final List<String> HARD_CHECKS = List.of("Policy Active", "Waiting Period", "Premiums Paid");
    /** Checks with real exceptions; a claim may be approved despite a failure, with a recorded reason. */
    static final List<String> SOFT_CHECKS = List.of("Beneficiary Match", "No Duplicate");

    /** The hard checks that stop this claim being recommended or approved: failed, or never run. */
    static List<String> blockingChecks(InsuranceClaim claim) {
        Map<String, String> results = results(claim);
        return HARD_CHECKS.stream().filter(name -> !PASS.equals(results.get(name))).toList();
    }

    /** The soft checks that failed, so approving needs a reason. */
    static List<String> failedSoftChecks(InsuranceClaim claim) {
        Map<String, String> results = results(claim);
        return SOFT_CHECKS.stream().filter(name -> FAIL.equals(results.get(name))).toList();
    }

    /**
     * Why an approved or paid claim needs an audit: it went through although a hard check failed, or with no
     * checks run at all. Null otherwise (a soft-check override with its reason is the rule working, not a flag).
     */
    static String auditFlag(InsuranceClaim claim) {
        if (!APPROVED.equals(claim.getStatus()) && !PAID.equals(claim.getStatus())) {
            return null;
        }
        Map<String, String> results = results(claim);
        String done = PAID.equals(claim.getStatus()) ? "Paid" : "Approved";
        if (results.values().stream().allMatch(r -> NOT_RUN.equals(r) || "Pending".equals(r))) {
            return done + " without checks";
        }
        List<String> failed = HARD_CHECKS.stream().filter(name -> FAIL.equals(results.get(name))).toList();
        return failed.isEmpty() ? null : done + " despite failed checks: " + String.join(", ", failed);
    }

    /** Refuses to move a claim towards payout while a hard check has failed or not run. */
    private static void requireCover(InsuranceClaim claim, String action) {
        List<String> blocking = blockingChecks(claim);
        if (blocking.isEmpty()) {
            return;
        }
        Map<String, String> results = results(claim);
        if (blocking.stream().allMatch(name -> "Pending".equals(results.get(name)))) {
            throw new IllegalArgumentException("Cannot " + action + " " + claim.getRef() + ": its checks have not "
                    + "run. Re-run the checks first.");
        }
        throw new IllegalArgumentException("Cannot " + action + " " + claim.getRef() + ": " + String.join(", ", blocking)
                + " failed, so there was no valid cover for this event and the "
                + "claim can only be rejected. If the policy or claim details were wrong, correct them and re-run the checks.");
    }

    /**
     * The check rules in force; bump it when a check changes so stored results are re-run once (ClaimCheckRefresh).
     * 2: Premiums Paid fails an event before cover started; No Duplicate means an earlier claim for the same event.
     */
    static final int CHECK_RULES_VERSION = 2;

    /** A check with no result on a decided claim: it never ran, and now never will. */
    static final String NOT_RUN = "Not run";

    /**
     * Each check's result. One that has not run is "Pending" while the claim is open, and "Not run" once it is
     * decided (claims recorded before the checks existed, with no event date to check against).
     */
    static Map<String, String> results(InsuranceClaim claim) {
        String none = claim.getStatus() == null || OPEN_STATES.contains(claim.getStatus()) ? "Pending" : NOT_RUN;
        Map<String, String> results = new LinkedHashMap<>();
        results.put("Policy Active", orElse(claim.getCheckPolicyActive(), none));
        results.put("Waiting Period", orElse(claim.getCheckWaitingPeriod(), none));
        results.put("Premiums Paid", orElse(claim.getCheckPremiumsPaid(), none));
        results.put("Beneficiary Match", orElse(claim.getCheckBeneficiaryMatch(), none));
        results.put("No Duplicate", orElse(claim.getCheckNoDuplicate(), none));
        return results;
    }

    /**
     * Re-runs the checks on a claim whose stored results came from older check rules, in any state, and records
     * what changed. Never moves the claim: a decided claim keeps its decision, and an audit flag shows if the
     * corrected results say it should not have gone through. Returns the changes, or nothing if there was nothing
     * to re-run (no event date or no policy).
     */
    @Transactional
    public List<String> refreshChecks(Long claimId) {
        InsuranceClaim claim = em.find(InsuranceClaim.class, claimId);
        if (claim == null || claim.getCheckRulesVersion() >= CHECK_RULES_VERSION
                || trim(claim.getEventDate()).isEmpty() || trim(claim.getPolicyRef()).isEmpty()) {
            return List.of();
        }
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref", InsurancePolicy.class)
                .setParameter("ref", claim.getPolicyRef())
                .getResultStream()
                .findFirst()
                .orElse(null);
        if (policy == null) {
            return List.of();
        }
        Map<String, String> before = results(claim);
        runChecks(claim, policy);
        Map<String, String> after = results(claim);
        List<String> changed = new ArrayList<>();
        after.forEach((name, result) -> {
            if (!result.equals(before.get(name))) {
                changed.add(name + ": " + before.get(name) + " → " + result);
            }
        });
        history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Checks Re-run",
                claim.getStatus(), claim.getStatus(), "Check rules updated · "
                        + (changed.isEmpty() ? "no change" : String.join("; ", changed)), WorkflowHistory.SYSTEM);
        return changed;
    }

    /** Claims whose stored check results came from older rules and can be re-run, for ClaimCheckRefresh. */
    @Transactional(readOnly = true)
    public List<Long> claimsWithOldCheckResults() {
        return em.createQuery("select c.id from InsuranceClaim c where c.checkRulesVersion < :version"
                        + " and c.eventDate is not null and c.eventDate <> '' and c.policyRef is not null"
                        + " and c.policyRef <> '' order by c.id", Long.class)
                .setParameter("version", CHECK_RULES_VERSION)
                .getResultList();
    }

    private static List<String> failedChecks(InsuranceClaim claim) {
        return results(claim).entrySet().stream()
                .filter(entry -> FAIL.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** The beneficiary named as the claimant, else the primary one, else the first (demo server payee.py). */
    private void payeeFromBeneficiary(InsuranceClaim claim, InsurancePolicy policy) {
        // The beneficiaries designated when the event happened, not any added since (NS-SH05-002).
        List<PolicyBeneficiary> rows = policies.beneficiariesOn(policy, LocalDate.parse(claim.getEventDate()));
        String claimant = normalise(claim.getClaimantName());
        rows.stream().filter(row -> normalise(row.getFullName()).equals(claimant)).findFirst()
                .or(() -> rows.stream().filter(PolicyBeneficiary::isPrimary).findFirst())
                .or(() -> rows.stream().findFirst())
                .ifPresent(row -> {
                    claim.setPayeeName(row.getFullName());
                    claim.setPayeeRelationship(row.getRelationship());
                    claim.setPayeeMobile(row.getMobile());
                    claim.setPayeePayoutMethod(row.getPayoutMethod());
                    claim.setPayeeAccountRef(row.getAccountRef());
                    claim.setPayeeSource("Registered Beneficiary");
                });
    }

    static boolean hasPayoutDetails(InsuranceClaim claim) {
        return !trim(claim.getPayeeName()).isEmpty() && !trim(claim.getPayeePayoutMethod()).isEmpty()
                && !trim(claim.getPayeeAccountRef()).isEmpty();
    }

    private static void requireOpen(InsuranceClaim claim, String message) {
        if (!OPEN_STATES.contains(claim.getStatus())) {
            throw new IllegalArgumentException(message + "; " + claim.getRef() + " is " + claim.getStatus() + ".");
        }
    }

    /** Race-free claim reference (spec 01 BR-59). */
    private String nextRef() {
        return references.next("CLM-", 4, () -> references.highest("InsuranceClaim", "CLM-"));
    }

    private static LocalDate eventDate(String value) {
        LocalDate date;
        try {
            date = LocalDate.parse(required(value, "eventDate"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date of the event must be a date like 2026-04-12.");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The date of the event cannot be in the future");
        }
        return date;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String result(boolean passed) {
        return passed ? PASS : FAIL;
    }

    private static String orElse(String value, String none) {
        return value == null ? none : value;
    }

    private static String normalise(String text) {
        return String.join(" ", trim(text).toLowerCase(Locale.ROOT).split("\\s+"));
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return trim(value).isEmpty() ? null : value.trim();
    }

    /** The workflow log keeps up to 1,000 characters of detail; the full reasons are on the claim. */
    private static String clip(String details) {
        return details.length() <= 1000 ? details : details.substring(0, 999) + "…";
    }

    /** Account numbers in the log show only their last four characters. */
    private static String mask(String account) {
        String value = trim(account);
        return value.length() <= 4 ? value : "•••" + value.substring(value.length() - 4);
    }
}
