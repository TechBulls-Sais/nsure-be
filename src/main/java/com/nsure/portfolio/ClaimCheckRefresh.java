package com.nsure.portfolio;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On start-up, re-runs the checks once on every claim whose stored results came from older check rules (see
 * {@link ClaimWorkflow#CHECK_RULES_VERSION}), so each claim shows what the current rules say. Claims are never
 * moved; each change is written to the claim's history by "system". Claims with no event date or policy (recorded
 * before the checks existed) cannot be checked and show "Not run". Each claim is its own transaction, so one
 * failure is logged and skipped. Runs before {@link DocumentLayoutUpgrade}, so replaced summaries use these results.
 */
@Component
@Profile("!migrate")
@Order(1)
public class ClaimCheckRefresh implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaimCheckRefresh.class);

    private final ClaimWorkflow claims;

    public ClaimCheckRefresh(ClaimWorkflow claims) {
        this.claims = claims;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> ids = claims.claimsWithOldCheckResults();
        if (ids.isEmpty()) {
            return;
        }
        int changed = 0;
        for (Long id : ids) {
            try {
                if (!claims.refreshChecks(id).isEmpty()) {
                    changed++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not re-run the checks of claim id {}: {}", id, e.toString());
            }
        }
        log.info("Claim checks: re-ran {} claim(s) under the current rules; {} changed", ids.size(), changed);
    }
}
