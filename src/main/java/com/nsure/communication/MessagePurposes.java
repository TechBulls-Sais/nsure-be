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

    /** Words that name a marketing purpose; refused for templates. */
    static final Set<String> PROMOTIONAL = Set.of("PROMOTIONAL", "MARKETING", "CAMPAIGN");

    private static final Map<String, String> BY_EVENT = Map.of(
            Events.POLICY_ISSUED, POLICY_ISSUANCE,
            Events.PREMIUM_RECEIVED, PAYMENT_CONFIRMATION,
            Events.PREMIUM_DUE_REMINDER, PREMIUM_REMINDER,
            Events.CLAIM_RECEIVED, CLAIM_PROGRESS,
            Events.CLAIM_APPROVED, CLAIM_PROGRESS,
            Events.CLAIM_REJECTED, CLAIM_PROGRESS,
            Events.CLAIM_PAID, CLAIM_PROGRESS,
            Events.SERVICE_CASE_OPENED, SERVICE_UPDATE,
            Events.OPERATIONAL_NOTICE, OPERATIONAL_NOTICE,
            CommunicationService.SERVICE_MESSAGE, SERVICE_UPDATE);

    private MessagePurposes() {
    }

    static String of(String eventType) {
        return BY_EVENT.getOrDefault(eventType, SERVICE_UPDATE);
    }
}
