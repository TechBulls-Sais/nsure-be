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

    public GroupClaimService(EntityManager em, PlatformSecurityContext security, GroupRecords records) {
        this.em = em;
        this.security = security;
        this.records = records;
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
        if (!"INCOME_PROTECTION".equals(benefit) && amount.compareTo(member.getAcceptedCover()) > 0) {
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
                claim.approve(amount, income, income ? reviewDate(body, LocalDate.now()) : null, note,
                        user.getUsername());
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
            }
            case "REVIEW" -> {
                user.validateHasPermissionTo("ASSESS_CLAIM");
                expect(claim, GroupClaim.IN_PAYMENT);
                LocalDate from = claim.getNextReviewDate() == null ? LocalDate.now() : claim.getNextReviewDate();
                claim.reviewed(reviewDate(body, from), reason(note, "the review outcome"), user.getUsername());
            }
            case "CEASE" -> {
                user.validateHasPermissionTo("ASSESS_CLAIM");
                expect(claim, GroupClaim.IN_PAYMENT);
                claim.cease(reason(note, "ceasing the income benefit"), user.getUsername());
            }
            default -> throw new IllegalArgumentException("Action must be APPROVE, DECLINE, PAY, REVIEW or CEASE");
        }
        return view(claim);
    }

    private static LocalDate reviewDate(JsonNode body, LocalDate from) {
        String text = GroupRecords.text(body, "nextReviewDate");
        if (text == null) {
            return from.plusMonths(DEFAULT_REVIEW_MONTHS);
        }
        LocalDate date = GroupRecords.parseDate(text);
        if (date == null || !date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("The next review date must be a future date in the format yyyy-MM-dd");
        }
        return date;
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
                c.getDecidedBy(), c.getDecidedAt());
    }
}
