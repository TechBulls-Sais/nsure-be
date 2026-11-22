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
 * Sends queued messages through the (simulated) Email / WhatsApp / SMS provider (NS-SH09B-004/005). Each message is
 * marked SUBMITTED in its own transaction before the provider is called, and ACCEPTED with the provider's id after;
 * that is not delivery. The provider's error is classified: a <b>transient</b> one (a timeout, the provider busy) is
 * retried with a bounded exponential backoff ({@code nsure.communications.retry-base-seconds}, doubling, at most
 * {@code retry-max-seconds}) until {@code max-attempts}; a <b>permanent</b> one (an address or number the provider
 * rejects) goes straight to the failed queue, where staff own it. A message that lands there is offered to the
 * {@link DeliveryRules#fallback fallback rules}. Delivery is only ever set by the provider's delivery report: the
 * simulated provider sends its report on the next run, through the same path as a real status callback. The simulated
 * provider also reviews WhatsApp templates submitted for its approval. Messaging never touches the business
 * transaction that caused it.
 */
@Service
public class CommunicationDispatcher {

    private static final int BATCH = 200;
    private static final Logger log = LoggerFactory.getLogger(CommunicationDispatcher.class);

    private final EntityManager em;
    private final TransactionTemplate tx;
    private final MessageTrail trail;
    private final DeliveryRules rules;
    private final boolean simulatedReports;
    private final int maxAttempts;
    private final int baseSeconds;
    private final int maxSeconds;

    public CommunicationDispatcher(EntityManager em, PlatformTransactionManager transactions, MessageTrail trail,
            DeliveryRules rules,
            @Value("${nsure.communications.simulated-delivery-reports:true}") boolean simulatedReports,
            @Value("${nsure.communications.max-attempts:3}") int maxAttempts,
            @Value("${nsure.communications.retry-base-seconds:30}") int baseSeconds,
            @Value("${nsure.communications.retry-max-seconds:900}") int maxSeconds) {
        this.em = em;
        this.trail = trail;
        this.rules = rules;
        this.simulatedReports = simulatedReports;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.baseSeconds = Math.max(0, baseSeconds);
        this.maxSeconds = Math.max(this.baseSeconds, maxSeconds);
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
        if (simulatedReports) {
            tx.executeWithoutResult(status -> reviewTemplates());
        }
        // A message waiting out its backoff is left for a later run (NS-SH09B-005).
        List<Long> queued = tx.execute(status -> em.createQuery(
                        "select m.id from CommunicationMessage m where m.status = :queued and (m.nextAttemptAt is null"
                                + " or m.nextAttemptAt <= :now) order by m.id asc", Long.class)
                .setParameter("queued", CommunicationMessage.QUEUED)
                .setParameter("now", java.time.LocalDateTime.now())
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
            boolean permanent = false;
            try {
                providerId = SimulatedProvider.send(handover.channel(), handover.contact(), handover.attempt());
            } catch (SimulatedProvider.ProviderException ex) {
                error = ex.getMessage();
                permanent = ex.permanent;
            }
            String reportedId = providerId;
            String reportedError = error;
            boolean reportedPermanent = permanent;
            String outcome = tx.execute(status -> answer(id, reportedId, reportedError, reportedPermanent));
            processed++;
            switch (outcome == null ? "" : outcome) {
                case CommunicationMessage.ACCEPTED -> accepted++;
                case CommunicationMessage.FAILED -> failed++;
                default -> retrying++;
            }
        }
        return new CommunicationViews.DispatchView(processed, accepted, delivered, retrying, failed);
    }

    private record Handover(String channel, String contact, int attempt) {
    }

    /** QUEUED → SUBMITTED, committed before the provider is called. */
    private Handover submit(Long id) {
        CommunicationMessage message = em.find(CommunicationMessage.class, id);
        if (message == null || !CommunicationMessage.QUEUED.equals(message.getStatus())) {
            return null;
        }
        message.submitted();
        trail.changed(message, "Handed to the " + DeliveryRules.label(message.getChannel()) + " provider (attempt "
                + message.getAttempts() + " of " + maxAttempts + ")", MessageEvent.SYSTEM, null, "system");
        return new Handover(message.getChannel(), message.getContact(), message.getAttempts());
    }

    /**
     * The provider's answer: accepted with its id; a transient error queued again after its backoff until the attempts
     * run out; a permanent error straight to the failed queue. A message that lands in the failed queue is offered to
     * the fallback rules in the same transaction.
     */
    private String answer(Long id, String providerId, String error, boolean permanent) {
        CommunicationMessage message = em.find(CommunicationMessage.class, id);
        if (message == null || !CommunicationMessage.SUBMITTED.equals(message.getStatus())) {
            return null;
        }
        if (providerId != null) {
            message.accepted(providerId);
            trail.changed(message, "Accepted by the provider as " + providerId + "; not yet delivered",
                    MessageEvent.PROVIDER, null, "provider");
            return message.getStatus();
        }
        if (permanent) {
            message.failedPermanently("Permanent: " + error + " (attempt " + message.getAttempts() + "; not retried)");
            trail.changed(message, message.getLastError() + ". In the failed queue for the communications team.",
                    MessageEvent.PROVIDER, null, "provider");
        } else {
            java.time.LocalDateTime next = java.time.LocalDateTime.now().plusSeconds(backoffSeconds(message.getAttempts()));
            message.failedAttempt("Transient: " + error + " (attempt " + message.getAttempts() + " of " + maxAttempts
                    + ")", maxAttempts, next);
            trail.changed(message, message.getLastError() + (CommunicationMessage.FAILED.equals(message.getStatus())
                    ? ". Attempts used up: in the failed queue for the communications team."
                    : ". Next attempt not before " + next.withNano(0) + "."), MessageEvent.PROVIDER, null, "provider");
        }
        log.warn("Message {} not sent: {}", message.getRef(), message.getLastError());
        if (CommunicationMessage.FAILED.equals(message.getStatus())) {
            rules.fallback(message);
        }
        return message.getStatus();
    }

    /** Bounded exponential backoff: base, 2 x base, 4 x base ... capped at the maximum. */
    long backoffSeconds(int attempt) {
        long wait = (long) baseSeconds << Math.min(20, Math.max(0, attempt - 1));
        return Math.min(wait, maxSeconds);
    }

    /**
     * The simulated WhatsApp provider's review of templates submitted to it (BP-COMMS). It refuses what the real
     * providers commonly refuse (plain http links, more than ten variables, over 1024 characters) and approves the
     * rest. A real adapter replaces this with the provider's status callback.
     */
    private void reviewTemplates() {
        for (MessageTemplate t : em.createQuery("select t from MessageTemplate t where t.providerStatus = :submitted",
                        MessageTemplate.class)
                .setParameter("submitted", MessageTemplate.PROVIDER_SUBMITTED).getResultList()) {
            String body = t.getBody() == null ? "" : t.getBody();
            String refusal = body.contains("http://") ? "links must use https"
                    : MergeFields.placeholders(t.getSubject(), body).size() > 10 ? "more than ten variables"
                    : body.length() > 1024 ? "longer than 1024 characters" : null;
            t.providerDecision(refusal == null, (refusal == null ? "Approved" : "Rejected: " + refusal)
                    + " by the simulated WhatsApp provider on " + java.time.LocalDate.now());
        }
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

        /**
         * Simulated outcomes, so every path can be shown on demo data: an email address containing "fail", or a
         * number ending in 000, is rejected for good (permanent); one containing "busy", or a number ending in 999,
         * times out on every attempt (transient); one containing "slow", or a number ending in 998, times out on the
         * first attempt only.
         */
        static String send(String channel, String contact, int attempt) {
            String value = contact == null ? "" : contact.trim();
            if (value.isEmpty()) {
                throw new ProviderException("No contact to send to", true);
            }
            boolean email = CommunicationService.EMAIL.equals(channel);
            String lower = value.toLowerCase(Locale.ROOT);
            String number = CommunicationService.digits(value);
            String name = DeliveryRules.label(channel) + " provider";
            if (email ? lower.contains("fail") : number.endsWith("000")) {
                throw new ProviderException(name + (email ? " rejected the address" : " could not reach the number"),
                        true);
            }
            if (email ? lower.contains("busy") : number.endsWith("999")) {
                throw new ProviderException(name + " timed out", false);
            }
            if (attempt <= 1 && (email ? lower.contains("slow") : number.endsWith("998"))) {
                throw new ProviderException(name + " timed out", false);
            }
            String prefix = email ? "EML-" : DeliveryRules.SMS.equals(channel) ? "SMS-" : "WA-";
            return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase(Locale.ROOT);
        }

        static final class ProviderException extends RuntimeException {
            final boolean permanent;

            ProviderException(String message, boolean permanent) {
                super(message);
                this.permanent = permanent;
            }
        }
    }
}
