package com.nsure.reinsurance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class ReinsuranceViews {

    private ReinsuranceViews() {
    }

    public record TreatyView(
            String ref,
            String name,
            String reinsurer,
            String treatyType,
            List<String> products,
            BigDecimal cededPercent,
            BigDecimal retention,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            // Approved and in force today: cedes new premiums automatically.
            boolean ceding,
            String createdBy,
            LocalDateTime createdAt,
            String approvedBy,
            LocalDateTime approvedAt,
            long cessionCount,
            BigDecimal totalCededPremium
    ) {
    }

    public record CessionView(
            String ref,
            String treatyRef,
            String treatyName,
            String policyRef,
            String product,
            BigDecimal sumAssured,
            BigDecimal cededSum,
            BigDecimal premium,
            BigDecimal cededPremium,
            BigDecimal cededShare,
            String bordereauRef,
            String sourceEventId,
            LocalDateTime createdAt
    ) {
    }

    public record RecoveryView(
            String ref,
            String treatyRef,
            String treatyName,
            String reinsurer,
            String claimRef,
            String policyRef,
            BigDecimal claimAmount,
            BigDecimal cededShare,
            BigDecimal recoveryAmount,
            String status,
            LocalDateTime createdAt
    ) {
    }

    public record BordereauView(
            String ref,
            String treatyRef,
            String treatyName,
            String reinsurer,
            String period,
            int recordCount,
            BigDecimal totalCededPremium,
            BigDecimal totalCededSum,
            String status,
            String createdBy,
            LocalDateTime createdAt
    ) {
    }
}
