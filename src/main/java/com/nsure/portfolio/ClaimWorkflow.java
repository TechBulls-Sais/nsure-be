package com.nsure.portfolio;

import com.nsure.common.exception.NotFoundException;
import com.nsure.controls.ControlKey;
import com.nsure.controls.ControlService;
import com.nsure.controls.FourEyesControl;
import com.nsure.security.domain.AppUser;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 *   INITIATED / UNDER REVIEW ──assess──▶ RECOMMENDED ──approve (four-eyes)──▶ APPROVED ──pay──▶ PAID
 *   INITIATED / UNDER REVIEW / RECOMMENDED ──reject (reason required)──▶ REJECTED
 * </pre>
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
        TRANSITIONS.put("pay", new Transition(Set.of(APPROVED), PAID, "SETTLE_CLAIM"));
    }

    private record Transition(Set<String> from, String to, String permission) {
    }

    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final EntityManager em;
    private final ControlService controls;
    private final FourEyesControl fourEyes;
    private final PolicyWorkflow policies;
    private final WorkflowHistory history;

    public ClaimWorkflow(EntityManager em, ControlService controls, FourEyesControl fourEyes,
            PolicyWorkflow policies, WorkflowHistory history) {
        this.em = em;
        this.controls = controls;
        this.fourEyes = fourEyes;
        this.policies = policies;
        this.history = history;
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
            String claimantRelationship, String eventDate, String description, String claimType) {
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
        claim.setProduct(policy.getProduct());
        claim.setPolicyRef(policy.getRef());
        // A claim pays the policy's cover; an assessor may approve a partial amount.
        claim.setAmount(policy.getAmount());
        claim.setPremium(BigDecimal.ZERO);
        claim.setStatus(INITIATED);
        claim.setRecordDate(LocalDate.now().format(DISPLAY));
        claim.setDisplayOrder(0);
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

        boolean allPassed = runChecks(claim, policy);
        String ineligible = allPassed ? stpIneligibleReason(claim) : null;
        if (allPassed && ineligible == null) {
            claim.verifyPayout(SYSTEM_VERIFIER);
            claim.setStatus(APPROVED);
            claim.setProcessingType(STP);
            claim.setPayoutAmount(claim.getAmount());
            claim.resolve(null);
            history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Auto Approved", INITIATED,
                    APPROVED, "Straight-through: all five checks passed · payout BWP " + claim.getAmount(),
                    WorkflowHistory.SYSTEM);
        } else {
            claim.setStatus(UNDER_REVIEW);
            claim.setProcessingType(MANUAL_REVIEW);
            claim.setStpIneligibleReason(ineligible);
            history.record(WorkflowHistory.CLAIM, claim.getRef(), claim.getCustomerName(), "Review", INITIATED,
                    UNDER_REVIEW, ineligible != null ? ineligible : "Failed: " + String.join(", ", failedChecks(claim)),
                    WorkflowHistory.SYSTEM);
        }
        return claim;
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
        requireOpen(claim, "The payee can only be changed while the claim is open");
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
        if (!hasPayoutDetails(claim)) {
            throw new IllegalArgumentException("This claim has no complete payout details. Add the payee's name, "
                    + "payout method and account / wallet number first.");
        }
        if (!claim.isPayoutVerified()) {
            claim.verifyPayout(actor.getUsername());
        }
        claim.recommend(actor.getUsername(), actor.getId());
        claim.setStatus(RECOMMENDED);
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Assessed", previous, RECOMMENDED,
                "Recommended for payout of BWP " + claim.getAmount() + (trim(note).isEmpty() ? "" : " · " + trim(note)),
                actor.getUsername());
        return claim;
    }

    /**
     * Approves a recommended claim. Four-eyes: the assessor may not approve their own recommendation. The payout
     * defaults to the full cover; a partial payout must be more than zero and no more than the cover.
     */
    @Transactional
    public InsuranceClaim approve(String ref, Integer requestedPayout, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "approve", actor);
        // Super Admin / Super user may approve their own assessment; the history records it as an override.
        boolean fourEyesOverride = actor.isSuperAdmin() && actor.getId().equals(claim.getRecommendedById());
        claim.setFourEyesApplied(fourEyesOverride
                ? Boolean.FALSE
                : fourEyes.assertClaimApproval(claim.getRecommendedById(), actor.getId()));
        if (!hasPayoutDetails(claim)) {
            throw new IllegalArgumentException("This claim has no complete payout details. Add the payee's name, "
                    + "payout method and account / wallet number on the claim first.");
        }
        if (!claim.isPayoutVerified()) {
            throw new IllegalArgumentException(
                    "Please verify the payee's payout details against the claim documents before approving.");
        }
        int payout = requestedPayout == null ? claim.getAmount() : requestedPayout;
        if (payout <= 0) {
            throw new IllegalArgumentException("Payout amount must be greater than zero");
        }
        if (payout > claim.getAmount()) {
            throw new IllegalArgumentException(
                    "Payout of BWP " + payout + " exceeds the policy cover of BWP " + claim.getAmount());
        }
        claim.setPayoutAmount(payout);
        claim.setStatus(APPROVED);
        claim.resolve(actor.getUsername());
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Approved", previous, APPROVED,
                "Payout: BWP " + payout + (fourEyesOverride
                        ? " · four-eyes overridden: super admin approved their own assessment"
                        : Boolean.TRUE.equals(claim.getFourEyesApplied())
                                ? " · four-eyes: assessed by " + claim.getRecommendedBy() : " · four-eyes control off"),
                actor.getUsername());
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
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Rejected", previous, REJECTED,
                "Reason: " + text, actor.getUsername());
        return claim;
    }

    /** Pays an approved claim to its payee (mock payout gateway) and closes it. */
    @Transactional
    public InsuranceClaim pay(String ref, AppUser actor) {
        InsuranceClaim claim = claim(ref);
        String previous = guard(claim, "pay", actor);
        if (!hasPayoutDetails(claim)) {
            throw new IllegalArgumentException("This claim has no payout details to pay to.");
        }
        String reference = "PAYOUT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        claim.pay(reference);
        claim.setStatus(PAID);
        history.record(WorkflowHistory.CLAIM, ref, claim.getCustomerName(), "Paid", previous, PAID,
                "BWP " + claim.getPayoutAmount() + " to " + claim.getPayeeName() + " by "
                        + claim.getPayeePayoutMethod() + " " + mask(claim.getPayeeAccountRef()) + " · " + reference,
                actor.getUsername());
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
        // 1. Policy Active: active, and its cover had started on the date of the event.
        boolean active = PolicyWorkflow.ACTIVE.equals(policy.getStatus()) && start != null && !start.isAfter(event);
        // 2. Waiting Period: the event is at least the product's waiting period after cover started.
        boolean waited = start != null
                && ChronoUnit.DAYS.between(start, event) >= policies.product(policy).getWaitingPeriodDays();
        // 3. Premiums Paid: paid up to the event, or the event fell within the grace period.
        boolean paid = (policy.getNextPremiumDue() != null && !policy.getNextPremiumDue().isBefore(event))
                || (policy.getGracePeriodEnd() != null && !policy.getGracePeriodEnd().isBefore(event));
        // 4. Beneficiary Match: the claimant is a beneficiary registered on the policy.
        String claimant = normalise(claim.getClaimantName());
        boolean beneficiary = policies.beneficiaries(policy).stream()
                .anyMatch(row -> normalise(row.getFullName()).equals(claimant));
        // 5. No Duplicate: no other approved or paid claim on this policy.
        boolean unique = em.createQuery(
                        "select count(c) from InsuranceClaim c where c.policyRef = :policy and c.status in :states"
                                + " and c.ref <> :ref", Long.class)
                .setParameter("policy", policy.getRef())
                .setParameter("states", List.of(APPROVED, PAID))
                .setParameter("ref", claim.getRef())
                .getSingleResult() == 0;
        claim.setCheckPolicyActive(result(active));
        claim.setCheckWaitingPeriod(result(waited));
        claim.setCheckPremiumsPaid(result(paid));
        claim.setCheckBeneficiaryMatch(result(beneficiary));
        claim.setCheckNoDuplicate(result(unique));
        return active && waited && paid && beneficiary && unique;
    }

    /** Null when a claim whose checks all passed may be approved straight through; else why not. */
    private String stpIneligibleReason(InsuranceClaim claim) {
        if (!controls.isEnabled(ControlKey.CLAIM_STP_ENABLED.key())) {
            return "Straight-through processing is switched off";
        }
        BigDecimal ceiling = controls.valueOf(ControlKey.CLAIM_STP_CEILING.key());
        if (BigDecimal.valueOf(claim.getAmount()).compareTo(ceiling) > 0) {
            return "Payout of BWP " + claim.getAmount() + " is over the straight-through ceiling of BWP "
                    + ceiling.stripTrailingZeros().toPlainString();
        }
        if (controls.isEnabled(ControlKey.CLAIM_STP_REQUIRES_VERIFIED_PAYOUT.key()) && !hasPayoutDetails(claim)) {
            return "No registered beneficiary with payout details to pay";
        }
        return null;
    }

    static Map<String, String> results(InsuranceClaim claim) {
        Map<String, String> results = new LinkedHashMap<>();
        results.put("Policy Active", orPending(claim.getCheckPolicyActive()));
        results.put("Waiting Period", orPending(claim.getCheckWaitingPeriod()));
        results.put("Premiums Paid", orPending(claim.getCheckPremiumsPaid()));
        results.put("Beneficiary Match", orPending(claim.getCheckBeneficiaryMatch()));
        results.put("No Duplicate", orPending(claim.getCheckNoDuplicate()));
        return results;
    }

    private static List<String> failedChecks(InsuranceClaim claim) {
        return results(claim).entrySet().stream()
                .filter(entry -> FAIL.equals(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** The beneficiary named as the claimant, else the primary one, else the first (demo server payee.py). */
    private void payeeFromBeneficiary(InsuranceClaim claim, InsurancePolicy policy) {
        List<PolicyBeneficiary> rows = policies.beneficiaries(policy);
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

    private String nextRef() {
        int max = 0;
        for (String ref : em.createQuery("select c.ref from InsuranceClaim c", String.class).getResultList()) {
            max = Math.max(max, Integer.parseInt(ref.replaceAll("[^0-9]", "")));
        }
        return String.format("CLM-%04d", max + 1);
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

    private static String orPending(String value) {
        return value == null ? "Pending" : value;
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

    /** Account numbers in the log show only their last four characters. */
    private static String mask(String account) {
        String value = trim(account);
        return value.length() <= 4 ? value : "•••" + value.substring(value.length() - 4);
    }
}
