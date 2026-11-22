package com.nsure.integration;

import java.util.Set;

/**
 * A consumer of outbox events. Delivery is at least once, so {@link #handle} must be idempotent: store
 * {@link DomainEvent#eventId()} (or another unique business key) with what it creates and do nothing on a repeat.
 * It runs in its own transaction; throwing rolls back only this handler's work and schedules a retry.
 */
public interface DomainEventHandler {

    /** Stable name stored on each delivery row, e.g. "finance.subledger". At most 60 characters. */
    String name();

    /** The event types this handler wants, e.g. {@code Set.of(Events.PREMIUM_RECEIVED)}. */
    Set<String> eventTypes();

    void handle(DomainEvent event);

    /**
     * The newest event schema version this handler understands (NS-API-004 versioned contracts). A newer event is not
     * guessed at: its delivery fails at once with the reason, for an operator to upgrade the consumer and replay it.
     * Compatible changes (added fields) keep the version; only a breaking change raises it.
     */
    default int supportedSchemaVersion() {
        return 1;
    }

    /**
     * Why this (older) event no longer matters to the handler, or null to handle it (NS-API-004): for example a
     * reminder for a premium already paid. The delivery is then recorded as IGNORED with the reason as evidence,
     * instead of being handled or silently dropped.
     */
    default String ignoreReason(DomainEvent event) {
        return null;
    }

    /**
     * Called inside the business transaction when an event this handler subscribes to is published. A handler may
     * refuse the business action by throwing (NS-SH08-003: Finance can make an event type block when it could not be
     * posted). It must only read. Most handlers do nothing.
     */
    default void beforePublish(String eventType, String aggregateRef, java.util.Map<String, ?> data) {
    }
}
