package com.nsure.finance;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * SH04 RefundSettled for refunds paid outside the refund controls: a credit-life early-settlement refund
 * (PremiumRefunded) and a group credit settlement refund (GroupPremiumRefunded). Those events post their own journal,
 * so this RefundSettled carries a zero ledger amount. Raised once per source event.
 */
@Component
public class RefundSettledRelay implements DomainEventHandler {

    private final EntityManager em;
    private final Outbox outbox;

    public RefundSettledRelay(EntityManager em, Outbox outbox) {
        this.em = em;
        this.outbox = outbox;
    }

    @Override
    public String name() {
        return "finance.refund-settled";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.PREMIUM_REFUNDED, Events.GROUP_PREMIUM_REFUNDED);
    }

    @Override
    public void handle(DomainEvent event) {
        if (em.createQuery("select count(e) from OutboxEvent e where e.eventType = :type and e.payload like :source",
                        Long.class)
                .setParameter("type", Events.REFUND_SETTLED)
                .setParameter("source", "%\"sourceEventId\":\"" + event.eventId() + "\"%")
                .getSingleResult() > 0) {
            return;
        }
        boolean group = Events.GROUP_PREMIUM_REFUNDED.equals(event.eventType());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("refundRef", event.aggregateRef());
        data.put("source", group ? "GROUP_SETTLEMENT" : "CREDIT_EARLY_SETTLEMENT");
        data.put("sourceRef", group ? text(event, "certificateNo") : text(event, "loanRef"));
        data.put("policyRef", group ? text(event, "schemeRef") : text(event, "policyRef"));
        data.put("amount", text(event, "amount"));
        data.put("ledgerAmount", "0");
        data.put("payoutReference", text(event, "paymentReference"));
        data.put("sourceEventId", event.eventId());
        outbox.publish(Events.REFUND_SETTLED, event.aggregateRef(), data, "system", event.correlationId());
    }

    private static String text(DomainEvent event, String field) {
        String value = event.text(field);
        return value == null ? "" : value;
    }
}
