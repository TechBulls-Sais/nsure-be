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
    private static final Set<String> CHANNELS = Set.of("PORTAL", "PHONE", "EMAIL", "WALK_IN", "WHATSAPP");
    private static final java.util.regex.Pattern CASE_REF = java.util.regex.Pattern.compile("\\b(?:SR|CMP)-\\d+\\b");
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
        String verb = action == null ? "" : action.toLowerCase(Locale.ROOT);
        // NS-SH10-002: work someone owns is worked by them; anyone else takes it over first (assign with force).
        if (Set.of("pause", "resume", "resolve").contains(verb) && c.getOwner() != null && !actor.equals(c.getOwner())) {
            throw new ConflictException(c.getRef() + " is owned by " + c.getOwner()
                    + ". Take it over first (by agreement) or ask a supervisor to assign it to you.");
        }
        switch (verb) {
            case "assign" -> {
                requireActive(c, "assigned");
                String owner = c.getOwner();
                // A supervisor may give the case to someone else who works cases (NS-SH10-002).
                String to = text(body, "to");
                String assignee = to == null ? actor : assignee(to, user);
                if (assignee.equals(owner)) {
                    return detailOf(c);
                }
                boolean force = body != null && body.path("force").asBoolean(false);
                if (owner != null && !force && to == null) {
                    throw new ConflictException(c.getRef() + " is already owned by " + owner
                            + ". Take it over only by agreement (force).");
                }
                c.assign(assignee);
                record(c, "Assigned", previous, (owner == null ? "Owner: " + assignee
                        : "Taken over from " + owner + " by " + assignee)
                        + (assignee.equals(actor) ? "" : " (assigned by " + actor + ")"), actor);
                taskAssigned(c, assignee, actor);
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
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("caseRef", c.getRef());
                event.put("caseType", c.getCaseType());
                event.put("customerRef", c.getCustomerRef());
                event.put("closedBy", actor);
                outbox.publish(Events.CASE_CLOSED, c.getRef(), event, actor);
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

    /**
     * Inbound correspondence from a customer (Solution Blueprint §5: replies linked to service cases). Added to the
     * customer's open case it names, otherwise a new service request is opened for it. Returns the case reference.
     */
    @Transactional
    public String linkCorrespondence(String customerRef, String channel, String text, String actor) {
        CustomerAccount customer = em.createQuery("select c from CustomerAccount c where c.ref = :ref",
                        CustomerAccount.class)
                .setParameter("ref", customerRef)
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("Customer " + customerRef + " not found"));
        String snippet = text.length() <= 300 ? text : text.substring(0, 300) + "…";
        java.util.regex.Matcher named = CASE_REF.matcher(text.toUpperCase(Locale.ROOT));
        while (named.find()) {
            ServiceCase c = em.createQuery("select c from ServiceCase c where c.ref = :ref and c.customerRef = :customer",
                            ServiceCase.class)
                    .setParameter("ref", named.group())
                    .setParameter("customer", customer.getRef())
                    .getResultStream().findFirst().orElse(null);
            if (c != null && !ServiceCase.CLOSED.equals(c.getStatus())) {
                record(c, "Reply Received", c.getStatus(), channel + ": " + snippet, actor);
                return c.getRef();
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode body =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.put("caseType", ServiceCase.SERVICE_REQUEST);
        body.put("category", "Customer correspondence");
        body.put("subject", "Message received by " + ("WHATSAPP".equals(channel) ? "WhatsApp" : "Email"));
        body.put("description", text.length() <= 1000 ? text : text.substring(0, 1000));
        return create(customer, body, channel, actor).getRef();
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
        if (linkedRef != null && !belongsTo(linkedRef, customer.getRef())) {
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
        // The spec's name for the same moment (SH10 events), alongside the existing one its handlers consume.
        Map<String, Object> opened = new LinkedHashMap<>();
        opened.put("caseRef", c.getRef());
        opened.put("caseType", caseType);
        opened.put("customerRef", customer.getRef());
        opened.put("channel", channel);
        outbox.publish(Events.CASE_OPENED, c.getRef(), opened, actor);
        return c;
    }

    /**
     * Ownership given from the work queue: the taker becomes the owner (a case someone else owns is refused), or a
     * supervisor gives it to {@code owner} whoever had it. Recorded in the case history; the queue publishes
     * TaskAssigned.
     */
    @Transactional
    public void assignFromQueue(String ref, String owner, String by, boolean supervisor) {
        ServiceCase c = find(ref);
        requireActive(c, "assigned");
        String current = c.getOwner();
        if (owner.equals(current)) {
            return;
        }
        if (current != null && !supervisor) {
            throw new ConflictException("This work is already owned by " + current + ".");
        }
        String previous = c.getStatus();
        c.assign(owner);
        record(c, "Assigned", previous, (current == null ? "Owner: " + owner
                : "Taken over from " + current + " by " + owner)
                + (owner.equals(by) ? "" : " (assigned by " + by + ")"), by);
        try {
            em.flush();
        } catch (OptimisticLockException | ObjectOptimisticLockingFailureException ex) {
            throw stale(c);
        }
    }

    /** Whom a supervisor (ASSIGN_WORK) may give a case to: an enabled staff user who works cases. */
    private String assignee(String username, AppUser supervisor) {
        supervisor.validateHasPermissionTo("ASSIGN_WORK");
        AppUser target = em.createQuery("select u from AppUser u where u.username = :name", AppUser.class)
                .setParameter("name", username.trim())
                .getResultStream().findFirst()
                .orElseThrow(() -> new NotFoundException("User " + username + " not found"));
        if (!target.isEnabled() || !target.isStaffUser() || target.isPartnerLogin()
                || !target.hasPermissionTo("MANAGE_SERVICE_CASE")) {
            throw new IllegalArgumentException(target.getUsername() + " does not work service cases.");
        }
        return target.getUsername();
    }

    private void taskAssigned(ServiceCase c, String owner, String by) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("key", c.getCaseType() + ":" + c.getRef());
        event.put("workflow", c.getCaseType());
        event.put("ref", c.getRef());
        event.put("owner", owner);
        event.put("assignedBy", by);
        outbox.publish(Events.TASK_ASSIGNED, c.getCaseType() + ":" + c.getRef(), event, by);
    }

    private boolean belongsTo(String ref, String customerRef) {
        Long policies = em.createQuery("select count(p) from InsurancePolicy p where p.ref = :ref and p.customerRef = :customer",
                        Long.class)
                .setParameter("ref", ref).setParameter("customer", customerRef).getSingleResult();
        if (policies > 0) {
            return true;
        }
        Long claims = em.createQuery("select count(c) from InsuranceClaim c where c.ref = :ref and c.customerRef = :customer",
                        Long.class)
                .setParameter("ref", ref).setParameter("customer", customerRef).getSingleResult();
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
