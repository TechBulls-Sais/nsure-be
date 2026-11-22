package com.nsure.distribution;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether an intermediary may sell (NS-SH06-001, AT-SH06-02): the code must be an appointed intermediary whose status
 * is not one that blocks sales ({@code nsure.distribution.sales-block-statuses}, demo default SUSPENDED and LAPSED), whose
 * appointment has not expired, and whose active agreement, if any, covers the product. Historical statements and
 * commission are never touched by a block.
 */
@Component
public class SalesAuthority {

    private final EntityManager em;
    private final Set<String> blocking;

    public SalesAuthority(EntityManager em,
            @Value("${nsure.distribution.sales-block-statuses:SUSPENDED,LAPSED}") String blocking) {
        this.em = em;
        this.blocking = DistributionService.statuses(blocking);
    }

    /** Null when the intermediary may sell the product today; otherwise why not. */
    public String problem(String intermediaryCode, String product) {
        if (intermediaryCode == null || intermediaryCode.isBlank()) {
            return null;
        }
        Intermediary intermediary = em.createQuery("select i from Intermediary i where i.ref = :ref", Intermediary.class)
                .setParameter("ref", intermediaryCode.trim()).getResultStream().findFirst().orElse(null);
        if (intermediary == null) {
            return "Intermediary " + intermediaryCode + " is not an appointed agent or broker.";
        }
        if (blocking.contains(intermediary.getStatus())) {
            return intermediary.getName() + "'s appointment is " + intermediary.getStatus().toLowerCase(Locale.ROOT)
                    + (intermediary.getStatusReason() == null ? "" : " (" + intermediary.getStatusReason() + ")")
                    + "; they cannot sell new business.";
        }
        LocalDate today = LocalDate.now();
        if (intermediary.getAppointmentExpiry().isBefore(today)) {
            return intermediary.getName() + "'s appointment expired on " + intermediary.getAppointmentExpiry()
                    + "; they cannot sell new business.";
        }
        Agreement agreement = CommissionEventHandler.activeAgreement(em, intermediary, today).orElse(null);
        if (agreement != null && product != null && !agreement.covers(product)) {
            return "Agreement " + agreement.getRef() + " of " + intermediary.getName() + " does not cover " + product + ".";
        }
        return null;
    }
}
