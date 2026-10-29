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
            Events.RISK_CEDED, Events.RECOVERY_RECOGNISED);

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
