package com.nsure.communication;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Turns business events into customer messages after the business transaction has committed (NS-SH09B-004).
 * Idempotent through the message dedup key, so a redelivered or replayed event creates nothing new.
 */
@Component
public class CommunicationEventHandler implements DomainEventHandler {

    private final CommunicationService communications;

    public CommunicationEventHandler(CommunicationService communications) {
        this.communications = communications;
    }

    @Override
    public String name() {
        return "communications.notify";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.POLICY_ISSUED, Events.PREMIUM_RECEIVED, Events.CLAIM_PAID, Events.SERVICE_CASE_OPENED);
    }

    @Override
    public void handle(DomainEvent event) {
        communications.notifyFor(event);
    }
}
