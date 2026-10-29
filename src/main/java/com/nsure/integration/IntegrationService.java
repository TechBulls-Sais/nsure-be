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

    public IntegrationService(EntityManager em, PlatformSecurityContext security, OutboxDispatcher dispatcher) {
        this.em = em;
        this.security = security;
        this.dispatcher = dispatcher;
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
                deliveries.computeIfAbsent(d.getEvent().getId(), id -> new ArrayList<>()).add(delivery(d));
            }
        }
        return events.stream().map(e -> {
            List<IntegrationViews.DeliveryView> own = deliveries.getOrDefault(e.getId(), List.of());
            return new IntegrationViews.EventView(e.getEventId(), e.getEventType(), e.getAggregateRef(),
                    e.getCorrelationId(), e.getPublishedBy(), e.getOccurredAt(), e.getPayload(), status(own), own);
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
        for (String status : List.of(EventDelivery.PENDING, EventDelivery.DELIVERED, EventDelivery.FAILED)) {
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
        return new IntegrationViews.DashboardView(
                em.createQuery("select count(e) from OutboxEvent e", Long.class).getSingleResult(),
                byStatus, byStatus.getOrDefault(EventDelivery.FAILED, 0L), oldest, inbound, LocalDateTime.now());
    }

    public IntegrationViews.DispatchView dispatchNow() {
        security.authenticatedUser().validateHasPermissionTo("MANAGE_INTEGRATION");
        OutboxDispatcher.DispatchResult result = dispatcher.dispatch();
        return new IntegrationViews.DispatchView(result.delivered(), result.failed(), summary());
    }

    /** Puts a failed delivery back in the queue and runs the dispatcher. The payload itself is never editable. */
    public IntegrationViews.DispatchView replay(Long deliveryId) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo("MANAGE_INTEGRATION");
        dispatcher.requeue(deliveryId);
        OutboxDispatcher.DispatchResult result = dispatcher.dispatch();
        return new IntegrationViews.DispatchView(result.delivered(), result.failed(), summary());
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

    private static IntegrationViews.DeliveryView delivery(EventDelivery d) {
        return new IntegrationViews.DeliveryView(d.getId(), d.getHandler(), d.getStatus(), d.getAttempts(),
                d.getLastError(), d.getNextAttemptAt(), d.getDeliveredAt());
    }

    static IntegrationViews.InboundView inbound(InboundMessage m) {
        return new IntegrationViews.InboundView(m.getId(), m.getProvider(), m.getProviderRef(), m.getMessageType(),
                m.getBusinessRef(), m.getAmount(), m.getStatus(), m.getResultRef(), m.getDetail(), m.getReceivedBy(),
                m.getReceivedAt());
    }
}
