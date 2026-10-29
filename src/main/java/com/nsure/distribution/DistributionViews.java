package com.nsure.distribution;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class DistributionViews {

    private DistributionViews() {
    }

    public record IntermediaryView(
            String ref,
            String name,
            String type,
            String licenceNumber,
            String status,
            String statusReason,
            LocalDate appointmentExpiry,
            // Active and unexpired today: can take new policy assignments.
            boolean canTakeBusiness,
            String parentRef,
            String parentName,
            String email,
            String mobile,
            long openAssignments,
            // Commission accrued and not yet on a statement (clawbacks included).
            BigDecimal unstatementedBalance,
            String createdBy,
            LocalDateTime createdAt,
            String updatedBy,
            LocalDateTime updatedAt
    ) {
    }

    public record CommissionRuleView(
            Long id,
            String product,
            String intermediaryType,
            BigDecimal initialRatePercent,
            BigDecimal renewalRatePercent,
            LocalDate effectiveFrom,
            // The rule in force today for its product and intermediary type.
            boolean current,
            String createdBy,
            LocalDateTime createdAt
    ) {
    }

    public record AssignmentView(
            Long id,
            String policyRef,
            String customerName,
            String product,
            String intermediaryRef,
            String intermediaryName,
            String intermediaryType,
            LocalDate effectiveFrom,
            LocalDate endedOn,
            boolean open,
            String assignedBy,
            LocalDateTime assignedAt
    ) {
    }

    public record CommissionView(
            String ref,
            String intermediaryRef,
            String intermediaryName,
            String policyRef,
            String product,
            String kind,
            BigDecimal basisAmount,
            BigDecimal ratePercent,
            BigDecimal amount,
            String status,
            String reversesRef,
            String statementRef,
            String sourceEventId,
            LocalDateTime createdAt
    ) {
    }

    public record StatementView(
            String ref,
            String intermediaryRef,
            String intermediaryName,
            BigDecimal netAmount,
            int lineCount,
            String status,
            String issuedBy,
            LocalDateTime issuedAt,
            String paidBy,
            LocalDateTime paidAt,
            List<CommissionView> lines
    ) {
    }
}
