package com.nsure.servicing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily policy servicing (NS-IL04-005, NS-IL03-005): age reviews and cessation tasks raised ahead of time, dependants
 * past their cessation age ceased, and contractual benefits that have fallen due raised for assessment.
 */
@Component
public class ServicingJobs {

    private static final Logger LOG = LoggerFactory.getLogger(ServicingJobs.class);

    private final ServicingService servicing;

    public ServicingJobs(ServicingService servicing) {
        this.servicing = servicing;
    }

    @Scheduled(cron = "${nsure.servicing.review-cron:0 35 1 * * *}")
    public void daily() {
        ServicingViews.RunResult reviews = servicing.runLifeReviews("system");
        ServicingViews.RunResult benefits = servicing.runBenefits("system");
        ServicingViews.RunResult ended = servicing.endCoverAtTerm("system");
        if (ended.created() > 0) {
            LOG.info("servicing: cover ended at term on {}", ended.refs());
        }
        if (reviews.created() > 0 || benefits.created() > 0) {
            LOG.info("servicing: {} life review item(s) {}, {} benefit(s) due {}", reviews.created(), reviews.refs(),
                    benefits.created(), benefits.refs());
        }
    }
}
