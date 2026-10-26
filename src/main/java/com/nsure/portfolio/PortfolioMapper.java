package com.nsure.portfolio;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Builds the API views of customers, policies and claims, shared by the staff portfolio and the customer portal. */
@Component
public class PortfolioMapper {

    private final EntityManager em;

    public PortfolioMapper(EntityManager em) {
        this.em = em;
    }

    public PortfolioViews.CustomerView customer(CustomerAccount row) {
        return new PortfolioViews.CustomerView(
                row.getRef(), row.getName(), row.getEmail(), row.getPhone(),
                blank(row.getProduct()), row.getPolicyCount(), row.getStatus(), row.getColor(),
                row.isPep(), blank(row.getEddStatus()), blank(row.getKycRejectionReason()),
                blank(row.getEddRejectionReason()));
    }

    public PortfolioViews.CoverView cover(CoverRecord row) {
        return cover(row, row instanceof InsurancePolicy
                ? beneficiariesOf(List.of(row.getId())).getOrDefault(row.getId(), List.of()) : List.of());
    }

    /** Policies (or other cover records) with their beneficiaries, loaded in one query. */
    public List<PortfolioViews.CoverView> covers(List<? extends CoverRecord> rows) {
        List<Long> policyIds = rows.stream().filter(InsurancePolicy.class::isInstance).map(CoverRecord::getId).toList();
        Map<Long, List<PortfolioViews.BeneficiaryView>> beneficiaries = beneficiariesOf(policyIds);
        return rows.stream().map(row -> cover(row, beneficiaries.getOrDefault(row.getId(), List.of()))).toList();
    }

    private PortfolioViews.CoverView cover(CoverRecord row, List<PortfolioViews.BeneficiaryView> beneficiaries) {
        InsurancePolicy policy = row instanceof InsurancePolicy p ? p : null;
        LocalDate today = LocalDate.now();
        return new PortfolioViews.CoverView(
                row.getRef(), row.getCustomerName(), row.getProduct(), row.getAmount(),
                row.getPremium(), row.getStatus(), row.getRecordDate(),
                policy == null ? "" : date(policy.getCoverStartDate()),
                policy == null ? "" : date(policy.getNextPremiumDue()),
                policy == null ? "" : date(policy.getGracePeriodEnd()),
                policy == null || policy.getPaymentDueBy() == null ? "" : policy.getPaymentDueBy().withNano(0).toString(),
                policy == null ? "" : date(policy.getLapseDate()),
                policy == null ? "" : blank(policy.getPaymentMethod()),
                policy != null && PolicyWorkflow.ACTIVE.equals(policy.getStatus()) && policy.getNextPremiumDue() != null
                        && policy.getNextPremiumDue().isBefore(today) && policy.getGracePeriodEnd() != null
                        && !policy.getGracePeriodEnd().isBefore(today),
                policy == null ? "" : blank(policy.getStatusChangedBy()),
                policy == null ? "" : blank(policy.getStatusNote()),
                beneficiaries);
    }

    public PortfolioViews.ClaimView claim(InsuranceClaim row) {
        return new PortfolioViews.ClaimView(
                row.getRef(), row.getCustomerName(), row.getProduct(), row.getAmount(), row.getPremium(),
                row.getStatus(), row.getRecordDate(), blank(row.getClaimType()), blank(row.getDescription()),
                blank(row.getEventDate()), blank(row.getPolicyRef()), blank(row.getClaimantName()),
                blank(row.getClaimantMobile()), blank(row.getClaimantRelationship()), blank(row.getProcessingType()),
                blank(row.getStpIneligibleReason()),
                ClaimWorkflow.results(row).entrySet().stream()
                        .map(entry -> new PortfolioViews.CheckView(entry.getKey(), entry.getValue()))
                        .toList(),
                new PortfolioViews.PayeeView(blank(row.getPayeeName()), blank(row.getPayeeRelationship()),
                        blank(row.getPayeeMobile()), blank(row.getPayeePayoutMethod()), blank(row.getPayeeAccountRef()),
                        blank(row.getPayeeSource()), row.isPayoutVerified(), blank(row.getPayoutVerifiedBy())),
                blank(row.getRecommendedBy()), blank(row.getAdjudicatedBy()), row.getFourEyesApplied(),
                blank(row.getRejectionReason()), row.getPayoutAmount(), blank(row.getPayoutReference()),
                row.getPaidAt() == null ? "" : row.getPaidAt().withNano(0).toString(),
                ClaimWorkflow.allowedActions(row.getStatus()));
    }

    /** Beneficiaries of the given policies, keyed by policy id, in one query. */
    private Map<Long, List<PortfolioViews.BeneficiaryView>> beneficiariesOf(List<Long> policyIds) {
        if (policyIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PortfolioViews.BeneficiaryView>> byPolicy = new HashMap<>();
        for (Object[] row : em.createQuery(
                        "select b.policy.id, b from PolicyBeneficiary b where b.policy.id in :ids"
                                + " order by b.displayOrder, b.id", Object[].class)
                .setParameter("ids", policyIds)
                .getResultList()) {
            PolicyBeneficiary b = (PolicyBeneficiary) row[1];
            byPolicy.computeIfAbsent((Long) row[0], key -> new ArrayList<>()).add(new PortfolioViews.BeneficiaryView(
                    b.getId(), b.getFullName(), b.getRelationship(), blank(b.getNationalId()), blank(b.getMobile()),
                    blank(b.getPayoutMethod()), blank(b.getAccountRef()), b.isPrimary()));
        }
        return byPolicy;
    }

    private static String date(LocalDate value) {
        return value == null ? "" : value.toString();
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }
}
