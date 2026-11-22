package com.nsure.finance;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Posts every monetary business event to the subledger. Non-monetary events (issue, cancel, cases) are skipped. */
@Component
public class SubledgerEventHandler implements DomainEventHandler {

    static final Set<String> MONETARY = Set.of(
            Events.PREMIUM_RECEIVED, Events.UNMATCHED_RECEIPT, Events.CLAIM_PAID,
            Events.SCHEME_INVOICE_ISSUED, Events.SPONSOR_RECEIPT_ALLOCATED,
            Events.COMMISSION_ACCRUED, Events.COMMISSION_REVERSED, Events.COMMISSION_PAID,
            Events.RISK_CEDED, Events.RECOVERY_RECOGNISED, Events.SUSPENSE_CLEARED, Events.PAYMENT_RETURNED,
            Events.BENEFIT_PAID, Events.PREMIUM_REFUNDED, Events.GROUP_PREMIUM_REFUNDED,
            // NS-SH04-005: a premium refund paid through the refund controls (ledgerAmount; zero when posted elsewhere).
            Events.REFUND_SETTLED,
            // NS-SH08-002 / NS-SH07-005: cash from the reinsurer, and an approved write-off of a recovery.
            Events.RECOVERY_RECEIVED, Events.RECOVERY_WRITTEN_OFF);

    private final Subledger subledger;

    public SubledgerEventHandler(Subledger subledger) {
        this.subledger = subledger;
    }

    @Override
    public String name() {
        return "finance.subledger";
    }

    @Override
    public Set<String> eventTypes() {
        return MONETARY;
    }

    @Override
    public void handle(DomainEvent event) {
        subledger.post(event, Subledger.SYSTEM);
    }
}
