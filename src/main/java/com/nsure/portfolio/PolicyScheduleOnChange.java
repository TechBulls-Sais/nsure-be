package com.nsure.portfolio;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * IL03 (NS-IL03-003, IL03-EVENTS): an applied endorsement (PolicyChanged with an endorsement reference) reissues the
 * policy schedule, the documents its preview listed; the replaced schedule is kept, superseded.
 */
@Component
public class PolicyScheduleOnChange implements DomainEventHandler {

    private final PolicyDocumentService documents;

    public PolicyScheduleOnChange(PolicyDocumentService documents) {
        this.documents = documents;
    }

    @Override
    public String name() {
        return "documents.policy-schedule-change";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.POLICY_CHANGED);
    }

    @Override
    public String ignoreReason(DomainEvent event) {
        String endorsement = event.text("endorsementRef");
        return endorsement == null || endorsement.isBlank()
                ? "Not an endorsement (beneficiary or other change): the schedule is reissued on request." : null;
    }

    @Override
    public void handle(DomainEvent event) {
        documents.reissueForEndorsement(event.aggregateRef(), event.text("endorsementRef"));
    }
}
