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

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Map<String, DomainEventHandler> handlers;
    private final int maxAttempts;

    public OutboxDispatcher(EntityManager em, ObjectMapper mapper, PlatformTransactionManager transactions,
            ObjectProvider<DomainEventHandler> handlers, @Value("${nsure.integration.max-attempts:5}") int maxAttempts) {
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
                if (deliver(id)) {
                    delivered++;
                } else {
                    failed++;
                }
            }
        }
        return new DispatchResult(delivered, failed);
    }

    private boolean deliver(Long deliveryId) {
        try {
            tx.executeWithoutResult(status -> {
                EventDelivery delivery = em.find(EventDelivery.class, deliveryId);
                if (delivery == null || !EventDelivery.PENDING.equals(delivery.getStatus())) {
                    return;
                }
                DomainEventHandler handler = handlers.get(delivery.getHandler());
                if (handler == null) {
                    throw new IllegalStateException("No handler named " + delivery.getHandler() + " is deployed");
                }
                handler.handle(toDomainEvent(delivery.getEvent()));
                delivery.delivered();
            });
            return true;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            log.warn("Delivery {} failed: {}", deliveryId, message);
            tx.executeWithoutResult(status -> {
                EventDelivery delivery = em.find(EventDelivery.class, deliveryId);
                if (delivery != null) {
                    delivery.failed(message, maxAttempts);
                }
            });
            return false;
        }
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
                    event.getCorrelationId(), event.getOccurredAt(), mapper.readTree(event.getPayload()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Event " + event.getEventId() + " payload is not valid JSON", ex);
        }
    }

    public record DispatchResult(int delivered, int failed) {
    }
}
