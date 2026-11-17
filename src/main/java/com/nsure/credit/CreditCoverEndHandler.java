package com.nsure.credit;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * IL05 events: when a credit policy ends by cancellation, lapse or another termination, the loan's credit cover ends
 * with it and CreditCoverTerminated is published. Claims and early settlements close the loan themselves first, so
 * their PolicyTerminated finds nothing left to do.
 */
@Component
public class CreditCoverEndHandler implements DomainEventHandler {

    private final CreditService credit;

    public CreditCoverEndHandler(CreditService credit) {
        this.credit = credit;
    }

    @Override
    public String name() {
        return "credit.cover-ended";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.POLICY_TERMINATED);
    }

    @Override
    @Transactional
    public void handle(DomainEvent event) {
        credit.coverEndedWithPolicy(event.aggregateRef(), event.text("reason"), event.text("terminatedOn"),
                event.correlationId());
    }
}
