package com.nsure.servicing;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * IL04 events that follow the policy: the lives captured at application are announced (InsuredLifeAdded) when the
 * policy is issued, and the lives still covered cease (InsuredLifeCeased) when it ends by termination, cancellation,
 * lapse or a claim. Both steps skip what is already done, so a redelivered or replayed event changes nothing.
 */
@Component
public class InsuredLivesEventHandler implements DomainEventHandler {

    private final ServicingService servicing;

    public InsuredLivesEventHandler(ServicingService servicing) {
        this.servicing = servicing;
    }

    @Override
    public String name() {
        return "servicing.insured-lives";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.POLICY_ISSUED, Events.POLICY_TERMINATED);
    }

    @Override
    @Transactional
    public void handle(DomainEvent event) {
        if (Events.POLICY_ISSUED.equals(event.eventType())) {
            servicing.announceLivesAtIssue(event.aggregateRef(), event.correlationId());
        } else {
            servicing.ceaseLivesWithPolicy(event.aggregateRef(), event.text("reason"), event.text("terminatedOn"),
                    event.correlationId());
        }
    }
}
