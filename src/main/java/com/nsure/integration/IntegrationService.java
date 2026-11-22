package com.nsure.integration;

import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Integration dashboard (NS-SH12-005): events with their per-handler deliveries, failures, replay and dispatch. */
@Service
public class IntegrationService {

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final OutboxDispatcher dispatcher;
    private final InterfaceRegistry interfaces;
    private final OperatorActionLog actions;

    public IntegrationService(EntityManager em, PlatformSecurityContext security, OutboxDispatcher dispatcher,
            InterfaceRegistry interfaces, OperatorActionLog actions) {
        this.em = em;
        this.security = security;
        this.dispatcher = dispatcher;
        this.interfaces = interfaces;
        this.actions = actions;
    }

    /** The newest events (optionally one type, or only those with a failed delivery), each with its deliveries. */
    @Transactional(readOnly = true)
    public List<IntegrationViews.EventView> events(String eventType, boolean failedOnly) {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        StringBuilder jpql = new StringBuilder("select e from OutboxEvent e where 1 = 1");
        if (eventType != null && !eventType.isBlank()) {
            jpql.append(" and e.eventType = :type");
        }
        if (failedOnly) {
            jpql.append(" and exists (select d from EventDelivery d where d.event = e and d.status = 'FAILED')");
        }
        var query = em.createQuery(jpql.append(" order by e.id desc").toString(), OutboxEvent.class)
                .setMaxResults(300);
        if (eventType != null && !eventType.isBlank()) {
            query.setParameter("type", eventType);
        }
        List<OutboxEvent> events = query.getResultList();
        Map<Long, List<IntegrationViews.DeliveryView>> deliveries = new HashMap<>();
        if (!events.isEmpty()) {
            for (EventDelivery d : em.createQuery(
                            "select d from EventDelivery d where d.event in :events order by d.id", EventDelivery.class)
                    .setParameter("events", events).getResultList()) {
                deliveries.computeIfAbsent(d.getEvent().getId(), id -> new ArrayList<>()).add(delivery(d, d.getEvent()));
            }
        }
        return events.stream().map(e -> {
            List<IntegrationViews.DeliveryView> own = deliveries.getOrDefault(e.getId(), List.of());
            return new IntegrationViews.EventView(e.getEventId(), e.getEventType(), e.getAggregateRef(),
                    e.getCorrelationId(), e.getPublishedBy(), e.getOccurredAt(), e.getPayload(), status(own), own,
                    e.getSchemaVersion(), e.getAggregateVersion());
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<IntegrationViews.InboundView> inbound() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        return em.createQuery("select m from InboundMessage m order by m.id desc", InboundMessage.class)
                .setMaxResults(300).getResultList().stream().map(IntegrationService::inbound).toList();
    }

    @Transactional(readOnly = true)
    public IntegrationViews.DashboardView dashboard() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        return summary();
    }

    /** Unchecked: the management report calls it after checking READ_REPORT itself. */
    @Transactional(readOnly = true)
    public IntegrationViews.DashboardView summary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (String status : List.of(EventDelivery.PENDING, EventDelivery.DELIVERED, EventDelivery.FAILED,
                EventDelivery.IGNORED)) {
            byStatus.put(status, 0L);
        }
        for (Object[] row : em.createQuery("select d.status, count(d) from EventDelivery d group by d.status",
                Object[].class).getResultList()) {
            byStatus.put((String) row[0], (Long) row[1]);
        }
        Map<String, Long> inbound = new LinkedHashMap<>();
        for (Object[] row : em.createQuery("select m.status, count(m) from InboundMessage m group by m.status",
                Object[].class).getResultList()) {
            inbound.put((String) row[0], (Long) row[1]);
        }
        LocalDateTime oldest = em.createQuery(
                        "select min(d.event.occurredAt) from EventDelivery d where d.status = 'PENDING'",
                        LocalDateTime.class)
                .getSingleResult();
        long retrying = em.createQuery("select count(d) from EventDelivery d where d.status = 'PENDING'"
                + " and d.attempts > 0", Long.class).getSingleResult();
        long seconds = 0;
        long delivered = 0;
        for (Object[] row : em.createQuery("select d.event.occurredAt, d.deliveredAt from EventDelivery d"
                        + " where d.status = 'DELIVERED' and d.deliveredAt >= :since", Object[].class)
                .setParameter("since", LocalDateTime.now().minusDays(30)).getResultList()) {
            seconds += Math.max(0, java.time.Duration.between((LocalDateTime) row[0], (LocalDateTime) row[1])
                    .getSeconds());
            delivered++;
        }
        List<String> down = interfaces.entries().stream().filter(e -> !e.available())
                .map(InterfaceRegistry.Entry::name).toList();
        return new IntegrationViews.DashboardView(
                em.createQuery("select count(e) from OutboxEvent e", Long.class).getSingleResult(),
                byStatus, byStatus.getOrDefault(EventDelivery.FAILED, 0L), oldest, inbound, LocalDateTime.now(),
                delivered == 0 ? null : seconds / delivered, retrying, down);
    }

    /** The dead-letter queue: every failed delivery with its owner and reason, newest first (NS-SH12-003). */
    @Transactional(readOnly = true)
    public List<IntegrationViews.DeadLetterView> deadLetters() {
        security.authenticatedUser().validateHasPermissionTo("READ_INTEGRATION");
        return em.createQuery("select d from EventDelivery d join fetch d.event where d.status = 'FAILED'"
                        + " order by d.id desc", EventDelivery.class)
                .setMaxResults(500).getResultList().stream().map(d -> {
                    var entry = interfaces.forHandler(d.getHandler());
                    OutboxEvent e = d.getEvent();
                    return new IntegrationViews.DeadLetterView(d.getId(), e.getEventId(), e.getEventType(),
                            e.getAggregateRef(), e.getCorrelationId(), e.getOccurredAt(), d.getHandler(),
                            entry.map(InterfaceRegistry.Entry::code).orElse(null),
                            entry.map(InterfaceRegistry.Entry::name).orElse("Unregistered consumer"),
                            entry.map(InterfaceRegistry.Entry::exceptionOwner).orElse("Integration engineering"),
                            d.getFailureClass() == null ? EventDelivery.TRANSIENT : d.getFailureClass(),
                            d.getAttempts(), d.getLastError(),
                            d.getDeadLetteredAt() == null ? d.getUpdatedAt() : d.getDeadLetteredAt());
                }).toList();
    }

    public IntegrationViews.DispatchView dispatchNow() {
        security.authenticatedUser().validateHasPermissionTo("MANAGE_INTEGRATION");
        OutboxDispatcher.DispatchResult result = dispatcher.dispatch();
        return new IntegrationViews.DispatchView(result.delivered(), result.failed(), summary());
    }

    /**
     * Puts a failed delivery back in the queue and runs the dispatcher. The payload itself is never editable, and the
     * consumer applies its own domain checks again; the operator's reason and the outcome are logged (NS-SH12-005).
     */
    public IntegrationViews.DispatchView replay(Long deliveryId, String reason) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        String why = reason(reason);
        dispatcher.requeue(deliveryId);
        OutboxDispatcher.DispatchResult result = dispatcher.dispatch();
        Object[] now = state(deliveryId);
        actions.record("REPLAY", interfaces.forHandler((String) now[0]).map(InterfaceRegistry.Entry::code)
                        .orElse(null), "Delivery " + deliveryId + " (" + now[0] + ")", why,
                "Now " + now[1] + " after " + now[2] + " attempt(s)", user.getUsername());
        return new IntegrationViews.DispatchView(result.delivered(), result.failed(), summary());
    }

    /** A pending retry runs now instead of after its backoff; logged like a replay. */
    public IntegrationViews.DispatchView retryNow(Long deliveryId, String reason) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        String why = reason == null || reason.isBlank() ? "Retry now" : reason.trim();
        dispatcher.expedite(deliveryId);
        OutboxDispatcher.DispatchResult result = dispatcher.dispatch();
        Object[] now = state(deliveryId);
        actions.record("RETRY_NOW", interfaces.forHandler((String) now[0]).map(InterfaceRegistry.Entry::code)
                        .orElse(null), "Delivery " + deliveryId + " (" + now[0] + ")", why,
                "Now " + now[1] + " after " + now[2] + " attempt(s)", user.getUsername());
        return new IntegrationViews.DispatchView(result.delivered(), result.failed(), summary());
    }

    /** handler, status and attempts as stored now (a scalar read, never a stale managed copy). */
    private Object[] state(Long deliveryId) {
        return em.createQuery("select d.handler, d.status, d.attempts from EventDelivery d where d.id = :id",
                Object[].class).setParameter("id", deliveryId).getSingleResult();
    }

    private static String reason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new com.nsure.common.exception.InvalidFieldException("reason",
                    "Say why this delivery is replayed (at least 5 characters).");
        }
        return reason.trim();
    }

    /** An outbox event as handlers see it, e.g. for Finance to post an exception again. */
    @Transactional(readOnly = true)
    public Optional<DomainEvent> domainEvent(String eventId) {
        return em.createQuery("select e from OutboxEvent e where e.eventId = :id", OutboxEvent.class)
                .setParameter("id", eventId)
                .getResultStream()
                .findFirst()
                .map(dispatcher::toDomainEvent);
    }

    private static String status(List<IntegrationViews.DeliveryView> deliveries) {
        if (deliveries.isEmpty()) {
            return "NO_SUBSCRIBERS";
        }
        if (deliveries.stream().anyMatch(d -> EventDelivery.FAILED.equals(d.status()))) {
            return EventDelivery.FAILED;
        }
        if (deliveries.stream().anyMatch(d -> EventDelivery.PENDING.equals(d.status()))) {
            return EventDelivery.PENDING;
        }
        return EventDelivery.DELIVERED;
    }

    private IntegrationViews.DeliveryView delivery(EventDelivery d, OutboxEvent e) {
        var entry = interfaces.forHandler(d.getHandler());
        return new IntegrationViews.DeliveryView(d.getId(), d.getHandler(), d.getStatus(), d.getAttempts(),
                d.getLastError(), d.getNextAttemptAt(), d.getDeliveredAt(), d.getFailureClass(), d.getDeadLetteredAt(),
                entry.map(InterfaceRegistry.Entry::code).orElse(null),
                entry.map(InterfaceRegistry.Entry::exceptionOwner).orElse("Integration engineering"),
                d.getDeliveredAt() == null ? null
                        : Math.max(0, java.time.Duration.between(e.getOccurredAt(), d.getDeliveredAt()).getSeconds()));
    }

    static IntegrationViews.InboundView inbound(InboundMessage m) {
        return new IntegrationViews.InboundView(m.getId(), m.getProvider(), m.getProviderRef(), m.getMessageType(),
                m.getBusinessRef(), m.getAmount(), m.getStatus(), m.getResultRef(), m.getDetail(), m.getReceivedBy(),
                m.getReceivedAt());
    }
}
