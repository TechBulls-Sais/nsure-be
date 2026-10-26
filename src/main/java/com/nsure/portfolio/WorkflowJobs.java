package com.nsure.portfolio;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** The demo server's scheduler_events for the policy lifecycle: hourly expiry, daily lapse. */
@Configuration
@EnableScheduling
public class WorkflowJobs {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowJobs.class);

    private final PolicyWorkflow policies;

    public WorkflowJobs(PolicyWorkflow policies) {
        this.policies = policies;
    }

    @Scheduled(cron = "${nsure.workflow.expire-cron:0 5 * * * *}")
    public void expireUnpaidPolicies() {
        List<String> expired = policies.expireUnpaid();
        if (!expired.isEmpty()) {
            LOG.info("expire_unpaid_policies: expired {} policies: {}", expired.size(), expired);
        }
    }

    @Scheduled(cron = "${nsure.workflow.lapse-cron:0 15 1 * * *}")
    public void lapsePolicies() {
        List<String> lapsed = policies.lapseOverdue();
        if (!lapsed.isEmpty()) {
            LOG.info("auto_lapse_policies: lapsed {} policies: {}", lapsed.size(), lapsed);
        }
    }
}
