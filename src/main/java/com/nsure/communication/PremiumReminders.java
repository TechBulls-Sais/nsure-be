package com.nsure.communication;

import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.InsurancePolicy;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled premium reminders (Solution Blueprint §5: scheduled reminders). Once a day, each active policy whose
 * premium falls due within the reminder window gets one {@code PremiumDueReminder} event per due date; Communications
 * turns it into messages through the approved template, consent and opt-out like any other event. Running again the
 * same day, or the next day, never reminds twice for the same due date.
 */
@Component
public class PremiumReminders {

    private static final Logger log = LoggerFactory.getLogger(PremiumReminders.class);

    private final EntityManager em;
    private final Outbox outbox;
    private final PlatformSecurityContext security;
    private final TransactionTemplate tx;
    private final int daysAhead;

    public PremiumReminders(EntityManager em, Outbox outbox, PlatformSecurityContext security,
            PlatformTransactionManager transactions,
            @Value("${nsure.communications.reminder-days-ahead:7}") int daysAhead) {
        this.em = em;
        this.outbox = outbox;
        this.security = security;
        this.tx = new TransactionTemplate(transactions);
        this.daysAhead = daysAhead;
    }

    @Scheduled(cron = "${nsure.communications.reminder-cron:0 0 7 * * *}")
    public void scheduled() {
        Integer published = tx.execute(status -> remind("system"));
        if (published != null && published > 0) {
            log.info("Premium reminders: {} published", published);
        }
    }

    /** Runs the reminder sweep now (MANAGE_COMMUNICATION), for operations and testing. */
    public int runNow() {
        String actor = security.authenticatedUser().getUsername();
        security.authenticatedUser().validateHasPermissionTo("MANAGE_COMMUNICATION");
        Integer published = tx.execute(status -> remind(actor));
        return published == null ? 0 : published;
    }

    private int remind(String actor) {
        LocalDate today = LocalDate.now();
        List<InsurancePolicy> due = em.createQuery("select p from InsurancePolicy p where p.status = 'Active' "
                        + "and p.nextPremiumDue between :from and :to order by p.id", InsurancePolicy.class)
                .setParameter("from", today)
                .setParameter("to", today.plusDays(daysAhead))
                .getResultList();
        int published = 0;
        for (InsurancePolicy policy : due) {
            String key = policy.getRef() + "@" + policy.getNextPremiumDue();
            Long earlier = em.createQuery("select count(e) from OutboxEvent e where e.eventType = :type "
                            + "and e.aggregateRef = :key", Long.class)
                    .setParameter("type", Events.PREMIUM_DUE_REMINDER)
                    .setParameter("key", key)
                    .getSingleResult();
            if (earlier > 0) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("policyRef", policy.getRef());
            data.put("customerRef", policy.getCustomerRef());
            data.put("customerName", policy.getCustomerName());
            data.put("product", policy.getProduct());
            data.put("amount", "BWP " + (policy.getPremium() == null ? "?" : policy.getPremium().toPlainString()));
            data.put("dueDate", policy.getNextPremiumDue().toString());
            outbox.publish(Events.PREMIUM_DUE_REMINDER, key, data, actor);
            published++;
        }
        return published;
    }
}
