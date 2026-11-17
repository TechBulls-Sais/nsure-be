package com.nsure.communication;

import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The trail of each message (NS-SH09B-007) and its events on the bus (SH09B): CommunicationRequested when a message is
 * created (queued or suppressed), MessageDelivered on the provider's delivery report, MessageFailed when it lands in the
 * failed queue. Runs inside the transaction that changes the message, so the event exists if and only if the change
 * does. Payloads carry references, never the content or the contact (NS-API-003).
 */
@Component
class MessageTrail {

    private final EntityManager em;
    private final Outbox outbox;

    MessageTrail(EntityManager em, Outbox outbox) {
        this.em = em;
        this.outbox = outbox;
    }

    /** A new message, queued or suppressed. {@code correlationId}: the business event's, when it caused the message. */
    void requested(CommunicationMessage m, String actor, String correlationId) {
        String detail = CommunicationMessage.SUPPRESSED.equals(m.getStatus()) ? "Not sent: " + m.getLastError()
                : "Queued for " + m.getChannel().toLowerCase(java.util.Locale.ROOT);
        em.persist(new MessageEvent(m.getId(), m.getStatus(), detail,
                "system".equals(actor) ? MessageEvent.SYSTEM : MessageEvent.STAFF, null, actor));
        Map<String, Object> data = data(m);
        data.put("templateCode", m.getTemplateCode());
        data.put("templateVersion", m.getTemplateVersion());
        data.put("status", m.getStatus());
        data.put("reason", CommunicationMessage.SUPPRESSED.equals(m.getStatus()) ? m.getLastError() : null);
        outbox.publish(Events.COMMUNICATION_REQUESTED, m.getRef(), data, actor, correlationId);
    }

    /** A change of state, with the provider's raw report as evidence when there is one. */
    void changed(CommunicationMessage m, String detail, String source, String rawPayload, String actor) {
        em.persist(new MessageEvent(m.getId(), m.getStatus(), detail, source, rawPayload, actor));
        if (CommunicationMessage.DELIVERED.equals(m.getStatus())) {
            Map<String, Object> data = data(m);
            data.put("providerMessageId", m.getProviderMessageId());
            data.put("status", m.getStatus());
            outbox.publish(Events.MESSAGE_DELIVERED, m.getRef(), data, actor);
        } else if (CommunicationMessage.FAILED.equals(m.getStatus())) {
            Map<String, Object> data = data(m);
            data.put("providerMessageId", m.getProviderMessageId());
            data.put("reason", m.getLastError());
            outbox.publish(Events.MESSAGE_FAILED, m.getRef(), data, actor);
        }
    }

    /** A staff action that does not change the state (taking or releasing a failed message). */
    void noted(CommunicationMessage m, String detail, String actor) {
        em.persist(new MessageEvent(m.getId(), m.getStatus(), detail, MessageEvent.STAFF, null, actor));
    }

    List<CommunicationViews.MessageEventView> of(Long messageId) {
        return em.createQuery("select e from MessageEvent e where e.messageId = :id order by e.id", MessageEvent.class)
                .setParameter("id", messageId)
                .getResultStream()
                .map(e -> new CommunicationViews.MessageEventView(e.getStatus(), CommunicationViews.blank(e.getDetail()),
                        e.getSource(), CommunicationViews.blank(e.getRawPayload()), e.getActor(),
                        CommunicationViews.time(e.getOccurredAt())))
                .toList();
    }

    private static Map<String, Object> data(CommunicationMessage m) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageRef", m.getRef());
        data.put("eventType", m.getEventType());
        data.put("businessRef", m.getBusinessRef());
        data.put("customerRef", m.getCustomerRef());
        data.put("recipientRole", m.getRecipientRole());
        data.put("channel", m.getChannel());
        data.put("purpose", m.getPurpose());
        return data;
    }
}
