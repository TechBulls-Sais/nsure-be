package com.nsure.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.ReferenceNumbers;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Controlled refunds (AT-SH10-01, NS-SH04 refund approval): a refund of premium or settlement money is raised with a
 * reason (MANAGE_FINANCE) and paid only when a different person approves it (APPROVE_JOURNAL, the finance checker).
 * The maker is refused as approver whatever roles they hold, the administrator included. Approval settles the refund
 * and raises RefundSettled with who asked and who approved. Modules that pay refunds (premium, credit early settlement,
 * group settlement) raise them through {@link #raise} instead of paying directly.
 *
 * <p>NS-SH04-005: the approver confirms the destination — automatically when it is the account of the payment
 * mandate on file for the policy, otherwise by recording how it was verified — and approval sends a payout
 * instruction with its own never-reused reference. Only the payout rail's confirmation settles the refund; until a
 * payout provider is contracted (D12) the SIMULATED payout rail confirms at once where simulators may run.
 */
@Service
public class RefundControls {

    public record RefundView(String ref, String source, String sourceRef, String policyRef, String payeeName,
            String payoutMethod, String accountRef, BigDecimal amount, String reason, String status,
            String requestedBy, LocalDateTime requestedAt, String decidedBy, LocalDateTime decidedAt,
            String decisionNote, String destinationCheck, String destinationSetBy, String payoutRef,
            String payoutProvider, String payoutReference, LocalDateTime settledAt) {
    }

    /** SUSPENSE refunds are raised and decided from the suspense queue (FinanceControls), never by hand here. */
    static final Set<String> SOURCES = Set.of("PREMIUM", "CREDIT_EARLY_SETTLEMENT", "GROUP_SETTLEMENT", "OTHER",
            "SUSPENSE");
    /** Sources whose refund nothing else posts: RefundSettled carries their amount to the subledger. */
    private static final Set<String> POSTED_HERE = Set.of("PREMIUM", "OTHER");
    public static final String PAYOUT_SIMULATOR = "PAYOUT_SIM";

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;
    private final com.nsure.payment.PaymentGateways gateways;

    public RefundControls(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers,
            com.nsure.payment.PaymentGateways gateways) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
        this.gateways = gateways;
    }

    @Transactional(readOnly = true)
    public List<RefundView> refunds() {
        security.authenticatedUser().validateHasPermissionTo("READ_FINANCE");
        return em.createQuery("select r from RefundRequest r order by r.id desc", RefundRequest.class)
                .setMaxResults(200).getResultStream().map(RefundControls::view).toList();
    }

    /** {source, sourceRef?, policyRef?, payeeName, payoutMethod, accountRef, amount, reason}: raised for approval. */
    @Transactional
    public RefundView request(JsonNode body) {
        AppUser maker = security.authenticatedUser();
        maker.validateHasPermissionTo("MANAGE_FINANCE");
        String source = text(body, "source", "PREMIUM").toUpperCase(Locale.ROOT);
        if ("SUSPENSE".equals(source)) {
            throw new IllegalArgumentException("A refund of money in suspense is asked for from the suspense queue.");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(text(body, "amount", "")).setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new com.nsure.common.exception.InvalidFieldException("amount",
                    "amount must be an amount of money, e.g. 250.00.");
        }
        return view(raise(source, text(body, "sourceRef", null), text(body, "policyRef", null),
                text(body, "payeeName", null), text(body, "payoutMethod", null), text(body, "accountRef", null), amount,
                text(body, "reason", null), maker.getUsername()));
    }

    /** Raises a refund for a second person to approve; for modules that owe a refund. Runs in the caller's transaction. */
    @Transactional
    public RefundRequest raise(String source, String sourceRef, String policyRef, String payee, String method,
            String account, BigDecimal amount, String reason, String maker) {
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("source must be one of " + String.join(", ", SOURCES.stream().sorted().toList()) + ".");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A refund must be more than zero.");
        }
        if (payee == null || method == null || account == null) {
            throw new IllegalArgumentException("Give the payee, the payout method and the account or wallet number.");
        }
        if (reason == null || reason.length() < 10) {
            throw new IllegalArgumentException("Give the reason for the refund (at least 10 characters).");
        }
        if (reason.length() > 500 || payee.length() > 150 || method.length() > 40 || account.length() > 60
                || (sourceRef != null && sourceRef.length() > 60) || (policyRef != null && policyRef.length() > 40)) {
            throw new IllegalArgumentException("The refund details are too long.");
        }
        if (sourceRef != null && !em.createQuery("select r.ref from RefundRequest r where r.source = :source"
                        + " and r.sourceRef = :ref and r.status <> :rejected", String.class)
                .setParameter("source", source).setParameter("ref", sourceRef)
                .setParameter("rejected", RefundRequest.REJECTED).getResultList().isEmpty()) {
            throw new ConflictException("A refund for " + sourceRef + " is already raised.");
        }
        RefundRequest refund = new RefundRequest(numbers.next("RFD-", 6, () -> 0), source, sourceRef, policyRef, payee,
                method, account, amount, reason, maker);
        em.persist(refund);
        return refund;
    }

    /**
     * The checker approves: {note?, verificationNote?}. The destination is verified, a payout instruction is sent and,
     * once the rail confirms it, RefundSettled is raised. Never the person who raised it or recorded its destination.
     */
    @Transactional
    public RefundView approve(String ref, JsonNode body) {
        AppUser checker = checker();
        RefundRequest refund = pending(ref);
        if ("SUSPENSE".equals(refund.getSource())) {
            throw new ConflictException("Refund " + ref + " pays out money in suspense; approve it from the suspense"
                    + " queue.");
        }
        pay(refund, checker, body);
        return view(refund);
    }

    /**
     * {payeeName?, payoutMethod, accountRef}: Finance records where a refund is to be paid, for one the system raised
     * without a known destination (or to correct one) before it is approved. Whoever records it cannot approve it.
     */
    @Transactional
    public RefundView destination(String ref, JsonNode body) {
        AppUser maker = security.authenticatedUser();
        maker.validateHasPermissionTo("MANAGE_FINANCE");
        RefundRequest refund = pending(ref);
        String payee = text(body, "payeeName", refund.getPayeeName());
        String method = text(body, "payoutMethod", null);
        String account = text(body, "accountRef", null);
        if (method == null || account == null || TO_CONFIRM_ACCOUNT(account)) {
            throw new IllegalArgumentException("Give the payout method and the account or wallet number.");
        }
        if (payee.length() > 150 || method.length() > 40 || account.length() > 60) {
            throw new IllegalArgumentException("The refund details are too long.");
        }
        refund.destination(payee, method, account, maker.getUsername());
        return view(refund);
    }

    /**
     * Verifies the destination and pays an approved refund (shared with the suspense queue). The payout instruction
     * reference is new for every refund; RefundSettled is raised when the rail confirms the payout.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    void pay(RefundRequest refund, AppUser checker, JsonNode body) {
        if (checker.getUsername().equalsIgnoreCase(refund.getRequestedBy())) {
            throw new NoAuthorizationException("You raised refund " + refund.getRef() + "; someone else must approve it.");
        }
        if (checker.getUsername().equalsIgnoreCase(refund.getDestinationSetBy())) {
            throw new NoAuthorizationException("You recorded where refund " + refund.getRef()
                    + " is paid; someone else must approve it.");
        }
        if (TO_CONFIRM_ACCOUNT(refund.getPayoutAccount())) {
            throw new IllegalArgumentException("Refund " + refund.getRef() + " has no destination yet: record the"
                    + " payee's account or wallet before approving it.");
        }
        String check = mandateCheck(refund);
        if (check == null) {
            String note = text(body, "verificationNote", null);
            if (note == null || note.length() < 10) {
                throw new IllegalArgumentException("The account " + refund.getPayoutAccount() + " is not a payment"
                        + " mandate on file for this policy. Record how you verified it belongs to "
                        + refund.getPayeeName() + " (verificationNote, at least 10 characters).");
            }
            check = "Verified by " + checker.getUsername() + ": " + (note.length() <= 250 ? note : note.substring(0, 250));
        }
        refund.decide(RefundRequest.PAYOUT_SENT, checker.getUsername(), text(body, "note", null));
        String payoutRef = numbers.next("RPO-", 6, () -> 0);
        boolean simulated = gateways.simulatorsEnabled();
        refund.payoutSent(check, payoutRef, simulated ? PAYOUT_SIMULATOR : "MANUAL",
                simulated ? "SIMPAY-" + payoutRef : null);
        if (simulated) {
            // SIMULATED payout rail: confirms the instruction at once (no payout provider is contracted yet, D12).
            settle(refund, checker.getUsername());
        }
    }

    /**
     * {reference}: the bank confirmed a payout sent outside a simulator (prod without a payout provider). Settles the
     * refund once and raises RefundSettled.
     */
    @Transactional
    public RefundView confirmPayout(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_FINANCE");
        RefundRequest refund = em.createQuery("select r from RefundRequest r where r.ref = :ref", RefundRequest.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Refund " + ref + " not found"));
        if (!RefundRequest.PAYOUT_SENT.equals(refund.getStatus())) {
            throw new ConflictException("Refund " + ref + " is " + refund.getStatus().toLowerCase(Locale.ROOT)
                    + "; only a payout that was sent can be confirmed.");
        }
        String reference = text(body, "reference", null);
        if (reference == null || reference.length() > 60) {
            throw new IllegalArgumentException("Give the bank's payment reference (at most 60 characters).");
        }
        refund.payoutSent(refund.getDestinationCheck(), refund.getPayoutRef(), refund.getPayoutProvider(), reference);
        settle(refund, user.getUsername());
        return view(refund);
    }

    private void settle(RefundRequest refund, String actor) {
        refund.settled();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundRef", refund.getRef());
        data.put("source", refund.getSource());
        data.put("sourceRef", refund.getSourceRef() == null ? "" : refund.getSourceRef());
        data.put("policyRef", refund.getPolicyRef() == null ? "" : refund.getPolicyRef());
        data.put("amount", refund.getAmount().toPlainString());
        // Posted here only when no other event posts this refund (suspense, credit and group refunds post their own).
        data.put("ledgerAmount", POSTED_HERE.contains(refund.getSource()) ? refund.getAmount().toPlainString() : "0");
        data.put("payoutRef", refund.getPayoutRef());
        data.put("payoutReference", refund.getPayoutReference() == null ? "" : refund.getPayoutReference());
        data.put("requestedBy", refund.getRequestedBy());
        data.put("approvedBy", refund.getDecidedBy());
        outbox.publish(Events.REFUND_SETTLED, refund.getRef(), data, actor);
    }

    /** "Matches mandate MDT-…" when the destination is the active payment mandate on file for the refund's policy. */
    private String mandateCheck(RefundRequest refund) {
        if (refund.getPolicyRef() == null) {
            return null;
        }
        String account = refund.getPayoutAccount().replaceAll("\\s", "");
        return em.createQuery("select m from CollectionMandate m where m.activePolicyRef = :ref",
                        com.nsure.collections.CollectionMandate.class)
                .setParameter("ref", refund.getPolicyRef()).getResultStream()
                .filter(m -> m.getAccountNumber().replaceAll("\\s", "").equalsIgnoreCase(account))
                .findFirst()
                .map(m -> "Matches payment mandate " + m.getRef() + " on file (" + m.getInstitution() + " "
                        + m.maskedAccount() + ")")
                .orElse(null);
    }

    private static boolean TO_CONFIRM_ACCOUNT(String account) {
        return account == null || RefundRequest.TO_CONFIRM.equalsIgnoreCase(account.trim());
    }

    /** {note}: the checker turns the refund down, saying why. Never the person who raised it. */
    @Transactional
    public RefundView reject(String ref, JsonNode body) {
        AppUser checker = checker();
        RefundRequest refund = pending(ref);
        if ("SUSPENSE".equals(refund.getSource())) {
            throw new ConflictException("Refund " + ref + " is decided from the suspense queue.");
        }
        if (checker.getUsername().equalsIgnoreCase(refund.getRequestedBy())) {
            throw new NoAuthorizationException("You raised refund " + ref + "; someone else must decide it.");
        }
        String note = text(body, "note", null);
        if (note == null) {
            throw new IllegalArgumentException("Say why the refund is rejected.");
        }
        refund.decide(RefundRequest.REJECTED, checker.getUsername(), note.length() <= 500 ? note : note.substring(0, 500));
        return view(refund);
    }

    private AppUser checker() {
        AppUser checker = security.authenticatedUser();
        checker.validateHasPermissionTo(FinanceControls.APPROVE);
        return checker;
    }

    /** The suspense refund waiting on a suspense receipt, if any. */
    RefundRequest pendingFor(String source, String sourceRef) {
        return em.createQuery("select r from RefundRequest r where r.source = :source and r.sourceRef = :ref"
                        + " and r.status = :pending", RefundRequest.class)
                .setParameter("source", source).setParameter("ref", sourceRef)
                .setParameter("pending", RefundRequest.PENDING).getResultStream().findFirst().orElse(null);
    }

    RefundRequest pending(String ref) {
        RefundRequest refund = em.createQuery("select r from RefundRequest r where r.ref = :ref", RefundRequest.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Refund " + ref + " not found"));
        if (!RefundRequest.PENDING.equals(refund.getStatus())) {
            throw new ConflictException("Refund " + ref + " is already " + refund.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        return refund;
    }

    static RefundView view(RefundRequest r) {
        return new RefundView(r.getRef(), r.getSource(), r.getSourceRef(), r.getPolicyRef(), r.getPayeeName(),
                r.getPayoutMethod(), r.getPayoutAccount(), r.getAmount(), r.getReason(), r.getStatus(),
                r.getRequestedBy(), r.getRequestedAt(), r.getDecidedBy(), r.getDecidedAt(), r.getDecisionNote(),
                r.getDestinationCheck(), r.getDestinationSetBy(), r.getPayoutRef(), r.getPayoutProvider(),
                r.getPayoutReference(), r.getSettledAt());
    }

    private static String text(JsonNode body, String field, String fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        return body.get(field).asText().trim();
    }
}
