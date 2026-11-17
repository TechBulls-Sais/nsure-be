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
 */
@Service
public class RefundControls {

    public record RefundView(String ref, String source, String sourceRef, String policyRef, String payeeName,
            String payoutMethod, String accountRef, BigDecimal amount, String reason, String status,
            String requestedBy, LocalDateTime requestedAt, String decidedBy, LocalDateTime decidedAt,
            String decisionNote) {
    }

    static final Set<String> SOURCES = Set.of("PREMIUM", "CREDIT_EARLY_SETTLEMENT", "GROUP_SETTLEMENT", "OTHER");

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;
    private final ReferenceNumbers numbers;

    public RefundControls(EntityManager em, PlatformSecurityContext security, Outbox outbox, ReferenceNumbers numbers) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
        this.numbers = numbers;
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

    /** The checker approves: the refund is paid and RefundSettled raised. Never the person who raised it. */
    @Transactional
    public RefundView approve(String ref, JsonNode body) {
        AppUser checker = checker();
        RefundRequest refund = pending(ref);
        if (checker.getUsername().equalsIgnoreCase(refund.getRequestedBy())) {
            throw new NoAuthorizationException("You raised refund " + ref + "; someone else must approve it.");
        }
        refund.decide(RefundRequest.SETTLED, checker.getUsername(), text(body, "note", null));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundRef", refund.getRef());
        data.put("source", refund.getSource());
        data.put("sourceRef", refund.getSourceRef() == null ? "" : refund.getSourceRef());
        data.put("policyRef", refund.getPolicyRef() == null ? "" : refund.getPolicyRef());
        data.put("amount", refund.getAmount().toPlainString());
        data.put("requestedBy", refund.getRequestedBy());
        data.put("approvedBy", checker.getUsername());
        outbox.publish(Events.REFUND_SETTLED, refund.getRef(), data, checker.getUsername());
        return view(refund);
    }

    /** {note}: the checker turns the refund down, saying why. Never the person who raised it. */
    @Transactional
    public RefundView reject(String ref, JsonNode body) {
        AppUser checker = checker();
        RefundRequest refund = pending(ref);
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

    private RefundRequest pending(String ref) {
        RefundRequest refund = em.createQuery("select r from RefundRequest r where r.ref = :ref", RefundRequest.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Refund " + ref + " not found"));
        if (!RefundRequest.PENDING.equals(refund.getStatus())) {
            throw new ConflictException("Refund " + ref + " is already " + refund.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        return refund;
    }

    private static RefundView view(RefundRequest r) {
        return new RefundView(r.getRef(), r.getSource(), r.getSourceRef(), r.getPolicyRef(), r.getPayeeName(),
                r.getPayoutMethod(), r.getPayoutAccount(), r.getAmount(), r.getReason(), r.getStatus(),
                r.getRequestedBy(), r.getRequestedAt(), r.getDecidedBy(), r.getDecidedAt(), r.getDecisionNote());
    }

    private static String text(JsonNode body, String field, String fallback) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return fallback;
        }
        return body.get(field).asText().trim();
    }
}
