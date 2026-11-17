package com.nsure.credit;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily credit life processing: cover ends when the loan term runs out (IL05). */
@Component
public class CreditJobs {

    private static final Logger LOG = LoggerFactory.getLogger(CreditJobs.class);

    private final CreditService credit;

    public CreditJobs(CreditService credit) {
        this.credit = credit;
    }

    @Scheduled(cron = "${nsure.credit.term-end-cron:0 45 1 * * *}")
    public void endExpiredTerms() {
        List<String> ended = credit.endExpiredTerms();
        if (!ended.isEmpty()) {
            LOG.info("credit term end: cover ended on {} loans: {}", ended.size(), ended);
        }
    }
}
