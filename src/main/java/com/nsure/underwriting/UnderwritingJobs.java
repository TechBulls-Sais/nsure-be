package com.nsure.underwriting;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily underwriting review (NS-IL02-002/006): reopen decisions that no longer stand and remind due evidence. */
@Component
public class UnderwritingJobs {

    private static final Logger LOG = LoggerFactory.getLogger(UnderwritingJobs.class);

    private final UnderwritingService underwriting;

    public UnderwritingJobs(UnderwritingService underwriting) {
        this.underwriting = underwriting;
    }

    @Scheduled(cron = "${nsure.underwriting.review-cron:0 25 1 * * *}")
    public void reviewDue() {
        Map<String, List<String>> done = underwriting.reviewDue("system");
        if (!done.get("reopened").isEmpty() || !done.get("reminded").isEmpty()) {
            LOG.info("underwriting review: reopened {}, reminded {}", done.get("reopened"), done.get("reminded"));
        }
    }
}
