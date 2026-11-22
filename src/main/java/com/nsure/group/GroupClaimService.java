package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.GroupScheme;
import com.nsure.security.domain.AppUser;
import com.nsure.security.exception.NoAuthorizationException;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The group claim register: a claim is notified against the member's cover episode on the event date, decided by
 * someone other than the person who notified it, and then paid (lump sums) or paid monthly with periodic reviews until
 * it ceases (income benefits). Only cover accepted on the event date is claimable; cover still pending underwriting
 * is not (AT-GL03-01). Assessment rules and payment execution stay with the insurer's claims process.
 */
@Service
public class GroupClaimService {

    static final Set<String> BENEFITS = Set.of("GLA", "LUMP_SUM_DISABILITY", "CRITICAL_ILLNESS", "FAMILY",
            "INCOME_PROTECTION");
    /** Months to the next income-benefit review when the decision gives no date (the default benefit terms). */
    static final int DEFAULT_REVIEW_MONTHS = 12;

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final GroupRecords records;
    private final BenefitRules rules;

    public GroupClaimService(EntityManager em, PlatformSecurityContext security, GroupRecords records,
            BenefitRules rules) {
        this.em = em;
        this.security = security;
        this.records = records;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public List<GroupViews.ClaimView> claims(String schemeRef) {
        security.authenticatedUser().validateHasPermissionTo("READ_SCHEME");
        GroupScheme scheme = records.scheme(schemeRef);
        return em.createQuery("select c from GroupClaim c join fetch c.member where c.scheme = :s order by c.id desc",
                        GroupClaim.class)
                .setParameter("s", scheme).getResultList().stream().map(GroupClaimService::view).toList();
    }

    /** {memberKey, benefitType, eventDate, amount, note?}: notifies a claim on the episode covering the event date. */
    @Transactional
    public GroupViews.ClaimView notify(String schemeRef, JsonNode body) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("CREATE_CLAIM");
        GroupScheme scheme = records.lockedScheme(schemeRef);
        String key = GroupRecords.requiredText(body, "memberKey", "Member key", 40);
        String benefit = GroupRecords.requiredText(body, "benefitType", "Benefit", 30).toUpperCase(Locale.ROOT);
        if (!BENEFITS.contains(benefit)) {
            throw new IllegalArgumentException("Benefit must be one of " + BENEFITS.stream().sorted().toList());
        }
        List<String> provided = em.createQuery("select b.benefitType from SchemeBenefit b where b.schemeRef = :ref",
                String.class).setParameter("ref", schemeRef).getResultList();
        if (!(provided.isEmpty() ? List.of("GLA") : provided).contains(benefit)) {
            throw new IllegalArgumentException("Scheme " + schemeRef + " does not provide " + benefit + ".");
        }
        LocalDate event = GroupRecords.date(body, "eventDate", "Event date");
        if (event.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The event date cannot be in the future.");
        }
        BigDecimal amount = GroupRecords.money(GroupRecords.decimal(body, "amount", "Amount"), "Amount", false);
        String note = GroupRecords.text(body, "note");
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("Note must be at most 500 characters");
        }
        SchemeMember member = em.createQuery("select m from SchemeMember m where m.scheme = :s and m.memberKey = :key"
                        + " order by m.joinDate desc", SchemeMember.class)
                .setParameter("s", scheme).setParameter("key", key).getResultList().stream()
                .filter(m -> m.coveredOn(event)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Member " + key + " had no cover on scheme " + schemeRef
                        + " on " + event + "."));
        // AT-GL03-01: a lump sum is limited to the cover accepted on the episode; pending cover is not claimable.
        if ("GLA".equals(benefit) && amount.compareTo(member.getAcceptedCover()) > 0) {
            throw new IllegalArgumentException("The claim of " + amount.toPlainString() + " exceeds the cover in force of "
                    + member.getAcceptedCover().toPlainString() + "; cover pending underwriting is not claimable.");
        }
        GroupClaim existing = em.createQuery("select c from GroupClaim c where c.member = :m and c.benefitType = :b"
                        + " and c.eventDate = :d and c.status <> :declined", GroupClaim.class)
                .setParameter("m", member).setParameter("b", benefit).setParameter("d", event)
                .setParameter("declined", GroupClaim.DECLINED).getResultStream().findFirst().orElse(null);
        if (existing != null) {
            throw new ConflictException("This event is already claimed as " + existing.getRef() + ".");
        }
        GroupClaim claim = new GroupClaim(records.nextRef(GroupClaim.class, "GCL-", 5), member, benefit, event, amount,
                note, user.getUsername());
        benefitTerms(claim, member, benefit, event, amount, body);
        em.persist(claim);
        return view(claim);
    }

    /**
     * {action, amount?, note, nextReviewDate?, paymentReference?}. APPROVE (APPROVE_CLAIM, not the notifier),
     * DECLINE (ASSESS_CLAIM), PAY a lump sum (SETTLE_CLAIM), REVIEW or CEASE an income benefit in payment
     * (ASSESS_CLAIM).
     */
    @Transactional
    public GroupViews.ClaimView decide(String ref, JsonNode body) {
        AppUser user = security.authenticatedUser();
        GroupClaim claim = em.createQuery("select c from GroupClaim c where c.ref = :ref", GroupClaim.class)
                .setParameter("ref", ref).setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Claim " + ref + " not found"));
        String action = GroupRecords.requiredText(body, "action", "Action", 20).toUpperCase(Locale.ROOT);
        String note = GroupRecords.text(body, "note");
        if (note != null && note.length() > 500) {
            throw new IllegalArgumentException("Note must be at most 500 characters");
        }
        boolean income = "INCOME_PROTECTION".equals(claim.getBenefitType());
        switch (action) {
            case "APPROVE" -> {
                user.validateHasPermissionTo("APPROVE_CLAIM");
                expect(claim, GroupClaim.NOTIFIED);
                if (user.getUsername().equalsIgnoreCase(claim.getNotifiedBy())) {
                    throw new NoAuthorizationException("You notified " + ref + ", so someone else must decide it.");
                }
                BigDecimal amount = GroupRecords.decimal(body, "amount", "Amount");
                amount = amount == null ? claim.getClaimedAmount() : GroupRecords.money(amount, "Amount", false);
                if (amount.compareTo(claim.getClaimedAmount()) > 0) {
                    throw new IllegalArgumentException("The approved amount cannot exceed the "
                            + claim.getClaimedAmount().toPlainString() + " claimed.");
                }
                Map<String, String> terms = terms(claim);
                approvalRules(claim, terms, body);
                claim.approve(amount, income, income ? reviewDate(body, LocalDate.now(), months(terms, "REVIEW_MONTHS"))
                        : null, note, user.getUsername());
            }
            case "DECLINE" -> {
                user.validateHasPermissionTo("ASSESS_CLAIM");
                expect(claim, GroupClaim.NOTIFIED);
                claim.decline(reason(note, "declining"), user.getUsername());
            }
            case "PAY" -> {
                user.validateHasPermissionTo("SETTLE_CLAIM");
                expect(claim, GroupClaim.APPROVED);
                claim.paid(GroupRecords.requiredText(body, "paymentReference", "Payment reference", 80),
                        user.getUsername());
                accelerate(claim, user.getUsername());
                // The paid benefit posts to the subledger (posting rule GroupClaimPaid, changeset 153).
                Map<String, Object> paid = new java.util.LinkedHashMap<>();
                paid.put("schemeRef", claim.getScheme().getRef());
                paid.put("claimRef", claim.getRef());
                paid.put("memberKey", claim.getMember().getMemberKey());
                paid.put("benefit", claim.getBenefitType());
                paid.put("paymentReference", claim.getPaymentReference());
                paid.put("amount", claim.getApprovedAmount().toPlainString());
                records.publish(com.nsure.integration.Events.GROUP_CLAIM_PAID, claim.getRef(), paid, user.getUsername());
            }
            case "REVIEW" -> {
                user.validateHasPermissionTo("ASSESS_CLAIM");
                expect(claim, GroupClaim.IN_PAYMENT);
                if (claim.getCessationDate() != null && !LocalDate.now().isBefore(claim.getCessationDate())) {
                    throw new IllegalArgumentException("The income benefit reached its cessation date ("
                            + claim.getCessationDate() + "); cease it.");
                }
                LocalDate from = claim.getNextReviewDate() == null ? LocalDate.now() : claim.getNextReviewDate();
                claim.reviewed(reviewDate(body, from, months(terms(claim), "REVIEW_MONTHS")),
                        reason(note, "the review outcome"), user.getUsername());
            }
            case "CEASE" -> {
                user.validateHasPermissionTo("ASSESS_CLAIM");
                expect(claim, GroupClaim.IN_PAYMENT);
                claim.cease(reason(note, "ceasing the income benefit"), user.getUsername());
            }
            case "REINSTATE" -> {
                user.validateHasPermissionTo("ASSESS_CLAIM");
                reinstate(claim, reason(note, "the reinstatement"), user.getUsername());
            }
            default -> throw new IllegalArgumentException("Action must be APPROVE, DECLINE, PAY, REVIEW, CEASE or REINSTATE");
        }
        return view(claim);
    }

    private static LocalDate reviewDate(JsonNode body, LocalDate from, Integer months) {
        String text = GroupRecords.text(body, "nextReviewDate");
        if (text == null) {
            return from.plusMonths(months == null ? DEFAULT_REVIEW_MONTHS : months);
        }
        LocalDate date = GroupRecords.parseDate(text);
        if (date == null || !date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The next review date must be a future date in the format yyyy-MM-dd");
        }
        return date;
    }

    // ── benefit-pack rules (NS-GL02-004/005, AT-GL02-03, section 08) ───────────────────────────────

    private Map<String, String> terms(GroupClaim c) {
        return rules.resolved(c.getScheme().getRef(), c.getMember().getCategory().getCode(), c.getBenefitType(),
                c.getEventDate());
    }

    private static Integer months(Map<String, String> terms, String parameter) {
        String v = terms.get(parameter);
        return v == null ? null : new BigDecimal(v).intValue();
    }

    private static BigDecimal number(Map<String, String> terms, String parameter) {
        String v = terms.get(parameter);
        return v == null ? null : new BigDecimal(v);
    }

    /**
     * Applies the benefit's resolved terms at notification: the category must have the benefit, an event inside the
     * category's waiting period is only claimable as an accident, and the amount must fit the benefit's limit (life
     * cover; disability and CI sums assured less earlier payments, partial at the partial percentage; CI related
     * conditions and the cap per condition; family limits per life, spouses, child age and stillbirth; income
     * protection's percentage of salary with its deferred period, cessation and escalation).
     */
    private void benefitTerms(GroupClaim claim, SchemeMember member, String benefit, LocalDate event, BigDecimal amount,
            JsonNode body) {
        String schemeRef = member.getScheme().getRef();
        String category = member.getCategory().getCode();
        if (!rules.included(schemeRef, category, benefit, event)) {
            throw new IllegalArgumentException("Category " + category + " does not have " + BenefitRules.label(benefit)
                    + " under scheme " + schemeRef + ".");
        }
        boolean accident = body.path("accident").asBoolean(false);
        Integer waiting = member.getCategory().getWaitingDays();
        if (waiting != null && !accident && event.isBefore(member.getJoinDate().plusDays(waiting))) {
            throw new IllegalArgumentException("The event falls in category " + category + "'s " + waiting + "-day waiting"
                    + " period (to " + member.getJoinDate().plusDays(waiting).minusDays(1) + "); only an accident is covered.");
        }
        Map<String, String> terms = rules.resolved(schemeRef, category, benefit, event);
        String life = null;
        String lifeName = GroupRecords.text(body, "lifeName");
        String conditionGroup = null;
        boolean partial = body.path("partial").asBoolean(false);
        Integer gestation = GroupRecords.integer(body, "gestationWeeks", "Gestation");
        Integer childAge = GroupRecords.integer(body, "childAge", "Child's age");
        BigDecimal limit;
        LocalDate firstPayment = null;
        LocalDate cessation = null;
        BigDecimal escalation = null;
        switch (benefit) {
            case "LUMP_SUM_DISABILITY" -> {
                BigDecimal sumAssured = sumAssured(member, benefit);
                BigDecimal paid = "YES".equals(terms.get("PARTIAL_REDUCES_COVER")) ? admitted(member, benefit, null)
                        : BigDecimal.ZERO;
                limit = sumAssured.subtract(paid).max(BigDecimal.ZERO);
                if (partial) {
                    limit = limit.min(sumAssured.multiply(number(terms, "PARTIAL_PERCENT"))
                            .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP));
                }
            }
            case "CRITICAL_ILLNESS" -> {
                conditionGroup = GroupRecords.requiredText(body, "conditionGroup", "Condition group", 60)
                        .toUpperCase(Locale.ROOT);
                if (admitted(member, benefit, conditionGroup).signum() > 0) {
                    throw new IllegalArgumentException("A related " + conditionGroup.toLowerCase(Locale.ROOT)
                            + " condition has already been paid; related conditions share one payment.");
                }
                BigDecimal sumAssured = sumAssured(member, benefit);
                BigDecimal cap = sumAssured.multiply(number(terms, "CAP_PER_CONDITION_PERCENT"))
                        .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
                limit = cap;
                if ("YES".equals(terms.get("ACCELERATES_GLA"))) {
                    limit = limit.min(member.getAcceptedCover());
                }
            }
            case "FAMILY" -> {
                life = GroupRecords.text(body, "life") == null ? "MEMBER"
                        : GroupRecords.text(body, "life").toUpperCase(Locale.ROOT);
                limit = switch (life) {
                    case "MEMBER" -> number(terms, "MEMBER_AMOUNT");
                    case "SPOUSE" -> number(terms, "SPOUSE_AMOUNT");
                    case "CHILD", "STILLBIRTH" -> number(terms, "CHILD_AMOUNT");
                    default -> throw new IllegalArgumentException("life must be MEMBER, SPOUSE, CHILD or STILLBIRTH");
                };
                if ("SPOUSE".equals(life)) {
                    if (lifeName == null) {
                        throw new IllegalArgumentException("Give the spouse's name (lifeName) for a spouse claim.");
                    }
                    long spouses = em.createQuery("select count(distinct c.lifeName) from GroupClaim c where c.member = :m"
                            + " and c.lifeInsured = 'SPOUSE' and c.status <> :declined and lower(c.lifeName) <> :n", Long.class)
                            .setParameter("m", member).setParameter("declined", GroupClaim.DECLINED)
                            .setParameter("n", lifeName.toLowerCase(Locale.ROOT)).getSingleResult();
                    if (spouses >= months(terms, "MAX_SPOUSES")) {
                        throw new IllegalArgumentException("The family benefit covers up to " + terms.get("MAX_SPOUSES")
                                + " spouse(s); " + spouses + " already claimed.");
                    }
                }
                if ("CHILD".equals(life) && (childAge == null || childAge > months(terms, "CHILD_MAX_AGE"))) {
                    throw new IllegalArgumentException("Children are covered to age " + terms.get("CHILD_MAX_AGE")
                            + "; give the child's age (childAge).");
                }
                if ("STILLBIRTH".equals(life) && (gestation == null || gestation < months(terms, "STILLBIRTH_WEEKS"))) {
                    throw new IllegalArgumentException("A stillbirth is covered from week " + terms.get("STILLBIRTH_WEEKS")
                            + " of pregnancy; give the weeks (gestationWeeks).");
                }
                // The funeral support is paid on top of the life's benefit (section 08).
                limit = limit.add(number(terms, "FUNERAL_SUPPORT"));
            }
            case "INCOME_PROTECTION" -> {
                // Without a salary on record the income limit is left to the assessor (no earnings to apply it to).
                limit = member.getSalary() == null ? amount : member.getSalary().multiply(number(terms, "INCOME_PERCENT"))
                        .divide(BigDecimal.valueOf(1200), 2, java.math.RoundingMode.HALF_UP);
                firstPayment = event.plusMonths(months(terms, "DEFERRED_MONTHS"));
                cessation = "TIP".equals(terms.get("VARIANT")) ? firstPayment.plusMonths(months(terms, "TIP_MONTHS"))
                        : member.getDateOfBirth().plusYears(months(terms, "RETIREMENT_AGE"));
                escalation = number(terms, "ESCALATION_PERCENT");
                Integer backdating = months(terms, "BACKDATING_MONTHS");
                if (backdating != null && firstPayment.plusMonths(backdating).isBefore(LocalDate.now())) {
                    firstPayment = LocalDate.now().minusMonths(backdating);
                }
            }
            default -> limit = member.getAcceptedCover();
        }
        limit = limit.setScale(2, java.math.RoundingMode.HALF_UP);
        if (amount.compareTo(limit) > 0) {
            throw new IllegalArgumentException("The claim of " + amount.toPlainString() + " exceeds the "
                    + BenefitRules.label(benefit).toLowerCase(Locale.ROOT) + " limit of " + limit.toPlainString()
                    + (partial ? " for a partial disability" : "") + " under the terms in force on " + event + ".");
        }
        claim.details(life, lifeName, conditionGroup, partial, accident, gestation, childAge, limit, firstPayment, cessation,
                escalation);
    }

    /** Approval rules: a critical illness needs the survival period met; a disability its assessment period. */
    private static void approvalRules(GroupClaim claim, Map<String, String> terms, JsonNode body) {
        if ("CRITICAL_ILLNESS".equals(claim.getBenefitType())) {
            int days = months(terms, "SURVIVAL_DAYS");
            LocalDate survival = GroupRecords.text(body, "survivalConfirmedOn") == null ? null
                    : GroupRecords.date(body, "survivalConfirmedOn", "Survival confirmed on");
            LocalDate earliest = claim.getEventDate().plusDays(days);
            if (survival == null || survival.isBefore(earliest) || survival.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("A critical-illness claim is approved once survival for " + days
                        + " days after diagnosis is confirmed (on or after " + earliest + "): give survivalConfirmedOn.");
            }
            claim.survivalConfirmed(survival);
        }
        if ("LUMP_SUM_DISABILITY".equals(claim.getBenefitType())) {
            LocalDate assessed = claim.getEventDate().plusMonths(months(terms, "ASSESSMENT_MONTHS"));
            if (assessed.isAfter(LocalDate.now())) {
                throw new IllegalArgumentException("The disability assessment period runs to " + assessed
                        + "; it can be approved after that.");
            }
        }
    }

    /** A paid critical illness that accelerates the life cover reduces it by the amount paid. */
    private void accelerate(GroupClaim claim, String actor) {
        if (!"CRITICAL_ILLNESS".equals(claim.getBenefitType()) || !"YES".equals(terms(claim).get("ACCELERATES_GLA"))) {
            return;
        }
        SchemeMember m = claim.getMember();
        BigDecimal before = m.getAcceptedCover();
        BigDecimal amount = claim.getApprovedAmount().min(before);
        m.accelerate(amount);
        claim.accelerated(amount);
        records.memberChanged(new MemberChange(m, "ACCELERATION", LocalDate.now(), m.getSalary(), before, claim.getRef(),
                "Life cover reduced by critical-illness payment " + claim.getRef(), actor));
        records.refreshTotals(m.getScheme());
    }

    /**
     * Reinstates life cover an accelerated critical-illness payment reduced (AT-GL02-03): refused until the insurer's
     * reinstatement rule is configured; under AFTER_12_MONTHS, from twelve months after the payment.
     */
    private void reinstate(GroupClaim claim, String note, String actor) {
        if (!"CRITICAL_ILLNESS".equals(claim.getBenefitType()) || claim.getAcceleratedAmount() == null
                || claim.getAcceleratedAmount().signum() <= 0) {
            throw new IllegalArgumentException(claim.getRef() + " did not accelerate any life cover.");
        }
        if (claim.getReinstatedOn() != null) {
            throw new ConflictException(claim.getRef() + " was already reinstated on " + claim.getReinstatedOn() + ".");
        }
        String rule = terms(claim).get("REINSTATEMENT_RULE");
        if (rule == null || "NOT_CONFIGURED".equals(rule)) {
            throw new IllegalArgumentException("No approved reinstatement rule is configured for critical illness, so the"
                    + " accelerated cover cannot be reinstated.");
        }
        LocalDate paid = claim.getUpdatedAt().toLocalDate();
        if ("AFTER_12_MONTHS".equals(rule) && paid.plusMonths(12).isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Under the reinstatement rule cover is reinstated from " + paid.plusMonths(12)
                    + ".");
        }
        SchemeMember m = claim.getMember();
        BigDecimal before = m.getAcceptedCover();
        m.reinstate(claim.getAcceleratedAmount());
        claim.reinstated(LocalDate.now(), note, actor);
        records.memberChanged(new MemberChange(m, "REINSTATEMENT", LocalDate.now(), m.getSalary(), before, claim.getRef(),
                "Accelerated cover reinstated: " + note, actor));
        records.refreshTotals(m.getScheme());
    }

    /** The benefit's sum assured for the member from the scheme's definition (multiple of salary or fixed). */
    private BigDecimal sumAssured(SchemeMember member, String benefit) {
        Object[] def = em.createQuery("select b.basis, b.multiple, b.fixedAmount from SchemeBenefit b where b.schemeRef = :r"
                        + " and b.benefitType = :t", Object[].class)
                .setParameter("r", member.getScheme().getRef()).setParameter("t", benefit).getResultStream().findFirst()
                .orElse(null);
        if (def == null) {
            return member.getAcceptedCover();
        }
        if ("FIXED".equals(def[0]) && def[2] != null) {
            return (BigDecimal) def[2];
        }
        if ("SALARY_MULTIPLE".equals(def[0]) && def[1] != null && member.getSalary() != null) {
            return member.getSalary().multiply((BigDecimal) def[1]).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return member.getAcceptedCover();
    }

    /** Admitted (approved or paid) amounts already on the member's claims for the benefit, or one condition group. */
    private BigDecimal admitted(SchemeMember member, String benefit, String conditionGroup) {
        BigDecimal total = em.createQuery("select coalesce(sum(c.approvedAmount), 0) from GroupClaim c where c.member = :m"
                        + " and c.benefitType = :b and c.status in :admitted"
                        + (conditionGroup == null ? "" : " and c.conditionGroup = :g"), BigDecimal.class)
                .setParameter("m", member).setParameter("b", benefit).setParameter("admitted", GroupClaim.ADMITTED)
                .setParameter(conditionGroup == null ? "b" : "g", conditionGroup == null ? benefit : conditionGroup)
                .getSingleResult();
        return total == null ? BigDecimal.ZERO : total;
    }

    private static String reason(String note, String what) {
        if (note == null) {
            throw new IllegalArgumentException("Please enter a note for " + what);
        }
        return note;
    }

    private static void expect(GroupClaim claim, String status) {
        if (!status.equals(claim.getStatus())) {
            throw new ConflictException(claim.getRef() + " is " + claim.getStatus().toLowerCase(Locale.ROOT)
                    .replace('_', ' ') + "; this step needs it " + status.toLowerCase(Locale.ROOT).replace('_', ' ')
                    + ".");
        }
    }

    static GroupViews.ClaimView view(GroupClaim c) {
        SchemeMember m = c.getMember();
        return new GroupViews.ClaimView(c.getRef(), c.getScheme().getRef(), m.getId(), m.getMemberKey(), m.fullName(),
                c.getBenefitType(), c.getEventDate(), c.getClaimedAmount(), c.getApprovedAmount(), c.getStatus(),
                c.getNextReviewDate(), c.getPaymentReference(), c.getNote(), c.getNotifiedBy(), c.getNotifiedAt(),
                c.getDecidedBy(), c.getDecidedAt(), c.getLifeInsured(), c.getLifeName(), c.getConditionGroup(),
                c.isPartial(), c.isAccident(), c.getBenefitLimit(), c.getFirstPaymentDate(), c.getCessationDate(),
                c.getEscalationPercent(), c.getSurvivalConfirmedOn(), c.getAcceleratedAmount(), c.getReinstatedOn());
    }
}
