package com.nsure.portfolio;

import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.Events;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Generates the policy schedule PDF once a policy is issued (spec 01 BR-60), after the payment has committed. */
@Component
public class PolicyScheduleOnIssue implements DomainEventHandler {

    private final PolicyDocumentService documents;

    public PolicyScheduleOnIssue(PolicyDocumentService documents) {
        this.documents = documents;
    }

    @Override
    public String name() {
        return "documents.policy-schedule";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of(Events.POLICY_ISSUED);
    }

    @Override
    public void handle(DomainEvent event) {
        documents.issueOnActivation(event.aggregateRef());
    }
}
