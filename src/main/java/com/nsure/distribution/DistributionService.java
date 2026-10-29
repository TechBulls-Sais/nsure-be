package com.nsure.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SH06 Distribution and commissions: appointments, commission rules, policy assignments and commission statements.
 * The ledger itself is written by {@link CommissionEventHandler} from premium and cancellation events.
 */
@Service
public class DistributionService {

    private static final Set<String> TYPES = Set.of(Intermediary.AGENT, Intermediary.BROKER);
    private static final Set<String> STATUSES = Set.of(Intermediary.ACTIVE, Intermediary.SUSPENDED, Intermediary.LAPSED);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final Outbox outbox;

    public DistributionService(EntityManager em, PlatformSecurityContext security, Outbox outbox) {
        this.em = em;
        this.security = security;
        this.outbox = outbox;
    }

    // ── intermediaries ────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DistributionViews.IntermediaryView> intermediaries() {
        user("READ_DISTRIBUTION");
        Map<Long, Long> open = new HashMap<>();
        for (Object[] row : em.createQuery("select a.intermediary.id, count(a) from PolicyAssignment a"
                + " where a.openPolicyRef is not null group by a.intermediary.id", Object[].class).getResultList()) {
            open.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, BigDecimal> balance = new HashMap<>();
        for (Object[] row : em.createQuery("select c.intermediary.id, sum(c.amount) from Commission c"
                        + " where c.status = :accrued group by c.intermediary.id", Object[].class)
                .setParameter("accrued", Commission.ACCRUED)
                .getResultList()) {
            balance.put((Long) row[0], (BigDecimal) row[1]);
        }
        return em.createQuery("select i from Intermediary i left join fetch i.parent order by i.name asc",
                        Intermediary.class)
                .getResultList()
                .stream()
                .map(i -> view(i, open.getOrDefault(i.getId(), 0L), balance.getOrDefault(i.getId(), BigDecimal.ZERO)))
                .toList();
    }

    @Transactional
    public DistributionViews.IntermediaryView appoint(JsonNode body) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        String name = required(body, "name", 150);
        String type = upper(required(body, "type", 20));
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Intermediary type must be AGENT or BROKER.");
        }
        String licence = required(body, "licenceNumber", 60);
        requireLicenceFree(licence, null);
        LocalDate expiry = date(body, "appointmentExpiry", true);
        if (expiry.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("The appointment expiry date is already in the past.");
        }
        Intermediary parent = null;
        String parentRef = optional(body, "parentRef", 20);
        if (parentRef != null) {
            parent = intermediary(parentRef);
        }
        Intermediary row = new Intermediary(name, type, licence, expiry, parent, optional(body, "email", 150),
                optional(body, "mobile", 40), user.getUsername());
        em.persist(row);
        em.flush();
        row.numbered();
        return view(row, 0, BigDecimal.ZERO);
    }

    /**
     * Changes an appointment: status (SUSPENDED and LAPSED need a reason), expiry date and contact details. Existing
     * assignments and the commission ledger are kept; a suspended or lapsed intermediary just takes no new business.
     */
    @Transactional
    public DistributionViews.IntermediaryView updateIntermediary(String ref, JsonNode body) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        Intermediary row = intermediary(ref);
        String name = body.hasNonNull("name") ? required(body, "name", 150) : row.getName();
        String licence = body.hasNonNull("licenceNumber") ? required(body, "licenceNumber", 60) : row.getLicenceNumber();
        if (!licence.equals(row.getLicenceNumber())) {
            requireLicenceFree(licence, row.getId());
        }
        LocalDate expiry = body.hasNonNull("appointmentExpiry") ? date(body, "appointmentExpiry", true)
                : row.getAppointmentExpiry();
        String email = body.has("email") ? optional(body, "email", 150) : row.getEmail();
        String mobile = body.has("mobile") ? optional(body, "mobile", 40) : row.getMobile();
        row.update(name, licence, expiry, email, mobile, user.getUsername());
        if (body.hasNonNull("status")) {
            String status = upper(body.get("status").asText());
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("Status must be ACTIVE, SUSPENDED or LAPSED.");
            }
            String reason = optional(body, "reason", 500);
            if (!Intermediary.ACTIVE.equals(status) && reason == null) {
                throw new IllegalArgumentException("Give a reason for " + (Intermediary.SUSPENDED.equals(status)
                        ? "suspending" : "lapsing") + " the appointment.");
            }
            if (!status.equals(row.getStatus()) || reason != null) {
                row.changeStatus(status, reason, user.getUsername());
            }
        }
        return view(row, openAssignments(row), unstatemented(row));
    }

    // ── commission rules ──────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DistributionViews.CommissionRuleView> rules() {
        user("READ_DISTRIBUTION");
        List<CommissionRule> rules = em.createQuery("select r from CommissionRule r"
                + " order by r.product asc, r.intermediaryType asc, r.effectiveFrom desc", CommissionRule.class)
                .getResultList();
        LocalDate today = LocalDate.now();
        Map<String, Long> current = new HashMap<>();
        for (CommissionRule rule : rules) {
            if (!rule.getEffectiveFrom().isAfter(today)) {
                current.putIfAbsent(rule.getProduct() + "|" + rule.getIntermediaryType(), rule.getId());
            }
        }
        return rules.stream()
                .map(r -> new DistributionViews.CommissionRuleView(r.getId(), r.getProduct(), r.getIntermediaryType(),
                        r.getInitialRatePercent(), r.getRenewalRatePercent(), r.getEffectiveFrom(),
                        r.getId().equals(current.get(r.getProduct() + "|" + r.getIntermediaryType())),
                        r.getCreatedBy(), r.getCreatedAt()))
                .toList();
    }

    @Transactional
    public DistributionViews.CommissionRuleView createRule(JsonNode body) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        String product = required(body, "product", 100);
        Long products = em.createQuery("select count(p) from InsuranceProduct p where p.name = :name", Long.class)
                .setParameter("name", product)
                .getSingleResult();
        if (products == 0) {
            throw new IllegalArgumentException("Unknown product " + product + ".");
        }
        String type = upper(required(body, "intermediaryType", 20));
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("Intermediary type must be AGENT or BROKER.");
        }
        BigDecimal initial = percent(body, "initialRatePercent");
        BigDecimal renewal = percent(body, "renewalRatePercent");
        LocalDate from = body.hasNonNull("effectiveFrom") ? date(body, "effectiveFrom", true) : LocalDate.now();
        Long clash = em.createQuery("select count(r) from CommissionRule r where r.product = :product"
                        + " and r.intermediaryType = :type and r.effectiveFrom = :from", Long.class)
                .setParameter("product", product)
                .setParameter("type", type)
                .setParameter("from", from)
                .getSingleResult();
        if (clash > 0) {
            throw new ConflictException("A " + type + " rule for " + product + " already starts on " + from
                    + ". Rules are not edited; add one from a later date.");
        }
        CommissionRule rule = new CommissionRule(product, type, initial, renewal, from, user.getUsername());
        em.persist(rule);
        em.flush();
        CommissionRule inForce = CommissionEventHandler.ruleInForce(em, product, type, LocalDate.now()).orElse(null);
        return new DistributionViews.CommissionRuleView(rule.getId(), product, type, initial, renewal, from,
                inForce != null && inForce.getId().equals(rule.getId()), rule.getCreatedBy(), rule.getCreatedAt());
    }

    // ── policy assignments ────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DistributionViews.AssignmentView> assignments(String policyRef, String intermediaryRef) {
        user("READ_DISTRIBUTION");
        List<PolicyAssignment> rows = em.createQuery("select a from PolicyAssignment a join fetch a.intermediary i"
                        + " where (:policy is null or a.policyRef = :policy) and (:intermediary is null or i.ref = :intermediary)"
                        + " order by a.id desc", PolicyAssignment.class)
                .setParameter("policy", blankToNull(policyRef))
                .setParameter("intermediary", blankToNull(intermediaryRef))
                .getResultList();
        Map<String, InsurancePolicy> policies = policies(rows.stream().map(PolicyAssignment::getPolicyRef)
                .collect(Collectors.toSet()));
        return rows.stream().map(a -> view(a, policies.get(a.getPolicyRef()))).toList();
    }

    /**
     * Assigns a policy to an intermediary from a date (default today). An open assignment for the policy is ended on
     * that date (portfolio transfer, AT-SH06-03). Refused for a suspended, lapsed or expired appointment; nothing
     * already recorded changes (AT-SH06-02).
     */
    @Transactional
    public DistributionViews.AssignmentView assign(JsonNode body) {
        AppUser user = user("MANAGE_DISTRIBUTION");
        String policyRef = required(body, "policyRef", 20);
        InsurancePolicy policy = em.createQuery("select p from InsurancePolicy p where p.ref = :ref",
                        InsurancePolicy.class)
                .setParameter("ref", policyRef)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Policy " + policyRef + " not found"));
        Intermediary intermediary = intermediary(required(body, "intermediaryRef", 20));
        LocalDate from = body.hasNonNull("effectiveFrom") ? date(body, "effectiveFrom", true) : LocalDate.now();
        if (!Intermediary.ACTIVE.equals(intermediary.getStatus())) {
            throw new IllegalArgumentException(intermediary.getName() + "'s appointment is "
                    + intermediary.getStatus().toLowerCase(Locale.ROOT) + "; no new policies can be assigned.");
        }
        if (!intermediary.canTakeBusiness(from)) {
            throw new IllegalArgumentException(intermediary.getName() + "'s appointment expired on "
                    + intermediary.getAppointmentExpiry() + "; no new policies can be assigned.");
        }
        Optional<PolicyAssignment> current = em.createQuery("select a from PolicyAssignment a"
                        + " where a.openPolicyRef = :ref", PolicyAssignment.class)
                .setParameter("ref", policyRef)
                .getResultStream()
                .findFirst();
        if (current.isPresent()) {
            PolicyAssignment old = current.get();
            if (old.getIntermediary().getId().equals(intermediary.getId())) {
                throw new IllegalArgumentException("Policy " + policyRef + " is already assigned to "
                        + intermediary.getName() + ".");
            }
            if (from.isBefore(old.getEffectiveFrom())) {
                throw new IllegalArgumentException("A transfer cannot start before the current assignment ("
                        + old.getEffectiveFrom() + ").");
            }
            old.end(from);
            em.flush();
        }
        PolicyAssignment row = new PolicyAssignment(policyRef, intermediary, from, user.getUsername());
        em.persist(row);
        return view(row, policy);
    }

    // ── commission ledger and statements ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DistributionViews.CommissionView> commissions(String intermediaryRef, String policyRef, String status) {
        user("READ_DISTRIBUTION");
        return em.createQuery("select c from Commission c join fetch c.intermediary i left join fetch c.statement"
                        + " left join fetch c.reverses"
                        + " where (:intermediary is null or i.ref = :intermediary)"
                        + " and (:policy is null or c.policyRef = :policy)"
                        + " and (:status is null or c.status = :status) order by c.id desc", Commission.class)
                .setParameter("intermediary", blankToNull(intermediaryRef))
                .setParameter("policy", blankToNull(policyRef))
                .setParameter("status", status == null || status.isBlank() ? null : upper(status))
                .getResultList()
                .stream()
                .map(DistributionService::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DistributionViews.StatementView> statements() {
        user("READ_DISTRIBUTION");
        List<CommissionStatement> rows = em.createQuery("select s from CommissionStatement s"
                + " join fetch s.intermediary order by s.id desc", CommissionStatement.class).getResultList();
        Map<Long, List<DistributionViews.CommissionView>> lines = rows.isEmpty() ? Map.of()
                : em.createQuery("select c from Commission c join fetch c.intermediary join fetch c.statement s"
                                + " left join fetch c.reverses where s.id in :ids order by c.id asc", Commission.class)
                        .setParameter("ids", rows.stream().map(CommissionStatement::getId).toList())
                        .getResultList()
                        .stream()
                        .collect(Collectors.groupingBy(c -> c.getStatement().getId(), LinkedHashMap::new,
                                Collectors.mapping(DistributionService::view, Collectors.toList())));
        return rows.stream().map(s -> view(s, lines.getOrDefault(s.getId(), List.of()))).toList();
    }

    /** Puts every accrued line of one intermediary (clawbacks included) on one net statement. */
    @Transactional
    public DistributionViews.StatementView issueStatement(JsonNode body) {
        AppUser user = user("PAY_COMMISSION");
        Intermediary intermediary = intermediary(required(body, "intermediaryRef", 20));
        List<Commission> lines = em.createQuery("select c from Commission c where c.intermediary = :intermediary"
                        + " and c.status = :accrued and c.statement is null order by c.id asc", Commission.class)
                .setParameter("intermediary", intermediary)
                .setParameter("accrued", Commission.ACCRUED)
                .getResultList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException(intermediary.getName() + " has no accrued commission to put on a statement.");
        }
        BigDecimal net = lines.stream().map(Commission::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        CommissionStatement statement = new CommissionStatement(intermediary, net, lines.size(), user.getUsername());
        em.persist(statement);
        em.flush();
        statement.numbered();
        lines.forEach(line -> line.statemented(statement));
        return view(statement, lines.stream().map(DistributionService::view).toList());
    }

    /** Pays an issued statement once; a paid statement is never changed and a negative net is recovered, not paid. */
    @Transactional
    public DistributionViews.StatementView payStatement(String ref) {
        AppUser user = user("PAY_COMMISSION");
        CommissionStatement statement = em.createQuery("select s from CommissionStatement s join fetch s.intermediary"
                        + " where s.ref = :ref", CommissionStatement.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Statement " + ref + " not found"));
        if (CommissionStatement.PAID.equals(statement.getStatus())) {
            throw new ConflictException("Statement " + ref + " is already paid and cannot be changed.");
        }
        if (statement.getNetAmount().signum() < 0) {
            throw new IllegalArgumentException("Statement " + ref + " nets to " + statement.getNetAmount().toPlainString()
                    + ": the intermediary owes this back, so it cannot be paid.");
        }
        List<Commission> lines = em.createQuery("select c from Commission c where c.statement = :statement"
                        + " order by c.id asc", Commission.class)
                .setParameter("statement", statement)
                .getResultList();
        lines.forEach(Commission::paid);
        statement.paid(user.getUsername());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("statementRef", statement.getRef());
        data.put("intermediaryRef", statement.getIntermediary().getRef());
        data.put("amount", statement.getNetAmount().toPlainString());
        outbox.publish(Events.COMMISSION_PAID, statement.getRef(), data, user.getUsername());
        return view(statement, lines.stream().map(DistributionService::view).toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private Intermediary intermediary(String ref) {
        return em.createQuery("select i from Intermediary i where i.ref = :ref", Intermediary.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Intermediary " + ref + " not found"));
    }

    private void requireLicenceFree(String licence, Long except) {
        Long count = em.createQuery("select count(i) from Intermediary i where i.licenceNumber = :licence"
                        + " and (:except is null or i.id <> :except)", Long.class)
                .setParameter("licence", licence)
                .setParameter("except", except)
                .getSingleResult();
        if (count > 0) {
            throw new ConflictException("Licence number " + licence + " is already registered to another intermediary.");
        }
    }

    private long openAssignments(Intermediary row) {
        return em.createQuery("select count(a) from PolicyAssignment a where a.intermediary = :i"
                        + " and a.openPolicyRef is not null", Long.class)
                .setParameter("i", row)
                .getSingleResult();
    }

    private BigDecimal unstatemented(Intermediary row) {
        BigDecimal sum = em.createQuery("select sum(c.amount) from Commission c where c.intermediary = :i"
                        + " and c.status = :accrued", BigDecimal.class)
                .setParameter("i", row)
                .setParameter("accrued", Commission.ACCRUED)
                .getSingleResult();
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private Map<String, InsurancePolicy> policies(Set<String> refs) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("select p from InsurancePolicy p where p.ref in :refs", InsurancePolicy.class)
                .setParameter("refs", refs)
                .getResultList()
                .stream()
                .collect(Collectors.toMap(InsurancePolicy::getRef, p -> p, (a, b) -> a));
    }

    private static DistributionViews.IntermediaryView view(Intermediary i, long open, BigDecimal balance) {
        Intermediary parent = i.getParent();
        return new DistributionViews.IntermediaryView(i.getRef(), i.getName(), i.getType(), i.getLicenceNumber(),
                i.getStatus(), i.getStatusReason(), i.getAppointmentExpiry(), i.canTakeBusiness(LocalDate.now()),
                parent == null ? null : parent.getRef(), parent == null ? null : parent.getName(), i.getEmail(),
                i.getMobile(), open, balance, i.getCreatedBy(), i.getCreatedAt(), i.getUpdatedBy(), i.getUpdatedAt());
    }

    private static DistributionViews.AssignmentView view(PolicyAssignment a, InsurancePolicy policy) {
        Intermediary i = a.getIntermediary();
        return new DistributionViews.AssignmentView(a.getId(), a.getPolicyRef(),
                policy == null ? null : policy.getCustomerName(), policy == null ? null : policy.getProduct(),
                i.getRef(), i.getName(), i.getType(), a.getEffectiveFrom(), a.getEndedOn(), a.isOpen(),
                a.getAssignedBy(), a.getAssignedAt());
    }

    static DistributionViews.CommissionView view(Commission c) {
        return new DistributionViews.CommissionView(c.getRef(), c.getIntermediary().getRef(),
                c.getIntermediary().getName(), c.getPolicyRef(), c.getProduct(), c.getKind(), c.getBasisAmount(),
                c.getRatePercent(), c.getAmount(), c.getStatus(),
                c.getReverses() == null ? null : c.getReverses().getRef(),
                c.getStatement() == null ? null : c.getStatement().getRef(), c.getSourceEventId(), c.getCreatedAt());
    }

    private static DistributionViews.StatementView view(CommissionStatement s,
            List<DistributionViews.CommissionView> lines) {
        return new DistributionViews.StatementView(s.getRef(), s.getIntermediary().getRef(),
                s.getIntermediary().getName(), s.getNetAmount(), s.getLineCount(), s.getStatus(), s.getIssuedBy(),
                s.getIssuedAt(), s.getPaidBy(), s.getPaidAt(), lines);
    }

    private static String required(JsonNode body, String field, int max) {
        String value = optional(body, field, max);
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        return value;
    }

    private static String optional(JsonNode body, String field, int max) {
        if (body == null || !body.hasNonNull(field)) {
            return null;
        }
        String value = body.get(field).asText().trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " must be at most " + max + " characters.");
        }
        return value;
    }

    private static LocalDate date(JsonNode body, String field, boolean required) {
        String value = required ? required(body, field, 10) : optional(body, field, 10);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(field + " must be a date (yyyy-MM-dd).");
        }
    }

    private static BigDecimal percent(JsonNode body, String field) {
        String value = required(body, field, 20);
        BigDecimal rate;
        try {
            rate = new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " must be a number.");
        }
        if (rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(field + " must be between 0 and 100.");
        }
        if (rate.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(field + " can have at most two decimal places.");
        }
        return rate.setScale(2);
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
