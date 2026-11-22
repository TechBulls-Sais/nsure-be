package com.nsure.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers pending outbox events to their handlers, each delivery in its own transaction. A handler that throws is
 * retried with backoff and left Failed after {@code nsure.integration.max-attempts}; operators replay it from the
 * integration dashboard. Handlers may publish further events; the dispatcher keeps going until the queue is quiet.
 */
@Service
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH = 200;
    private static final int MAX_ROUNDS = 10;
    /** How long a held delivery waits before checking again whether the earlier event got through. */
    private static final int HOLD_SECONDS = 30;
    private static final int DELIVERED = 1;
    private static final int HELD = 0;
    private static final int FAILED = -1;

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Map<String, DomainEventHandler> handlers;
    private final int maxAttempts;
    private final com.nsure.controls.StaffAlertService alerts;
    private final InterfaceRegistry interfaces;

    public OutboxDispatcher(EntityManager em, ObjectMapper mapper, PlatformTransactionManager transactions,
            ObjectProvider<DomainEventHandler> handlers, @Value("${nsure.integration.max-attempts:5}") int maxAttempts,
            com.nsure.controls.StaffAlertService alerts, InterfaceRegistry interfaces) {
        this.alerts = alerts;
        this.interfaces = interfaces;
        this.em = em;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.handlers = handlers.orderedStream()
                .collect(Collectors.toMap(DomainEventHandler::name, Function.identity(), (a, b) -> {
                    throw new IllegalStateException("Two event handlers are named " + a.name());
                }, LinkedHashMap::new));
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${nsure.integration.dispatch-delay-ms:5000}",
            initialDelayString = "${nsure.integration.dispatch-delay-ms:5000}")
    public void scheduled() {
        try {
            dispatch();
        } catch (RuntimeException ex) {
            log.error("Outbox dispatch failed", ex);
        }
    }

    /** Runs every due delivery now. Synchronized: one dispatcher per deployment, so a delivery never runs twice. */
    public synchronized DispatchResult dispatch() {
        int delivered = 0;
        int failed = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Long> due = tx.execute(status -> em.createQuery(
                            "select d.id from EventDelivery d where d.status = :pending and d.nextAttemptAt <= :now"
                                    + " order by d.id asc", Long.class)
                    .setParameter("pending", EventDelivery.PENDING)
                    .setParameter("now", LocalDateTime.now())
                    .setMaxResults(BATCH)
                    .getResultList());
            if (due == null || due.isEmpty()) {
                break;
            }
            for (Long id : due) {
                int outcome = deliver(id);
                if (outcome == DELIVERED) {
                    delivered++;
                } else if (outcome == FAILED) {
                    failed++;
                }
            }
        }
        return new DispatchResult(delivered, failed);
    }

    /** DELIVERED, HELD (an earlier event of the aggregate must go first) or FAILED. */
    private int deliver(Long deliveryId) {
        boolean[] held = {false};
        try {
            tx.executeWithoutResult(status -> {
                EventDelivery delivery = em.find(EventDelivery.class, deliveryId);
                if (delivery == null || !EventDelivery.PENDING.equals(delivery.getStatus())) {
                    return;
                }
                String waiting = earlierUndelivered(delivery);
                if (waiting != null) {
                    delivery.heldFor(waiting, HOLD_SECONDS);
                    held[0] = true;
                    if (waiting.endsWith(BLOCKED)) {
                        gapAlert(delivery, waiting);
                    }
                    return;
                }
                DomainEventHandler handler = handlers.get(delivery.getHandler());
                if (handler == null) {
                    throw new IllegalStateException("No handler named " + delivery.getHandler() + " is deployed");
                }
                DomainEvent event = toDomainEvent(delivery.getEvent());
                // NS-API-004: a newer contract version than the consumer knows is refused, never guessed at.
                if (event.schemaVersion() > handler.supportedSchemaVersion()) {
                    delivery.refused("Event schema version " + event.schemaVersion() + " of " + event.eventType()
                            + " is newer than " + handler.name() + " supports (" + handler.supportedSchemaVersion()
                            + "). Upgrade the consumer, then replay.");
                    return;
                }
                String ignore = handler.ignoreReason(event);
                if (ignore != null) {
                    delivery.ignored("Ignored: " + ignore);
                    return;
                }
                // AT-SH12-02: an unavailable interface is a transient failure; the event stays committed and queued.
                String down = interfaces.outage(handler.name());
                if (down != null) {
                    throw new InterfaceUnavailableException(down);
                }
                handler.handle(event);
                delivery.delivered();
            });
            return held[0] ? HELD : DELIVERED;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            String failureClass = classify(ex);
            log.warn("Delivery {} failed ({}): {}", deliveryId, failureClass, message);
            tx.executeWithoutResult(status -> {
                EventDelivery delivery = em.find(EventDelivery.class, deliveryId);
                if (delivery != null) {
                    delivery.failed(message, maxAttempts, failureClass);
                    if (EventDelivery.FAILED.equals(delivery.getStatus())) {
                        deadLetterAlert(delivery);
                    }
                }
            });
            return FAILED;
        }
    }

    /**
     * Why this delivery must wait, or null: each aggregate's events reach a handler in version order (NS-API-004),
     * so a later event waits while an earlier one is pending or failed for the same handler. A failed earlier event
     * keeps the later ones waiting until it is replayed, which shows the gap on the integration dashboard.
     */
    private String earlierUndelivered(EventDelivery delivery) {
        OutboxEvent event = delivery.getEvent();
        if (event.getAggregateVersion() == null) {
            return null;
        }
        return em.createQuery("select d from EventDelivery d where d.handler = :handler"
                        + " and d.event.aggregateRef = :ref and d.event.aggregateVersion < :version"
                        + " and d.status not in :done order by d.event.aggregateVersion asc", EventDelivery.class)
                .setParameter("handler", delivery.getHandler())
                .setParameter("ref", event.getAggregateRef())
                .setParameter("version", event.getAggregateVersion())
                .setParameter("done", List.of(EventDelivery.DELIVERED, EventDelivery.IGNORED))
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .map(earlier -> "Waiting for " + earlier.getEvent().getEventType() + " (version "
                        + earlier.getEvent().getAggregateVersion() + " of " + event.getAggregateRef() + ", "
                        + earlier.getStatus().toLowerCase(java.util.Locale.ROOT) + ") to reach " + delivery.getHandler()
                        + (EventDelivery.FAILED.equals(earlier.getStatus()) ? BLOCKED : ""))
                .orElse(null);
    }

    /** Suffix of a hold behind a failed earlier event: a sequence gap that will not close on its own. */
    private static final String BLOCKED = " · sequence gap: replay or reconcile the failed event";

    /**
     * NS-API-004: a gap in an aggregate's sequence that will not close by itself (the earlier event failed) raises a
     * reconciliation alert for integration staff, once per blocked handler and aggregate.
     */
    private void gapAlert(EventDelivery delivery, String waiting) {
        String key = "event-gap:" + delivery.getHandler() + ":" + delivery.getEvent().getAggregateRef();
        alerts.raise("EVENT_SEQUENCE_GAP", "READ_INTEGRATION", "Events held behind a failed delivery",
                waiting + ". Later events of " + delivery.getEvent().getAggregateRef() + " for " + delivery.getHandler()
                        + " wait until it is replayed or reconciled (Integration monitor).",
                key.length() <= 120 ? key : key.substring(0, 120), "system");
    }

    /**
     * NS-SH12-003: an outage, a timeout or a database hiccup is worth retrying; an event whose stored payload cannot
     * even be read never will be, so it is dead-lettered at once with the reason.
     */
    static String classify(RuntimeException ex) {
        if (ex instanceof InterfaceUnavailableException) {
            return EventDelivery.TRANSIENT;
        }
        if (ex instanceof IllegalStateException && ex.getCause() instanceof JsonProcessingException) {
            return EventDelivery.PERMANENT;
        }
        return EventDelivery.TRANSIENT;
    }

    /**
     * AT-SH12-02: the interface is back, so its consumers' transient dead letters go back in the queue and its pending
     * retries are due now. Returns how many deliveries were resumed. Permanent failures stay for an operator.
     */
    public int resume(List<String> handlerNames) {
        if (handlerNames.isEmpty()) {
            return 0;
        }
        Integer resumed = tx.execute(status -> {
            int count = 0;
            for (EventDelivery d : em.createQuery("select d from EventDelivery d where d.handler in :handlers"
                            + " and ((d.status = :failed and (d.failureClass is null or d.failureClass = :transient))"
                            + " or (d.status = :pending and d.attempts > 0))", EventDelivery.class)
                    .setParameter("handlers", handlerNames)
                    .setParameter("failed", EventDelivery.FAILED)
                    .setParameter("pending", EventDelivery.PENDING)
                    .setParameter("transient", EventDelivery.TRANSIENT)
                    .getResultList()) {
                if (EventDelivery.FAILED.equals(d.getStatus())) {
                    d.replay();
                } else {
                    d.expedite();
                }
                count++;
            }
            return count;
        });
        return resumed == null ? 0 : resumed;
    }

    /** An operator asks for a waiting retry to run now instead of after its backoff. */
    public void expedite(Long deliveryId) {
        tx.executeWithoutResult(status -> {
            EventDelivery delivery = em.find(EventDelivery.class, deliveryId);
            if (delivery == null) {
                throw new NotFoundException("Delivery " + deliveryId + " not found");
            }
            if (!EventDelivery.PENDING.equals(delivery.getStatus()) || delivery.getAttempts() == 0) {
                throw new ConflictException("Only a pending delivery that has already failed can be retried now; this"
                        + " one is " + delivery.getStatus() + " after " + delivery.getAttempts() + " attempt(s).");
            }
            delivery.expedite();
        });
    }

    /**
     * NS-SH12-003 / AT-SH12-02: a dead-lettered delivery is alerted to integration staff with its owner and reason,
     * once each time it is dead-lettered.
     */
    private void deadLetterAlert(EventDelivery delivery) {
        String owner = interfaces.forHandler(delivery.getHandler()).map(InterfaceRegistry.Entry::exceptionOwner)
                .orElse("Integration engineering");
        String key = "dead-letter:" + delivery.getId() + ":" + delivery.getDeadLetteredAt().withNano(0);
        String body = delivery.getEvent().getAggregateRef() + " after " + delivery.getAttempts() + " attempt(s) ("
                + delivery.getFailureClass() + "). Business owner: " + owner
                + ". Replay it from the Integration monitor once the cause is fixed. Reason: " + delivery.getLastError();
        alerts.raise("DELIVERY_DEAD_LETTERED", "READ_INTEGRATION", "Delivery dead-lettered: "
                        + delivery.getEvent().getEventType() + " to " + delivery.getHandler(),
                body.length() <= 1000 ? body : body.substring(0, 1000),
                key.length() <= 120 ? key : key.substring(0, 120), "system");
    }

    /** Puts a failed delivery back in the queue (NS-SH12-005). The event payload itself is never editable. */
    public void requeue(Long deliveryId) {
        tx.executeWithoutResult(status -> {
            EventDelivery delivery = em.find(EventDelivery.class, deliveryId);
            if (delivery == null) {
                throw new NotFoundException("Delivery " + deliveryId + " not found");
            }
            if (!EventDelivery.FAILED.equals(delivery.getStatus())) {
                throw new ConflictException(
                        "Only a failed delivery can be replayed; this one is " + delivery.getStatus() + ".");
            }
            delivery.replay();
        });
    }

    DomainEvent toDomainEvent(OutboxEvent event) {
        try {
            return new DomainEvent(event.getEventId(), event.getEventType(), event.getAggregateRef(),
                    event.getCorrelationId(), event.getOccurredAt(), mapper.readTree(event.getPayload()),
                    event.getSchemaVersion(), event.getAggregateVersion());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Event " + event.getEventId() + " payload is not valid JSON", ex);
        }
    }

    public record DispatchResult(int delivered, int failed) {
    }
}
