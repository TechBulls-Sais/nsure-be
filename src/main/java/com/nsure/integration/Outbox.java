package com.nsure.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes a business event inside the caller's transaction, so the event exists if and only if the change does.
 * Delivery to handlers happens later in {@link OutboxDispatcher}; a handler outage never rolls back the business
 * change (a valid policy stays issued when messaging is down, AT-IL03-02).
 */
@Service
public class Outbox {

    private final EntityManager em;
    private final ObjectMapper mapper;
    private final ObjectProvider<DomainEventHandler> handlers;
    private final AggregateSequences sequences;

    public Outbox(EntityManager em, ObjectMapper mapper, ObjectProvider<DomainEventHandler> handlers,
            AggregateSequences sequences) {
        this.em = em;
        this.mapper = mapper;
        this.handlers = handlers;
        this.sequences = sequences;
    }

    /** Publishes under the current request's correlation id (a new one outside a request). Must run inside the business transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(String eventType, String aggregateRef, Map<String, ?> data, String actor) {
        return publish(eventType, aggregateRef, data, actor, null);
    }

    /** Publishes as a consequence of another event, keeping its correlation id for tracing. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(String eventType, String aggregateRef, Map<String, ?> data, String actor,
            String correlationId) {
        String payload;
        try {
            payload = mapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Event " + eventType + " could not be serialised", ex);
        }
        if (payload.length() > 4000) {
            throw new IllegalStateException("Event " + eventType + " payload is too large; publish references only");
        }
        String correlation = correlationId != null ? correlationId : com.nsure.common.CorrelationId.current();
        OutboxEvent event = new OutboxEvent(eventType, aggregateRef, payload, correlation,
                actor == null ? "system" : actor, sequences.next(aggregateRef));
        em.persist(event);
        for (DomainEventHandler handler : handlers.orderedStream().toList()) {
            if (handler.eventTypes().contains(eventType)) {
                handler.beforePublish(eventType, aggregateRef, data);
                em.persist(new EventDelivery(event, handler.name()));
            }
        }
        return event;
    }
}
