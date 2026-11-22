package com.nsure.communication;

import com.nsure.integration.Events;
import java.util.Map;
import java.util.Set;

/**
 * Why a message is sent (NS-SH09B-004, NS-SH09B-007). Every purpose Nsure sends today is a contractual or service
 * notice tied to a policy, claim or case. Promotional campaigns are a different purpose that needs its own permission,
 * consent basis and approval; none exists in this release, so a promotional template is refused.
 */
final class MessagePurposes {

    static final String POLICY_ISSUANCE = "POLICY_ISSUANCE";
    static final String PAYMENT_CONFIRMATION = "PAYMENT_CONFIRMATION";
    static final String PREMIUM_REMINDER = "PREMIUM_REMINDER";
    static final String CLAIM_PROGRESS = "CLAIM_PROGRESS";
    static final String SERVICE_UPDATE = "SERVICE_UPDATE";
    static final String OPERATIONAL_NOTICE = "OPERATIONAL_NOTICE";
    static final String EVIDENCE_REQUEST = "EVIDENCE_REQUEST";
    static final String COMMISSION_STATEMENT = "COMMISSION_STATEMENT";
    static final String LENDER_NOTICE = "LENDER_NOTICE";

    /** Words that name a marketing purpose; refused for templates. */
    static final Set<String> PROMOTIONAL = Set.of("PROMOTIONAL", "MARKETING", "CAMPAIGN");

    private static final Map<String, String> BY_EVENT = Map.ofEntries(
            Map.entry(Events.EVIDENCE_REQUESTED, EVIDENCE_REQUEST),
            Map.entry(Events.COMMISSION_PAID, COMMISSION_STATEMENT),
            Map.entry(Events.LOAN_DISBURSED, LENDER_NOTICE),
            Map.entry(Events.POLICY_ISSUED, POLICY_ISSUANCE),
            Map.entry(Events.PREMIUM_RECEIVED, PAYMENT_CONFIRMATION),
            Map.entry(Events.PREMIUM_DUE_REMINDER, PREMIUM_REMINDER),
            Map.entry(Events.CLAIM_RECEIVED, CLAIM_PROGRESS),
            Map.entry(Events.CLAIM_APPROVED, CLAIM_PROGRESS),
            Map.entry(Events.CLAIM_REJECTED, CLAIM_PROGRESS),
            Map.entry(Events.CLAIM_PAID, CLAIM_PROGRESS),
            Map.entry(Events.SERVICE_CASE_OPENED, SERVICE_UPDATE),
            Map.entry(Events.OPERATIONAL_NOTICE, OPERATIONAL_NOTICE),
            Map.entry(CommunicationService.SERVICE_MESSAGE, SERVICE_UPDATE));

    private MessagePurposes() {
    }

    static String of(String eventType) {
        return BY_EVENT.getOrDefault(eventType, SERVICE_UPDATE);
    }
}
