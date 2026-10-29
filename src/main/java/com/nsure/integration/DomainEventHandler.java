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
}
