package com.nsure.servicedesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.nsure.common.exception.ConflictException;
import com.nsure.common.exception.NotFoundException;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.portfolio.CustomerAccount;
import com.nsure.portfolio.PortfolioService;
import com.nsure.portfolio.WorkflowHistory;
import com.nsure.security.domain.AppUser;
import com.nsure.security.service.PlatformSecurityContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service desk and complaints (SH10): staff and portal intake, atomic ownership, a pausable SLA clock and
 * resolve / reopen / close with every transition in the workflow history.
 */
@Service
public class ServiceDeskService {

    /** entity_type in workflow_log. */
    public static final String ENTITY = "Service Case";

    private static final Set<String> TYPES = Set.of(ServiceCase.SERVICE_REQUEST, ServiceCase.COMPLAINT);
    private static final Set<String> CHANNELS = Set.of("PORTAL", "PHONE", "EMAIL", "WALK_IN");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH");
    private static final Set<String> STATES = Set.of(ServiceCase.OPEN, ServiceCase.IN_PROGRESS, ServiceCase.PAUSED,
            ServiceCase.RESOLVED, ServiceCase.CLOSED);

    private final EntityManager em;
    private final PlatformSecurityContext security;
    private final WorkflowHistory history;
    private final Outbox outbox;
    private final PortfolioService portfolio;
    private final int serviceRequestHours;
    private final int complaintHours;

    public ServiceDeskService(EntityManager em, PlatformSecurityContext security, WorkflowHistory history,
            Outbox outbox, PortfolioService portfolio,
            @Value("${nsure.service-desk.sla.service-request-hours:72}") int serviceRequestHours,
            @Value("${nsure.service-desk.sla.complaint-hours:120}") int complaintHours) {
        this.em = em;
        this.security = security;
        this.history = history;
        this.outbox = outbox;
        this.portfolio = portfolio;
        this.serviceRequestHours = serviceRequestHours;
        this.complaintHours = complaintHours;
    }

    // ── staff ────────────────────────────────────────────────────────────────────────────────────────

    /** The queue, newest first. Filters: status, type, and mine (owned by the caller). */
    @Transactional(readOnly = true)
    public List<ServiceDeskViews.CaseView> cases(String status, String type, boolean mine) {
        AppUser user = user("READ_SERVICE_CASE");
        String state = upper(status);
        String caseType = upper(type);
        if (state != null && !STATES.contains(state)) {
            throw new IllegalArgumentException("Unknown case status " + status + ".");
        }
        if (caseType != null && !TYPES.contains(caseType)) {
            throw new IllegalArgumentException("Case type must be SERVICE_REQUEST or COMPLAINT.");
        }
        List<String> where = new ArrayList<>();
        if (state != null) {
            where.add("c.status = :status");
        }
        if (caseType != null) {
            where.add("c.caseType = :type");
        }
        if (mine) {
            where.add("c.owner = :owner");
        }
        TypedQuery<ServiceCase> query = em.createQuery("select c from ServiceCase c"
                + (where.isEmpty() ? "" : " where " + String.join(" and ", where))
                + " order by c.openedAt desc, c.id desc", ServiceCase.class);
        if (state != null) {
            query.setParameter("status", state);
        }
        if (caseType != null) {
            query.setParameter("type", caseType);
        }
        if (mine) {
            query.setParameter("owner", user.getUsername());
        }
        LocalDateTime now = LocalDateTime.now();
        return query.setMaxResults(500).getResultList().stream()
                .map(c -> ServiceDeskViews.CaseView.of(c, now)).toList();
    }

    @Transactional(readOnly = true)
    public ServiceDeskViews.CaseDetail detail(String ref) {
        user("READ_SERVICE_CASE");
        return detailOf(find(ref));
    }

    /** Staff log a case for a customer: {customerRef, caseType, category, subject, description, linkedRef, channel, priority}. */
    @Transactional
    public ServiceDeskViews.CaseDetail open(JsonNode body) {
        AppUser user = user("MANAGE_SERVICE_CASE");
        String customerRef = text(body, "customerRef");
        if (customerRef == null) {
            throw new IllegalArgumentException("Give the customer reference, e.g. CUS-1001.");
        }
        CustomerAccount customer = em.createQuery("select c from CustomerAccount c where c.ref = :ref",
                        CustomerAccount.class)
                .setParameter("ref", customerRef.toUpperCase(Locale.ROOT))
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + customerRef + " not found"));
        String channel = upper(text(body, "channel"));
        ServiceCase created = create(customer, body, channel == null ? "PHONE" : channel, user.getUsername());
        return detailOf(created);
    }

    /**
     * assign | pause | resume | resolve | reopen | close. A body {@code expectedVersion} that is not the current
     * version means someone else changed the case: 409, refresh and retry (NS-TXN-001).
     */
    @Transactional
    public ServiceDeskViews.CaseDetail act(String ref, String action, JsonNode body) {
        AppUser user = user("MANAGE_SERVICE_CASE");
        ServiceCase c = find(ref);
        if (body != null && body.hasNonNull("expectedVersion") && body.get("expectedVersion").asInt() != c.getVersion()) {
            throw stale(c);
        }
        String actor = user.getUsername();
        String previous = c.getStatus();
        switch (action == null ? "" : action.toLowerCase(Locale.ROOT)) {
            case "assign" -> {
                requireActive(c, "assigned");
                String owner = c.getOwner();
                if (actor.equals(owner)) {
                    return detailOf(c);
                }
                boolean force = body != null && body.path("force").asBoolean(false);
                if (owner != null && !force) {
                    throw new ConflictException(c.getRef() + " is already owned by " + owner
                            + ". Take it over only by agreement (force).");
                }
                c.assign(actor);
                record(c, "Assigned", previous, owner == null ? "Owner: " + actor
                        : "Taken over from " + owner + " by " + actor, actor);
            }
            case "pause" -> {
                if (!ServiceCase.OPEN.equals(previous) && !ServiceCase.IN_PROGRESS.equals(previous)) {
                    throw illegal(c, "paused");
                }
                String reason = required(body, "reason", "Give the reason for pausing the SLA clock.", 500);
                c.pause(reason);
                record(c, "Paused", previous, reason, actor);
            }
            case "resume" -> {
                if (!ServiceCase.PAUSED.equals(previous)) {
                    throw illegal(c, "resumed");
                }
                long minutes = c.resume();
                record(c, "Resumed", previous, "Clock stopped for " + minutes + " min; due " + c.getDueAt(), actor);
            }
            case "resolve" -> {
                if (!ServiceCase.OPEN.equals(previous) && !ServiceCase.IN_PROGRESS.equals(previous)) {
                    throw illegal(c, "resolved");
                }
                String resolution = required(body, "resolution", "Record the resolution given to the customer.", 1000);
                c.resolve(resolution);
                record(c, "Resolved", previous, resolution, actor);
            }
            case "reopen" -> {
                if (!ServiceCase.RESOLVED.equals(previous)) {
                    throw illegal(c, "reopened");
                }
                String reason = required(body, "reason", "Give the reason for reopening the case.", 500);
                String earlier = c.getResolution();
                c.reopen();
                record(c, "Reopened", previous, reason + " | Previous resolution: " + earlier, actor);
            }
            case "close" -> {
                if (!ServiceCase.RESOLVED.equals(previous)) {
                    throw new IllegalArgumentException("Only a resolved case can be closed; " + c.getRef() + " is "
                            + previous + ".");
                }
                c.close();
                record(c, "Closed", previous, null, actor);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown action " + action + ". Use assign, pause, resume, resolve, reopen or close.");
        }
        try {
            em.flush();
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException ex) {
            throw stale(c);
        }
        return detailOf(c);
    }

    // ── customer portal ──────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ServiceDeskViews.CustomerCaseView> customerCases() {
        CustomerAccount customer = portfolio.customerForLogin();
        LocalDateTime now = LocalDateTime.now();
        return em.createQuery("select c from ServiceCase c where c.customerRef = :ref order by c.openedAt desc, c.id desc",
                        ServiceCase.class)
                .setParameter("ref", customer.getRef())
                .getResultList().stream()
                .map(c -> ServiceDeskViews.CustomerCaseView.of(c, now)).toList();
    }

    /** A customer lodges a request or complaint from the portal: {caseType, category, subject, description, linkedRef}. */
    @Transactional
    public ServiceDeskViews.CustomerCaseView customerOpen(JsonNode body) {
        CustomerAccount customer = portfolio.customerForLogin();
        AppUser user = security.authenticatedUser();
        ServiceCase created = create(customer, body, "PORTAL", user.getUsername());
        return ServiceDeskViews.CustomerCaseView.of(created, LocalDateTime.now());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private ServiceCase create(CustomerAccount customer, JsonNode body, String channel, String actor) {
        String caseType = upper(text(body, "caseType"));
        if (caseType == null || !TYPES.contains(caseType)) {
            throw new IllegalArgumentException("Choose whether this is a SERVICE_REQUEST or a COMPLAINT.");
        }
        String category = required(body, "category", "Choose a category.", 60);
        String subject = required(body, "subject", "Give a short subject.", 200);
        String description = text(body, "description");
        if (description != null && description.length() > 1000) {
            throw new IllegalArgumentException("Keep the description to 1000 characters.");
        }
        if (!CHANNELS.contains(channel)) {
            throw new IllegalArgumentException("Channel must be PORTAL, PHONE, EMAIL or WALK_IN.");
        }
        String priority = upper(text(body, "priority"));
        priority = priority == null ? "NORMAL" : priority;
        if (!PRIORITIES.contains(priority)) {
            throw new IllegalArgumentException("Priority must be LOW, NORMAL or HIGH.");
        }
        String linkedRef = upper(text(body, "linkedRef"));
        if (linkedRef != null && !belongsTo(linkedRef, customer.getName())) {
            throw new IllegalArgumentException(linkedRef + " is not a policy or claim of " + customer.getName() + ".");
        }
        boolean complaint = ServiceCase.COMPLAINT.equals(caseType);
        ServiceCase c = new ServiceCase(nextRef(complaint ? "CMP-" : "SR-", caseType), caseType, category,
                customer.getRef(), customer.getName(), linkedRef, subject, description, channel, priority, actor,
                complaint ? complaintHours : serviceRequestHours);
        em.persist(c);
        history.record(ENTITY, c.getRef(), customer.getName(), "Opened", null, ServiceCase.OPEN,
                (complaint ? "Complaint" : "Service request") + " · " + category + " · via " + channel
                        + (linkedRef == null ? "" : " · " + linkedRef), actor);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("caseRef", c.getRef());
        event.put("caseType", caseType);
        event.put("customerName", customer.getName());
        event.put("category", category);
        event.put("customerRef", customer.getRef());
        outbox.publish(Events.SERVICE_CASE_OPENED, c.getRef(), event, actor);
        return c;
    }

    private boolean belongsTo(String ref, String customerName) {
        Long policies = em.createQuery("select count(p) from InsurancePolicy p where p.ref = :ref and p.customerName = :name",
                        Long.class)
                .setParameter("ref", ref).setParameter("name", customerName).getSingleResult();
        if (policies > 0) {
            return true;
        }
        Long claims = em.createQuery("select count(c) from InsuranceClaim c where c.ref = :ref and c.customerName = :name",
                        Long.class)
                .setParameter("ref", ref).setParameter("name", customerName).getSingleResult();
        return claims > 0;
    }

    private String nextRef(String prefix, String caseType) {
        int max = 0;
        for (String ref : em.createQuery("select c.ref from ServiceCase c where c.caseType = :type", String.class)
                .setParameter("type", caseType).getResultList()) {
            String number = ref.substring(prefix.length()).replaceAll("[^0-9]", "");
            if (!number.isEmpty()) {
                max = Math.max(max, Integer.parseInt(number));
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private ServiceDeskViews.CaseDetail detailOf(ServiceCase c) {
        return new ServiceDeskViews.CaseDetail(ServiceDeskViews.CaseView.of(c, LocalDateTime.now()),
                history.of(ENTITY, c.getRef()));
    }

    private void record(ServiceCase c, String action, String previous, String details, String actor) {
        history.record(ENTITY, c.getRef(), c.getCustomerName(), action, previous, c.getStatus(), details, actor);
    }

    private ServiceCase find(String ref) {
        return em.createQuery("select c from ServiceCase c where c.ref = :ref", ServiceCase.class)
                .setParameter("ref", ref == null ? "" : ref.toUpperCase(Locale.ROOT))
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Case " + ref + " not found"));
    }

    private static void requireActive(ServiceCase c, String verb) {
        if (ServiceCase.RESOLVED.equals(c.getStatus()) || ServiceCase.CLOSED.equals(c.getStatus())) {
            throw illegal(c, verb);
        }
    }

    private static IllegalArgumentException illegal(ServiceCase c, String verb) {
        return new IllegalArgumentException(c.getRef() + " is " + c.getStatus() + " and cannot be " + verb + ".");
    }

    static ConflictException stale(ServiceCase c) {
        return new ConflictException(c.getRef() + " was changed by someone else. Refresh and retry.");
    }

    private static String required(JsonNode body, String field, String message, int max) {
        String value = text(body, field);
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        if (value.length() > max) {
            throw new IllegalArgumentException("Keep the " + field + " to " + max + " characters.");
        }
        return value;
    }

    private AppUser user(String permission) {
        AppUser user = security.authenticatedUser();
        user.validateHasPermissionTo(permission);
        return user;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String text(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText().trim();
        return text.isEmpty() ? null : text;
    }
}
