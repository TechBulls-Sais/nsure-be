package com.nsure.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SH06 controls around the commission ledger: the commission exception queue (NS-SH06-003), disputed lines
 * (NS-SH06-005), payment batches across intermediaries with a bank file (NS-SH06-006) and versioned agreements with
 * products, clawback period and payment details (NS-SH06-001/002). Each change that moves money or terms is made by
 * one person and approved or released by another.
 */
@Service
public class DistributionControls {

    /** Until a payout provider is contracted (D12) the commission bank file is settled by a simulated bank. */
    public static final String PAYOUT_SIMULATOR = "PAYOUT_SIM";

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final CommissionEventHandler ledger;
    private final DistributionService distribution;
    private final com.nsure.payment.PaymentGateways gateways;

    public DistributionControls(EntityManager em, PlatformSecurityContext security, CommissionEventHandler ledger,
            DistributionService distribution, com.nsure.payment.PaymentGateways gateways) {
        this.em = em;
        this.security = security;
        this.ledger = ledger;
        this.distribution = distribution;
        this.gateways = gateways;
    }

    // ── commission exceptions ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DistributionViews.ExceptionView> exceptions() {
        user("READ_DISTRIBUTION");
        return em.createQuery("select x from CommissionException x order by x.status asc, x.id desc",
                CommissionException.class).setMaxResults(500).getResultStream().map(DistributionControls::view).toList();
    }

    /** Accrues the premium again, after its cause was fixed (assignment, rule, agreement); still failing stays open. */
    @Transactional
    public DistributionViews.ExceptionView retry(String ref) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        CommissionException x = openException(ref);
        CommissionEventHandler.Outcome outcome = ledger.accrue(new CommissionEventHandler.Premium(
                x.getSourceEventId(), x.getPolicyRef(), x.getProduct(), x.getPremiumAmount(), x.getBasis(),
                x.getReceiptRef(), x.getPremiumDate()), null);
        if (outcome.line() != null) {
            x.resolve("Accrued on retry: " + outcome.line().getCalcTrace(), outcome.line().getRef(), user.getUsername());
        } else if (outcome.problemCode() != null) {
            x.stillFailing(outcome.problemCode(), outcome.problem());
        } else {
            x.resolve("Nothing is due on retry: direct business", null, user.getUsername());
        }
        return view(x);
    }

    /** {resolution}: closes the exception without commission (e.g. agreed that none is payable). */
    @Transactional
    public DistributionViews.ExceptionView resolve(String ref, JsonNode body) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        openException(ref).resolve(required(body, "resolution", 10, 500), null, user.getUsername());
        return view(openOrResolved(ref));
    }

    // ── disputes ─────────────────────────────────────────────────────────────────────────────────

    /** {reason}: an accrued line the intermediary disputes is held off statements until resolved. */
    @Transactional
    public DistributionViews.CommissionView dispute(String ref, JsonNode body) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        Commission line = line(ref);
        if (!Commission.ACCRUED.equals(line.getStatus()) || line.getStatement() != null) {
            throw new ConflictException("Line " + ref + " is " + line.getStatus().toLowerCase(Locale.ROOT)
                    + "; only an accrued line not yet on a statement can be disputed.");
        }
        line.dispute(required(body, "reason", 10, 400), user.getUsername());
        return DistributionService.view(line);
    }

    /** {note}: the dispute is settled; the line goes back to accrued (a correction is a new clawback or accrual). */
    @Transactional
    public DistributionViews.CommissionView resolveDispute(String ref, JsonNode body) {
        user("MANAGE_DISTRIBUTION");
        Commission line = line(ref);
        if (!Commission.DISPUTED.equals(line.getStatus())) {
            throw new ConflictException("Line " + ref + " is not disputed.");
        }
        line.undispute(required(body, "note", 5, 90));
        return DistributionService.view(line);
    }

    // ── payment batches ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DistributionViews.PaymentBatchView> batches() {
        user("READ_DISTRIBUTION");
        return em.createQuery("select b from CommissionPaymentBatch b order by b.id desc", CommissionPaymentBatch.class)
                .setMaxResults(100).getResultStream().map(this::view).toList();
    }

    /**
     * Puts every approved statement with a positive net, of an intermediary whose payments are not held, into one
     * batch with a control total and a bank file. Nothing is paid until someone else releases it.
     */
    @Transactional
    public DistributionViews.PaymentBatchView prepareBatch() {
        AppUser user = user("PAY_COMMISSION");
        List<CommissionStatement> ready = new ArrayList<>();
        for (CommissionStatement s : em.createQuery("select s from CommissionStatement s join fetch s.intermediary"
                + " where s.status = :approved and s.batchRef is null and s.netAmount > 0 order by s.id",
                CommissionStatement.class).setParameter("approved", CommissionStatement.APPROVED).getResultList()) {
            try {
                distribution.requirePaymentAllowed(s.getIntermediary());
                ready.add(s);
            } catch (IllegalArgumentException held) {
                // Held statements wait for the next batch once the hold is lifted.
            }
        }
        if (ready.isEmpty()) {
            throw new IllegalArgumentException("No approved statement is waiting for payment.");
        }
        BigDecimal total = ready.stream().map(CommissionStatement::getNetAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        CommissionPaymentBatch batch = new CommissionPaymentBatch(ready.size(), total,
                gateways.simulatorsEnabled() ? PAYOUT_SIMULATOR : "MANUAL", user.getUsername());
        em.persist(batch);
        em.flush();
        batch.numbered();
        ready.forEach(s -> s.batched(batch.getRef()));
        return view(batch);
    }

    /**
     * Releases a prepared batch (someone other than its preparer): each statement is paid (CommissionPaid) and the
     * bank file sent; the simulated bank settles it at once.
     */
    @Transactional
    public DistributionViews.PaymentBatchView releaseBatch(String ref) {
        AppUser user = user("PAY_COMMISSION");
        CommissionPaymentBatch batch = batch(ref);
        if (!CommissionPaymentBatch.PREPARED.equals(batch.getStatus())) {
            throw new ConflictException("Batch " + ref + " is already " + batch.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getUsername().equalsIgnoreCase(batch.getPreparedBy())) {
            throw new NoAuthorizationException("You prepared batch " + ref + "; someone else must release it.");
        }
        for (CommissionStatement s : statements(batch)) {
            distribution.payStatement(s, user.getUsername());
        }
        batch.released(user.getUsername());
        if (PAYOUT_SIMULATOR.equals(batch.getProvider())) {
            batch.settled();
        }
        return view(batch);
    }

    // ── agreements ───────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DistributionViews.AgreementView> agreements() {
        user("READ_DISTRIBUTION");
        return em.createQuery("select a from Agreement a join fetch a.intermediary order by a.intermediary.name,"
                + " a.version desc", Agreement.class).getResultStream().map(DistributionControls::view).toList();
    }

    /**
     * {intermediaryRef, products? (list or comma separated; none = all), channels?, clawbackMonths, bankName?,
     * bankAccount?, bankBranch?, effectiveFrom?, note?}: the next version, a draft until someone else activates it.
     */
    @Transactional
    public DistributionViews.AgreementView draftAgreement(JsonNode body) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        Intermediary intermediary = em.createQuery("select i from Intermediary i where i.ref = :ref", Intermediary.class)
                .setParameter("ref", required(body, "intermediaryRef", 3, 20)).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Intermediary not found"));
        if (!em.createQuery("select a.id from Agreement a where a.intermediary = :i and a.status = :draft", Long.class)
                .setParameter("i", intermediary).setParameter("draft", Agreement.DRAFT).setMaxResults(1).getResultList()
                .isEmpty()) {
            throw new ConflictException(intermediary.getName() + " already has a draft agreement waiting for approval.");
        }
        Set<String> products = new LinkedHashSet<>();
        JsonNode node = body.get("products");
        List<String> raw = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(p -> raw.add(p.asText()));
        } else if (node != null && !node.isNull()) {
            raw.addAll(List.of(node.asText().split(",")));
        }
        List<String> known = em.createQuery("select p.name from InsuranceProduct p", String.class).getResultList();
        for (String value : raw) {
            String product = value.trim();
            if (!product.isEmpty()) {
                products.add(known.stream().filter(k -> k.equalsIgnoreCase(product)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown product " + product + ".")));
            }
        }
        int clawback;
        try {
            clawback = Integer.parseInt(required(body, "clawbackMonths", 1, 3));
        } catch (NumberFormatException ex) {
            clawback = -1;
        }
        if (clawback < 0 || clawback > 60) {
            throw new IllegalArgumentException("clawbackMonths must be a number of months from 0 to 60.");
        }
        LocalDate from = LocalDate.now();
        if (body.hasNonNull("effectiveFrom") && !body.get("effectiveFrom").asText().isBlank()) {
            try {
                from = LocalDate.parse(body.get("effectiveFrom").asText().trim());
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("effectiveFrom must be a date (yyyy-MM-dd).");
            }
        }
        Integer latest = em.createQuery("select max(a.version) from Agreement a where a.intermediary = :i",
                Integer.class).setParameter("i", intermediary).getSingleResult();
        String joined = String.join(",", products);
        if (joined.length() > 500) {
            throw new IllegalArgumentException("Too many products for one agreement.");
        }
        Agreement agreement = new Agreement(intermediary, latest == null ? 1 : latest + 1,
                joined.isEmpty() ? null : joined, optional(body, "channels", 100), clawback,
                optional(body, "bankName", 60), optional(body, "bankAccount", 60), optional(body, "bankBranch", 60),
                from, optional(body, "note", 500), user.getUsername());
        em.persist(agreement);
        em.flush();
        agreement.numbered();
        return view(agreement);
    }

    /** Someone other than its drafter activates the draft; the previous active version is superseded, not edited. */
    @Transactional
    public DistributionViews.AgreementView activateAgreement(String ref) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        Agreement agreement = em.createQuery("select a from Agreement a join fetch a.intermediary where a.ref = :ref",
                        Agreement.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Agreement " + ref + " not found"));
        if (!Agreement.DRAFT.equals(agreement.getStatus())) {
            throw new ConflictException("Agreement " + ref + " is already " + agreement.getStatus().toLowerCase(Locale.ROOT) + ".");
        }
        if (user.getUsername().equalsIgnoreCase(agreement.getCreatedBy())) {
            throw new NoAuthorizationException("You drafted agreement " + ref + "; someone else must activate it.");
        }
        em.createQuery("select a from Agreement a where a.intermediary = :i and a.status = :active", Agreement.class)
                .setParameter("i", agreement.getIntermediary()).setParameter("active", Agreement.ACTIVE)
                .getResultStream().forEach(Agreement::supersede);
        agreement.activate(user.getUsername());
        return view(agreement);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private List<CommissionStatement> statements(CommissionPaymentBatch batch) {
        return em.createQuery("select s from CommissionStatement s join fetch s.intermediary where s.batchRef = :ref"
                + " order by s.id", CommissionStatement.class).setParameter("ref", batch.getRef()).getResultList();
    }

    private DistributionViews.PaymentBatchView view(CommissionPaymentBatch b) {
        List<DistributionViews.BankLine> lines = statements(b).stream().map(s -> {
            Agreement agreement = CommissionEventHandler.activeAgreement(em, s.getIntermediary(), LocalDate.now())
                    .orElse(null);
            return new DistributionViews.BankLine(s.getRef(), s.getIntermediary().getRef(), s.getIntermediary().getName(),
                    agreement == null ? null : agreement.getBankName(),
                    agreement == null ? null : agreement.maskedAccount(),
                    agreement == null ? null : agreement.getBankBranch(), s.getNetAmount(), s.getStatus());
        }).toList();
        return new DistributionViews.PaymentBatchView(b.getRef(), b.getStatus(), b.getStatementCount(),
                b.getTotalAmount(), b.getProvider(), b.getBankFileRef(), b.getPreparedBy(), b.getPreparedAt(),
                b.getReleasedBy(), b.getReleasedAt(), b.getSettledAt(), lines);
    }

    private static DistributionViews.ExceptionView view(CommissionException x) {
        return new DistributionViews.ExceptionView(x.getRef(), x.getPolicyRef(), x.getProduct(), x.getIntermediaryRef(),
                x.getPremiumAmount(), x.getBasis(), x.getReceiptRef(), x.getPremiumDate(), x.getReasonCode(),
                x.getReason(), x.getStatus(), x.getResolvedBy(), x.getResolvedAt(), x.getResolution(),
                x.getCommissionRef(), x.getCreatedAt());
    }

    private static DistributionViews.AgreementView view(Agreement a) {
        return new DistributionViews.AgreementView(a.getRef(), a.getIntermediary().getRef(),
                a.getIntermediary().getName(), a.getVersion(), a.productList(), a.getChannels(), a.getClawbackMonths(),
                a.getBankName(), a.maskedAccount(), a.getBankBranch(), a.getEffectiveFrom(), a.getStatus(), a.getNote(),
                a.getCreatedBy(), a.getCreatedAt(), a.getApprovedBy(), a.getApprovedAt());
    }

    private CommissionException openException(String ref) {
        CommissionException x = openOrResolved(ref);
        if (!CommissionException.OPEN.equals(x.getStatus())) {
            throw new ConflictException("Exception " + ref + " is already resolved.");
        }
        return x;
    }

    private CommissionException openOrResolved(String ref) {
        return em.createQuery("select x from CommissionException x where x.ref = :ref", CommissionException.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Commission exception " + ref + " not found"));
    }

    private Commission line(String ref) {
        return em.createQuery("select c from Commission c join fetch c.intermediary left join fetch c.statement"
                        + " left join fetch c.reverses where c.ref = :ref", Commission.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Commission line " + ref + " not found"));
    }

    private CommissionPaymentBatch batch(String ref) {
        return em.createQuery("select b from CommissionPaymentBatch b where b.ref = :ref", CommissionPaymentBatch.class)
                .setParameter("ref", ref).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Payment batch " + ref + " not found"));
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String required(JsonNode body, String field, int min, int max) {
        String value = body == null || !body.hasNonNull(field) ? "" : body.get(field).asText().trim();
        if (value.length() < min || value.length() > max) {
            throw new IllegalArgumentException(field + " is required (" + min + " to " + max + " characters).");
        }
        return value;
    }

    private static String optional(JsonNode body, String field, int max) {
        if (body == null || !body.hasNonNull(field) || body.get(field).asText().isBlank()) {
            return null;
        }
        String value = body.get(field).asText().trim();
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters.");
        }
        return value;
    }
}
