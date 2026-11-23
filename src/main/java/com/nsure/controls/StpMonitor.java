package com.nsure.controls;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The STP dashboard (spec 12 C-17) and the zero-rate alert (C-18): "if this reads 0 %, STP is broken". The alert turns
 * a silent two-year omission into a notification on day eight.
 */
@Service
public class StpMonitor {

    private final EntityManager em;
    private final ControlService controls;
    private final StaffAlertService alerts;

    public StpMonitor(EntityManager em, ControlService controls, StaffAlertService alerts) {
        this.em = em;
        this.controls = controls;
        this.alerts = alerts;
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard() {
        long lodged = count("select count(c) from InsuranceClaim c where c.processingType is not null");
        long stp = count("select count(c) from InsuranceClaim c where c.processingType = 'STP'");
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        long lodgedWeek = em.createQuery("select count(c) from InsuranceClaim c where c.lodgedAt >= :since", Long.class)
                .setParameter("since", weekAgo).getSingleResult();
        long stpWeek = em.createQuery("select count(c) from InsuranceClaim c where c.lodgedAt >= :since"
                        + " and c.processingType = 'STP'", Long.class)
                .setParameter("since", weekAgo).getSingleResult();
        Map<String, Long> reasons = new LinkedHashMap<>();
        for (Object[] row : em.createQuery("select c.stpIneligibleCode, count(c) from InsuranceClaim c"
                        + " where c.stpIneligibleCode is not null group by c.stpIneligibleCode order by count(c) desc",
                        Object[].class).getResultList()) {
            reasons.put((String) row[0], (Long) row[1]);
        }
        List<Bypass> register = em.createQuery("select c from com.nsure.portfolio.InsuranceClaim c"
                        + " where c.status in ('Approved', 'Payout Sent', 'Payout Failed', 'Paid', 'Closed')"
                        + " and c.processingType = 'Manual Review'"
                        + " and (c.fourEyesApplied = false or c.fourEyesApplied is null) order by c.id desc",
                        com.nsure.portfolio.InsuranceClaim.class)
                .setMaxResults(100)
                .getResultStream()
                .map(c -> new Bypass(c.getRef(), c.getStatus(), c.getRecommendedBy(), c.getAdjudicatedBy(),
                        c.getFourEyesOverrideReason() != null ? "Override: " + c.getFourEyesOverrideReason()
                                : "Four-eyes control was off"))
                .toList();
        boolean enabled = controls.isEnabled(ControlKey.CLAIM_STP_ENABLED.key());
        return new Dashboard(LocalDateTime.now(), enabled, controls.valueOf(ControlKey.CLAIM_STP_CEILING.key()),
                lodged, stp, pct(stp, lodged), lodgedWeek, stpWeek, pct(stpWeek, lodgedWeek),
                enabled && lodgedWeek > 0 && stpWeek == 0, reasons, register);
    }

    /** Daily: alert READ_CONTROL holders when STP is on but approved nothing over the last seven days (C-18). */
    @Scheduled(cron = "${nsure.controls.stp-alert-cron:0 30 6 * * *}")
    @Transactional
    public void checkStpRate() {
        Dashboard now = dashboard();
        if (now.zeroRateAlert()) {
            alerts.raise(StaffAlertService.STP_RATE_ZERO, "READ_CONTROL",
                    "Straight-through processing approved nothing this week",
                    now.lodgedLast7Days() + " claim(s) were lodged in the last 7 days and none was approved "
                            + "straight through, although STP is switched on. Check the ineligibility reasons.",
                    "stp-zero:" + LocalDate.now(), "system");
        }
    }

    private long count(String jpql) {
        return em.createQuery(jpql, Long.class).getSingleResult();
    }

    private static BigDecimal pct(long part, long whole) {
        return whole == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(part * 100).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    public record Bypass(String claimRef, String status, String assessedBy, String approvedBy, String detail) {
    }

    public record Dashboard(
            LocalDateTime asOf,
            boolean stpEnabled,
            BigDecimal stpCeiling,
            long claimsLodged,
            long stpApproved,
            BigDecimal stpRatePct,
            long lodgedLast7Days,
            long stpLast7Days,
            BigDecimal stpRateLast7DaysPct,
            boolean zeroRateAlert,
            Map<String, Long> ineligibleReasons,
            List<Bypass> fourEyesNotApplied) {
    }
}
