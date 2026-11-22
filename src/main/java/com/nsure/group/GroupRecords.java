package com.nsure.group;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.NotFoundException;
import com.nsure.portfolio.GroupScheme;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Shared lookups, request parsing and view mapping for the group administration services. Every method runs inside
 * the caller's transaction.
 */
@Component
public class GroupRecords {

    static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final EntityManager em;
    private final com.nsure.integration.Outbox outbox;
    private final CoverHistory cover;

    public GroupRecords(EntityManager em, com.nsure.integration.Outbox outbox, CoverHistory cover) {
        this.em = em;
        this.outbox = outbox;
        this.cover = cover;
    }

    /**
     * Records a member change and publishes it (GL04 MemberCoverChanged); a change that leaves cover waiting for
     * underwriting is also published as a referral (GL03 MemberUnderwritingReferred).
     */
    void memberChanged(MemberChange change) {
        em.persist(change);
        SchemeMember m = change.getMember();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("schemeRef", m.getScheme().getRef());
        data.put("memberKey", m.getMemberKey());
        data.put("memberId", m.getId());
        data.put("changeType", change.getChangeType());
        data.put("effectiveDate", change.getEffectiveDate().toString());
        data.put("coverBefore", change.getCoverBefore() == null ? null : change.getCoverBefore().toPlainString());
        data.put("coverAfter", change.getCoverAfter().toPlainString());
        data.put("pendingAfter", change.getPendingAfter().toPlainString());
        data.put("status", m.getStatus());
        data.put("sourceRef", change.getBatchRef());
        outbox.publish(com.nsure.integration.Events.MEMBER_COVER_CHANGED, m.getMemberKey(), data, change.getChangedBy());
        if (java.util.Set.of("JOIN", "SALARY", "TRANSFER_IN").contains(change.getChangeType())
                && SchemeMember.UNDERWRITING_PENDING.equals(m.getStatus()) && m.getPendingCover().signum() > 0) {
            Map<String, Object> referral = new java.util.LinkedHashMap<>();
            referral.put("schemeRef", m.getScheme().getRef());
            referral.put("memberKey", m.getMemberKey());
            referral.put("memberId", m.getId());
            referral.put("changeType", change.getChangeType());
            referral.put("requestedCover", m.getRequestedCover().toPlainString());
            referral.put("acceptedCover", m.getAcceptedCover().toPlainString());
            referral.put("pendingCover", m.getPendingCover().toPlainString());
            outbox.publish(com.nsure.integration.Events.MEMBER_UNDERWRITING_REFERRED, m.getMemberKey(), referral,
                    change.getChangedBy());
        }
    }

    void publish(String type, String ref, Map<String, ?> data, String actor) {
        outbox.publish(type, ref, data, actor);
    }

    // ── lookups ────────────────────────────────────────────────────────────────────────────────────

    GroupScheme scheme(String ref) {
        return em.createQuery("select s from GroupScheme s where s.ref = :ref", GroupScheme.class)
                .setParameter("ref", ref)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Scheme " + ref + " not found"));
    }

    /** The scheme row locked for update: serialises membership, census and billing changes on one scheme. */
    GroupScheme lockedScheme(String ref) {
        return em.createQuery("select s from GroupScheme s where s.ref = :ref", GroupScheme.class)
                .setParameter("ref", ref)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream()
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Scheme " + ref + " not found"));
    }

    /** Refuses a membership change unless the scheme is active (NS-GL01-006): suspended and closed schemes take none. */
    static void requireActive(GroupScheme scheme, String what) {
        if (!"ACTIVE".equals(scheme.getStatus())) {
            throw new IllegalArgumentException("Scheme " + scheme.getRef() + " is "
                    + scheme.getStatus().toLowerCase(java.util.Locale.ROOT) + "; " + what + " is not accepted until it"
                    + " is active again.");
        }
    }

    /** Refuses a change of terms on a closed scheme; its terms stay as they were for the cover history. */
    static void requireOpen(GroupScheme scheme) {
        if ("CLOSED".equals(scheme.getStatus())) {
            throw new IllegalArgumentException("Scheme " + scheme.getRef() + " is closed; its terms can no longer"
                    + " change.");
        }
    }

    List<BenefitCategory> categories(GroupScheme scheme) {
        return em.createQuery("select c from BenefitCategory c where c.scheme = :scheme order by c.code",
                        BenefitCategory.class)
                .setParameter("scheme", scheme)
                .getResultList();
    }

    /** Members with a live cover episode, keyed by member key. */
    Map<String, SchemeMember> membersOnCover(GroupScheme scheme) {
        Map<String, SchemeMember> byKey = new HashMap<>();
        for (SchemeMember member : em.createQuery(
                        "select m from SchemeMember m join fetch m.category where m.scheme = :scheme"
                                + " and m.status in :statuses", SchemeMember.class)
                .setParameter("scheme", scheme)
                .setParameter("statuses", SchemeMember.ON_COVER)
                .getResultList()) {
            byKey.put(member.getMemberKey(), member);
        }
        return byKey;
    }

    /** Keeps the ins_scheme dashboard snapshot (members, whole-pula monthly premium) in step with live cover. */
    void refreshTotals(GroupScheme scheme) {
        em.flush();
        int members = 0;
        BigDecimal premium = BigDecimal.ZERO;
        for (SchemeMember member : membersOnCover(scheme).values()) {
            members++;
            premium = premium.add(member.getCategory().monthlyPremium(member.getAcceptedCover()));
        }
        scheme.syncTotals(members, premium.setScale(0, RoundingMode.HALF_UP).intValue());
    }

    /** Next reference after the highest number used so far for the entity, e.g. CEN-00012. */
    String nextRef(Class<?> type, String prefix, int width) {
        int max = 0;
        for (String ref : em.createQuery("select e.ref from " + type.getSimpleName() + " e", String.class)
                .getResultList()) {
            String digits = ref.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                max = Math.max(max, Integer.parseInt(digits));
            }
        }
        return prefix + String.format("%0" + width + "d", max + 1);
    }

    // ── request parsing ────────────────────────────────────────────────────────────────────────────

    static String text(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    static String requiredText(JsonNode body, String field, String label, int max) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(label + " must be at most " + max + " characters");
        }
        return value;
    }

    static BigDecimal decimal(JsonNode body, String field, String label) {
        String value = text(body, field);
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number");
        }
    }

    static Integer integer(JsonNode body, String field, String label) {
        String value = text(body, field);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a whole number");
        }
    }

    static LocalDate date(JsonNode body, String field, String label) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        LocalDate parsed = parseDate(value);
        if (parsed == null) {
            throw new IllegalArgumentException(label + " must be a date in the format yyyy-MM-dd");
        }
        return parsed;
    }

    /** A strict yyyy-MM-dd date, or null when the text is not one. */
    static LocalDate parseDate(String value) {
        if (value == null || !ISO_DATE.matcher(value).matches()) {
            return null;
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** A money amount: positive (or zero when allowed), at most two decimals, returned at scale 2. */
    static BigDecimal money(BigDecimal value, String label, boolean zeroAllowed) {
        if (value == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        if (value.signum() < 0 || (!zeroAllowed && value.signum() == 0)) {
            throw new IllegalArgumentException(label + (zeroAllowed ? " must be zero or more" : " must be more than zero"));
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(label + " can have at most two decimals");
        }
        if (value.compareTo(new BigDecimal("9999999999999")) > 0) {
            throw new IllegalArgumentException(label + " is too large");
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // ── views ──────────────────────────────────────────────────────────────────────────────────────

    GroupViews.SchemeView schemeView(GroupScheme scheme, int activeMembers, int pending, BigDecimal premium,
            BigDecimal outstanding, int categories) {
        return new GroupViews.SchemeView(scheme.getRef(), scheme.getName(), scheme.getSector(), scheme.getInitials(),
                scheme.getColor(), scheme.getStatus(), scheme.getInceptionDate(), scheme.getRenewalDate(),
                scheme.getRenewal(), scheme.getBillingFrequency(), activeMembers, pending,
                premium.setScale(2, RoundingMode.HALF_UP), outstanding.setScale(2, RoundingMode.HALF_UP), categories,
                scheme.getQuoteRef(), scheme.getPolicyYear(), scheme.getRowVersion());
    }

    static GroupViews.CategoryView categoryView(BenefitCategory c, int activeMembers) {
        return new GroupViews.CategoryView(c.getCode(), c.getName(), c.getBenefitBasis(), c.getSalaryMultiple(),
                c.getFixedCover(), c.getFreeCoverLimit(), c.getRatePerMille(), c.getMinEntryAge(), c.getMaxEntryAge(),
                c.getLateEntryDays(), activeMembers, c.getUpdatedBy(), c.getUpdatedAt());
    }

    static GroupViews.MemberView memberView(SchemeMember m) {
        BenefitCategory c = m.getCategory();
        BigDecimal premium = m.onCover() ? c.monthlyPremium(m.getAcceptedCover()) : BigDecimal.ZERO.setScale(2);
        return new GroupViews.MemberView(m.getId(), m.getMemberKey(), m.getFirstName(), m.getLastName(),
                m.fullName(), m.getDateOfBirth(), c.getCode(), c.getName(), m.getSalary(), m.getStatus(),
                m.getJoinDate(), m.getExitDate(), m.getRequestedCover(), m.getAcceptedCover(), m.getPendingCover(),
                premium, m.getUnderwritingDecision(), m.getUnderwritingReason(), m.getUnderwrittenBy(),
                m.getUnderwrittenAt(), m.getSourceBatchRef(), m.getActivelyAtWork(), m.getRowVersion(),
                membershipStatus(m), coverStatus(m), m.getPartyRef());
    }

    /** Membership of the scheme, separate from cover (GL03 workspace): on the scheme, suspended with it, or left. */
    static String membershipStatus(SchemeMember m) {
        return SchemeMember.CEASED.equals(m.getStatus()) ? "LEFT"
                : SchemeMember.SUSPENDED.equals(m.getStatus()) ? "SUSPENDED" : "ON_SCHEME";
    }

    /** The benefit's cover status: in force, partly pending underwriting, pending, declined or ended. */
    static String coverStatus(SchemeMember m) {
        if (SchemeMember.CEASED.equals(m.getStatus())) {
            return "ENDED";
        }
        if (SchemeMember.SUSPENDED.equals(m.getStatus())) {
            return "SUSPENDED";
        }
        boolean accepted = m.getAcceptedCover().signum() > 0;
        boolean pending = m.getPendingCover().signum() > 0;
        if (!accepted && !pending) {
            return "DECLINED";
        }
        return accepted && pending ? "IN_FORCE_PART_PENDING" : pending ? "PENDING_UNDERWRITING" : "IN_FORCE";
    }

    static GroupViews.CensusBatchSummaryView batchSummary(CensusBatch b) {
        return new GroupViews.CensusBatchSummaryView(b.getRef(), b.getScheme().getRef(), b.getExternalBatchId(),
                b.getMode(), b.getStatus(), b.getFileHash(), b.getTotalRows(), b.getValidRows(), b.getInvalidRows(),
                b.getAcceptedRows(), b.getRejectedRows(), b.getSubmittedBy(), b.getSubmittedAt(), b.getCommittedBy(),
                b.getCommittedAt(), b.getHeldRows(), b.getSchemaVersion(), b.getCorrectsBatchRef());
    }

    GroupViews.CensusBatchView batchView(CensusBatch b) {
        List<GroupViews.CensusRowView> rows = em.createQuery(
                        "select r from CensusRow r where r.batch = :batch order by r.lineNo", CensusRow.class)
                .setParameter("batch", b)
                .getResultList()
                .stream()
                .map(r -> new GroupViews.CensusRowView(r.getLineNo(), r.getMemberKey(), r.getFirstName(),
                        r.getLastName(), r.getDateOfBirth(), r.getCategoryCode(), r.getSalary(), r.getAction(),
                        r.getEffectiveDate(), r.getStatus(), r.getError(), r.getMemberId(), r.getChangeId(),
                        r.getRowKey(), r.getHoldReason(), r.getReviewedBy(), r.getReviewNote(), r.getAdjustmentRefs(),
                        r.getCorrectsLine(), r.getCorrectedBy(), r.getPremiumBefore(), r.getPremiumAfter()))
                .toList();
        BigDecimal before = rows.stream().filter(r -> !CensusRow.INVALID.equals(r.status())
                        && !CensusRow.REJECTED.equals(r.status()) && r.premiumBefore() != null)
                .map(GroupViews.CensusRowView::premiumBefore).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        BigDecimal after = rows.stream().filter(r -> !CensusRow.INVALID.equals(r.status())
                        && !CensusRow.REJECTED.equals(r.status()) && r.premiumAfter() != null)
                .map(GroupViews.CensusRowView::premiumAfter).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new GroupViews.CensusBatchView(b.getRef(), b.getScheme().getRef(), b.getScheme().getName(),
                b.getExternalBatchId(), b.getMode(), b.getStatus(), b.getFileHash(), b.getTotalRows(),
                b.getValidRows(), b.getInvalidRows(), b.getAcceptedRows(), b.getRejectedRows(), b.getSubmittedBy(),
                b.getSubmittedAt(), b.getCommittedBy(), b.getCommittedAt(), rows, b.getHeldRows(), b.getSchemaVersion(),
                b.getCorrectsBatchRef(), before, after, cover.prorationText());
    }

    GroupViews.InvoiceView invoiceView(SchemeInvoice invoice) {
        return invoiceViews(List.of(invoice)).get(0);
    }

    /** Invoice views with their lines and receipts, loaded in two queries for the whole list. */
    List<GroupViews.InvoiceView> invoiceViews(List<SchemeInvoice> invoices) {
        if (invoices.isEmpty()) {
            return List.of();
        }
        Map<Long, List<GroupViews.InvoiceLineView>> lines = new HashMap<>();
        for (InvoiceLine l : em.createQuery(
                        "select l from InvoiceLine l where l.invoice in :invoices order by l.memberKey", InvoiceLine.class)
                .setParameter("invoices", invoices)
                .getResultList()) {
            lines.computeIfAbsent(l.getInvoice().getId(), id -> new ArrayList<>())
                    .add(new GroupViews.InvoiceLineView(l.getMemberId(), l.getMemberKey(), l.getMemberName(),
                            l.getCategoryCode(), l.getAcceptedCover(), l.getRatePerMille(), l.getDaysCovered(),
                            l.getDaysInPeriod(), l.getPremium(), l.getDisputeStatus(), l.getDisputeNote(), l.getId(),
                            l.getPaidAmount()));
        }
        Map<Long, List<GroupViews.AdjustmentView>> adjustments = new HashMap<>();
        for (GroupViews.AdjustmentView a : adjustmentViews(invoices)) {
            Long invoiceId = invoices.stream().filter(i -> i.getRef().equals(a.invoiceRef())).findFirst()
                    .map(SchemeInvoice::getId).orElse(null);
            adjustments.computeIfAbsent(invoiceId, id -> new ArrayList<>()).add(a);
        }
        Map<Long, List<GroupViews.ReceiptView>> receipts = new HashMap<>();
        for (SponsorReceipt r : em.createQuery(
                        "select r from SponsorReceipt r where r.invoice in :invoices order by r.id", SponsorReceipt.class)
                .setParameter("invoices", invoices)
                .getResultList()) {
            receipts.computeIfAbsent(r.getInvoice().getId(), id -> new ArrayList<>()).add(receiptView(r));
        }
        return invoices.stream()
                .map(i -> new GroupViews.InvoiceView(i.getRef(), i.getScheme().getRef(), i.getScheme().getName(),
                        i.getPeriod(), i.getPeriodStart(), i.getPeriodEnd(), i.getAmount(), i.getPaidAmount(),
                        i.getOutstanding(), lines.getOrDefault(i.getId(), List.of()).stream()
                                .filter(l -> "OPEN".equals(l.disputeStatus())).map(GroupViews.InvoiceLineView::premium)
                                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add), i.getStatus(), i.getPolicyYear(), i.getMemberCount(), i.getIssuedBy(), i.getIssuedAt(),
                        lines.getOrDefault(i.getId(), List.of()), receipts.getOrDefault(i.getId(), List.of()),
                        i.getAdjustmentAmount(), adjustments.getOrDefault(i.getId(), List.of()), i.getProrationBasis(),
                        i.getRateSource()))
                .toList();
    }

    /** Supplementary adjustments on the given invoices, with their lines, oldest first. */
    List<GroupViews.AdjustmentView> adjustmentViews(List<SchemeInvoice> invoices) {
        if (invoices.isEmpty()) {
            return List.of();
        }
        List<SchemeAdjustment> list = em.createQuery("select a from SchemeAdjustment a join fetch a.invoice"
                + " where a.invoice in :invoices order by a.id", SchemeAdjustment.class)
                .setParameter("invoices", invoices).getResultList();
        Map<Long, List<GroupViews.AdjustmentLineView>> lines = new HashMap<>();
        if (!list.isEmpty()) {
            for (AdjustmentLine l : em.createQuery("select l from AdjustmentLine l where l.adjustment in :a"
                    + " order by l.memberKey", AdjustmentLine.class).setParameter("a", list).getResultList()) {
                lines.computeIfAbsent(l.getAdjustment().getId(), k -> new ArrayList<>())
                        .add(new GroupViews.AdjustmentLineView(l.getOriginalLineId(), l.getMemberId(),
                                l.getMemberKey(), l.getMemberName(), l.getCategoryCode(), l.getChangeType(),
                                l.getCoverAfter(), l.getDaysCovered(), l.getBilledBefore(), l.getRecalculated(),
                                l.getAmount()));
            }
        }
        return list.stream().map(a -> new GroupViews.AdjustmentView(a.getRef(), a.getInvoice().getRef(),
                a.getInvoice().getPeriod(), a.getPolicyYear(), a.getType(), a.getAmount(), a.getAppliedToInvoice(),
                a.getHeldAsCredit(), a.getSourceRef(), a.getReason(), a.getIssuedBy(), a.getIssuedAt(),
                lines.getOrDefault(a.getId(), List.of()))).toList();
    }

    static GroupViews.ReceiptView receiptView(SponsorReceipt r) {
        return new GroupViews.ReceiptView(r.getRef(), r.getReference(), r.getAmount(), r.getOutstandingAfter(),
                r.getReceivedBy(), r.getReceivedAt(), r.getInvoice().getPolicyYear());
    }
}
