package com.nsure.communication;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued messages through the (simulated) Email / WhatsApp provider (NS-SH09B-004/005). Each message is marked
 * SUBMITTED in its own transaction before the provider is called, and ACCEPTED with the provider's id after; that is
 * not delivery. A provider error keeps the message queued for the next run; after {@link #MAX_ATTEMPTS} it moves to the
 * failed queue, where staff retry it. Delivery is only ever set by the provider's delivery report: the simulated
 * provider sends its report on the next run, through the same path as a real status callback. Messaging never touches
 * the business transaction that caused it.
 */
@Service
public class CommunicationDispatcher {

    static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(CommunicationDispatcher.class);

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final MessageTrail trail;
    private final boolean simulatedReports;

    public CommunicationDispatcher(EntityManager em, PlatformTransactionManager transactions, MessageTrail trail,
            @Value("${nsure.communications.simulated-delivery-reports:true}") boolean simulatedReports) {
        this.em = em;
        this.trail = trail;
        this.simulatedReports = simulatedReports;
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

    /**
     * One run: first the simulated provider's delivery reports for messages it accepted earlier, then one send attempt
     * for every queued message. Synchronized so a message is never sent twice at once.
     */
    public synchronized CommunicationViews.DispatchView dispatch() {
        int delivered = simulatedReports ? deliveryReports() : 0;
        List<Long> queued = tx.execute(status -> em.createQuery(
                        "select m.id from CommunicationMessage m where m.status = :queued order by m.id asc", Long.class)
                .setParameter("queued", CommunicationMessage.QUEUED)
                .setMaxResults(BATCH)
                .getResultList());
        int accepted = 0;
        int retrying = 0;
        int failed = 0;
        int processed = 0;
        for (Long id : queued == null ? List.<Long>of() : queued) {
            Handover handover = tx.execute(status -> submit(id));
            if (handover == null) {
                continue;
            }
            String providerId = null;
            String error = null;
            try {
                providerId = SimulatedProvider.send(handover.channel(), handover.contact());
            } catch (SimulatedProvider.ProviderException ex) {
                error = ex.getMessage();
            }
            String reportedId = providerId;
            String reportedError = error;
            String outcome = tx.execute(status -> answer(id, reportedId, reportedError));
            processed++;
            switch (outcome == null ? "" : outcome) {
                case CommunicationMessage.ACCEPTED -> accepted++;
                case CommunicationMessage.FAILED -> failed++;
                default -> retrying++;
            }
        }
        return new CommunicationViews.DispatchView(processed, accepted, delivered, retrying, failed);
    }

    private record Handover(String channel, String contact) {
    }

    /** QUEUED → SUBMITTED, committed before the provider is called. */
    private Handover submit(Long id) {
        CommunicationMessage message = em.find(CommunicationMessage.class, id);
        if (message == null || !CommunicationMessage.QUEUED.equals(message.getStatus())) {
            return null;
        }
        message.submitted();
        trail.changed(message, "Handed to the " + message.getChannel().toLowerCase(Locale.ROOT) + " provider (attempt "
                + message.getAttempts() + " of " + MAX_ATTEMPTS + ")", MessageEvent.SYSTEM, null, "system");
        return new Handover(message.getChannel(), message.getContact());
    }

    /** The provider's answer: accepted with its id, or an error that is retried until the failed queue. */
    private String answer(Long id, String providerId, String error) {
        CommunicationMessage message = em.find(CommunicationMessage.class, id);
        if (message == null || !CommunicationMessage.SUBMITTED.equals(message.getStatus())) {
            return null;
        }
        if (providerId != null) {
            message.accepted(providerId);
            trail.changed(message, "Accepted by the provider as " + providerId + "; not yet delivered",
                    MessageEvent.PROVIDER, null, "provider");
        } else {
            message.failedAttempt(error + " (attempt " + message.getAttempts() + " of " + MAX_ATTEMPTS + ")",
                    MAX_ATTEMPTS);
            trail.changed(message, message.getLastError(), MessageEvent.PROVIDER, null, "provider");
            log.warn("Message {} not sent: {}", message.getRef(), message.getLastError());
        }
        return message.getStatus();
    }

    /** The simulated provider reports delivery of what it accepted on an earlier run. */
    private int deliveryReports() {
        List<Long> accepted = tx.execute(status -> em.createQuery(
                        "select m.id from CommunicationMessage m where m.status = :accepted order by m.id asc", Long.class)
                .setParameter("accepted", CommunicationMessage.ACCEPTED)
                .setMaxResults(BATCH)
                .getResultList());
        int delivered = 0;
        for (Long id : accepted == null ? List.<Long>of() : accepted) {
            Boolean done = tx.execute(status -> {
                CommunicationMessage message = em.find(CommunicationMessage.class, id);
                if (message == null || !message.providerStatus(CommunicationMessage.DELIVERED, null)) {
                    return false;
                }
                trail.changed(message, "Delivery reported by the simulated provider", MessageEvent.PROVIDER,
                        "{\"providerMessageId\":\"" + message.getProviderMessageId() + "\",\"status\":\"DELIVERED\","
                                + "\"simulated\":true}", "provider");
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                delivered++;
            }
        }
        return delivered;
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
