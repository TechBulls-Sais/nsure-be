package com.nsure.portfolio;

import java.util.List;
import java.util.function.ToIntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * On start-up, replaces generated documents made in an older layout with one document in the current layout:
 * the policy schedule on each policy, and the claim summary on each closed claim. This also clears the duplicates
 * the old "generate as often as you like" buttons left. Each record is its own transaction, so one failure is
 * logged and skipped and never stops the application starting. Once every document is current this finds nothing
 * and does nothing. Not run by the one-shot migrate job (the schema change it relies on comes from 039 and 040).
 */
@Component
@Profile("!migrate")
@org.springframework.core.annotation.Order(2)
public class DocumentLayoutUpgrade implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DocumentLayoutUpgrade.class);

    private final PolicyDocumentService policyDocuments;
    private final ClaimDocumentService claimDocuments;

    public DocumentLayoutUpgrade(PolicyDocumentService policyDocuments, ClaimDocumentService claimDocuments) {
        this.policyDocuments = policyDocuments;
        this.claimDocuments = claimDocuments;
    }

    @Override
    public void run(ApplicationArguments args) {
        upgrade("policy schedule", "policy", policyDocuments.policiesWithOldSchedules(),
                policyDocuments::replaceOldSchedules);
        upgrade("claim summary", "claim", claimDocuments.closedClaimsWithOldSummaries(),
                claimDocuments::replaceOldSummaries);
    }

    private static void upgrade(String document, String record, List<Long> ids, ToIntFunction<Long> replace) {
        if (ids.isEmpty()) {
            return;
        }
        int replaced = 0;
        int upgraded = 0;
        for (Long id : ids) {
            try {
                int count = replace.applyAsInt(id);
                if (count > 0) {
                    replaced += count;
                    upgraded++;
                }
            } catch (RuntimeException e) {
                log.warn("Could not replace the old {} documents of {} id {}: {}", document, record, id, e.toString());
            }
        }
        log.info("Documents: replaced {} old-layout {}(s) with one current {} on each of {} {}(s)",
                replaced, document, document, upgraded, record);
    }
}
