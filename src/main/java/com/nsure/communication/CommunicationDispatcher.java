package com.nsure.communication;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued messages through the (simulated) Email / WhatsApp provider, each message in its own transaction.
 * A provider error keeps the message queued for the next run; after {@link #MAX_ATTEMPTS} it moves to the failed
 * queue, where staff retry it (NS-SH09B-005). Messaging never touches the business transaction that caused it.
 */
@Service
public class CommunicationDispatcher {

    static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(CommunicationDispatcher.class);

    private final EntityManager em;
    private final TransactionTemplate tx;

    public CommunicationDispatcher(EntityManager em, PlatformTransactionManager transactions) {
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${nsure.communications.dispatch-delay-ms:7000}",
            initialDelayString = "${nsure.communications.dispatch-delay-ms:7000}")
    public void scheduled() {
        try {
            dispatch();
        } catch (RuntimeException ex) {
            log.error("Message dispatch failed", ex);
        }
    }

    /** One send attempt for every queued message. Synchronized so a message is never sent twice at once. */
    public synchronized CommunicationViews.DispatchView dispatch() {
        List<Long> queued = tx.execute(status -> em.createQuery(
                        "select m.id from CommunicationMessage m where m.status = :queued order by m.id asc", Long.class)
                .setParameter("queued", CommunicationMessage.QUEUED)
                .setMaxResults(BATCH)
                .getResultList());
        int delivered = 0;
        int retrying = 0;
        int failed = 0;
        int processed = 0;
        for (Long id : queued == null ? List.<Long>of() : queued) {
            String outcome = tx.execute(status -> send(id));
            if (outcome == null) {
                continue;
            }
            processed++;
            switch (outcome) {
                case CommunicationMessage.DELIVERED -> delivered++;
                case CommunicationMessage.FAILED -> failed++;
                default -> retrying++;
            }
        }
        return new CommunicationViews.DispatchView(processed, delivered, retrying, failed);
    }

    private String send(Long id) {
        CommunicationMessage message = em.find(CommunicationMessage.class, id);
        if (message == null || !CommunicationMessage.QUEUED.equals(message.getStatus())) {
            return null;
        }
        try {
            message.delivered(SimulatedProvider.send(message.getChannel(), message.getContact()));
        } catch (SimulatedProvider.ProviderException ex) {
            message.failedAttempt(ex.getMessage() + " (attempt " + (message.getAttempts() + 1) + " of "
                    + MAX_ATTEMPTS + ")", MAX_ATTEMPTS);
            log.warn("Message {} not sent: {}", message.getRef(), message.getLastError());
        }
        return message.getStatus();
    }

    /** Stands in for the Email and WhatsApp provider adapters until a real connector is configured. */
    static final class SimulatedProvider {

        private SimulatedProvider() {
        }

        static String send(String channel, String contact) {
            String value = contact == null ? "" : contact.trim();
            if (value.isEmpty()) {
                throw new ProviderException("No contact to send to");
            }
            if (CommunicationService.EMAIL.equals(channel) && value.toLowerCase(Locale.ROOT).contains("fail")) {
                throw new ProviderException("Email provider rejected the address");
            }
            if (CommunicationService.WHATSAPP.equals(channel) && CommunicationService.digits(value).endsWith("000")) {
                throw new ProviderException("WhatsApp provider could not reach the number");
            }
            String prefix = CommunicationService.EMAIL.equals(channel) ? "EML-" : "WA-";
            return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        }

        static final class ProviderException extends RuntimeException {
            ProviderException(String message) {
                super(message);
            }
        }
    }
}
