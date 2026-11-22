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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final com.nsure.controls.StaffAlertService alerts;
    private final com.nsure.portfolio.DocumentQuarantine quarantine;
    private final com.nsure.portfolio.DocumentCustody custody;
    private final com.nsure.portfolio.DocumentFiles files;
    private final int unownedAlertHours;

    /** Complaint outcomes recorded with the resolution (NS-SH10-004). */
    static final Set<String> OUTCOMES = Set.of("UPHELD", "PARTIALLY_UPHELD", "NOT_UPHELD");

    public ServiceDeskService(EntityManager em, PlatformSecurityContext security, WorkflowHistory history,
            Outbox outbox, PortfolioService portfolio, org.springframework.jdbc.core.JdbcTemplate jdbc,
            com.nsure.controls.StaffAlertService alerts, com.nsure.portfolio.DocumentQuarantine quarantine,
            com.nsure.portfolio.DocumentCustody custody, com.nsure.portfolio.DocumentFiles files,
            @Value("${nsure.service-desk.sla.service-request-hours:72}") int serviceRequestHours,
            @Value("${nsure.service-desk.sla.complaint-hours:120}") int complaintHours,
            @Value("${nsure.service-desk.unowned-alert-hours:4}") int unownedAlertHours) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.quarantine = quarantine;
        this.custody = custody;
        this.files = files;
        this.unownedAlertHours = unownedAlertHours;
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
        Map<String, Object> calendar = calendar();
        return query.setMaxResults(500).getResultList().stream()
                .map(c -> ServiceDeskViews.CaseView.of(c, now, calendar)).toList();
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
            case "pause" -> pause(c, previous, body, actor);
            case "approve-pause", "decline-pause" -> {
                user.validateHasPermissionTo("ASSIGN_WORK");
                if (c.getPauseRequestedBy() == null || ServiceCase.PAUSED.equals(previous)) {
                    throw new IllegalArgumentException(c.getRef() + " has no pause waiting for approval.");
                }
                if (actor.equalsIgnoreCase(c.getPauseRequestedBy())) {
                    throw new com.nsure.security.exception.NoAuthorizationException(
                            "You asked for this pause, so another supervisor approves it.");
                }
                Map<String, Object> reason = pauseReason(c.getPauseCode());
                if ("approve-pause".equals(verb)) {
                    c.approvePause((String) reason.get("label"), actor);
                    record(c, "Paused", previous, reason.get("label") + " · approved by " + actor + " · asked by "
                            + c.getPauseRequestedBy() + " · evidence: " + c.getPauseEvidence() + " · resume by "
                            + c.getPauseExpectedResume(), actor);
                } else {
                    String why = required(body, "reason", "Say why the pause is declined.", 500);
                    record(c, "Pause declined", previous, reason.get("label") + ": " + why, actor);
                    c.declinePause();
                }
            }
            case "resume" -> {
                if (!ServiceCase.PAUSED.equals(previous)) {
                    throw illegal(c, "resumed");
                }
                LocalDateTime pausedAt = c.getPausedAt();
                Map<String, Object> calendar = calendar();
                long working = calendar == null ? 0
                        : com.nsure.ops.OpsService.workingMinutesBetween(pausedAt, LocalDateTime.now(), calendar);
                LocalDateTime due = "WORKING_HOURS".equals(c.getSlaBasis()) && calendar != null
                        ? com.nsure.ops.OpsService.addWorkingMinutes(c.getDueAt(), working, calendar)
                        : c.getDueAt().plusMinutes(java.time.Duration.between(pausedAt, LocalDateTime.now()).toMinutes());
                long minutes = c.resumeWorking(due, working);
                record(c, "Resumed", previous, "Clock stopped for " + minutes + " min (" + working
                        + " working min); due " + c.getDueAt(), actor);
            }
            case "resolve" -> {
                if (!ServiceCase.OPEN.equals(previous) && !ServiceCase.IN_PROGRESS.equals(previous)) {
                    throw illegal(c, "resolved");
                }
                String resolution = required(body, "resolution", "Record the resolution given to the customer.", 1000);
                // NS-SH10-004: a coded resolution reason, and for a complaint its outcome.
                String code = upper(text(body, "resolutionCode"));
                if (code == null || !resolutionCodes(c.getCaseType()).contains(code)) {
                    throw new IllegalArgumentException("Choose the resolution reason: "
                            + String.join(", ", resolutionCodes(c.getCaseType())) + ".");
                }
                String outcome = upper(text(body, "outcome"));
                if (ServiceCase.COMPLAINT.equals(c.getCaseType()) && (outcome == null || !OUTCOMES.contains(outcome))) {
                    throw new IllegalArgumentException("Record the complaint outcome: UPHELD, PARTIALLY_UPHELD or"
                            + " NOT_UPHELD.");
                }
                c.resolveWith(resolution, code, ServiceCase.COMPLAINT.equals(c.getCaseType()) ? outcome : null);
                record(c, "Resolved", previous, code + (outcome == null || !ServiceCase.COMPLAINT.equals(c.getCaseType())
                        ? "" : " · " + outcome) + " · " + resolution, actor);
            }
            case "escalate" -> {
                requireActive(c, "escalated");
                String reason = required(body, "reason", "Say why the case is escalated.", 500);
                String reference = text(body, "externalReference");
                boolean external = body != null && body.path("external").asBoolean(false);
                if (external && !ServiceCase.COMPLAINT.equals(c.getCaseType())) {
                    throw new IllegalArgumentException("Only a complaint is referred to an external body.");
                }
                if (external && reference == null) {
                    throw new IllegalArgumentException("Give the external body's reference for the referral.");
                }
                int level = external ? Math.max(3, c.getEscalationLevel() + 1) : c.getEscalationLevel() + 1;
                c.escalate(level, external ? reference : null);
                record(c, "Escalated", previous, "Level " + level + (external ? " · referred externally ("
                        + reference + ")" : "") + " · " + reason, actor);
                alerts.raise("CASE_ESCALATED", "ASSIGN_WORK", c.getRef() + " escalated to level " + level,
                        c.getCaseType().toLowerCase(Locale.ROOT).replace('_', ' ') + " for " + c.getCustomerName()
                                + ": " + reason, "case-escalation:" + c.getRef() + ":" + level, actor);
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
            default -> throw new IllegalArgumentException("Unknown action " + action + ". Use assign, pause,"
                    + " approve-pause, decline-pause, resume, resolve, escalate, reopen or close.");
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
        String remedy = text(body, "requestedRemedy");
        if (remedy != null && remedy.length() > 300) {
            throw new IllegalArgumentException("Keep the requested remedy to 300 characters.");
        }
        // NS-SH10-001: the case keeps the workflow version and SLA it was opened under; NS-SH10-005: the SLA is
        // counted in working hours of the business calendar.
        List<Map<String, Object>> definition = jdbc.queryForList("select sla_hours, version_no from wf_definition"
                + " where code = ?", caseType);
        int hours = definition.isEmpty() ? (complaint ? complaintHours : serviceRequestHours)
                : ((Number) value(definition.get(0), "sla_hours")).intValue();
        Integer workflowVersion = definition.isEmpty() ? null
                : ((Number) value(definition.get(0), "version_no")).intValue();
        ServiceCase c = new ServiceCase(nextRef(complaint ? "CMP-" : "SR-", caseType), caseType, category,
                customer.getRef(), customer.getName(), linkedRef, subject, description, channel, priority, actor,
                hours);
        Map<String, Object> calendar = calendar();
        if (calendar != null) {
            c.sla(workflowVersion, hours, com.nsure.ops.OpsService.addWorkingHours(c.getOpenedAt(), hours, calendar),
                    "WORKING_HOURS");
        } else {
            c.sla(workflowVersion, hours, c.getDueAt(), "ELAPSED");
        }
        c.remedy(remedy);
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
        return new ServiceDeskViews.CaseDetail(ServiceDeskViews.CaseView.of(c, LocalDateTime.now(), calendar()),
                history.of(ENTITY, c.getRef()));
    }

    // ── pauses, escalation, reference data (NS-SH10-004/005, work package e) ─────────────────────────

    /**
     * {code, evidence, expectedResume, reason?}: pauses the SLA clock for an approved reason only. A reason that
     * needs evidence needs it; the expected resume date must fall within the reason's limit. A reason that needs
     * approval waits for a supervisor (ASSIGN_WORK) who is someone else, and the clock runs until then.
     */
    private void pause(ServiceCase c, String previous, JsonNode body, String actor) {
        if (!ServiceCase.OPEN.equals(previous) && !ServiceCase.IN_PROGRESS.equals(previous)) {
            throw illegal(c, "paused");
        }
        if (c.getPauseRequestedBy() != null) {
            throw new ConflictException("A pause of " + c.getRef() + " is already waiting for approval.");
        }
        String code = upper(text(body, "code"));
        Map<String, Object> reason = code == null ? null : pauseReason(code);
        if (reason == null || !"Y".equals(reason.get("active"))) {
            throw new IllegalArgumentException("Pause only for an approved reason: " + String.join(", ",
                    jdbc.queryForList("select code from svc_pause_reason where active = 'Y' order by code",
                            String.class)) + ".");
        }
        String evidence = text(body, "evidence");
        if ("Y".equals(reason.get("requires_evidence")) && (evidence == null || evidence.length() < 5)) {
            throw new IllegalArgumentException("Record the evidence for this pause (what was requested, from whom and"
                    + " when), at least 5 characters.");
        }
        if (evidence != null && evidence.length() > 300) {
            throw new IllegalArgumentException("Keep the evidence to 300 characters.");
        }
        int maxDays = ((Number) reason.get("max_days")).intValue();
        String date = text(body, "expectedResume");
        java.time.LocalDate resume;
        try {
            resume = date == null ? null : java.time.LocalDate.parse(date);
        } catch (java.time.format.DateTimeParseException e) {
            resume = null;
        }
        java.time.LocalDate today = java.time.LocalDate.now();
        if (resume == null || resume.isBefore(today) || resume.isAfter(today.plusDays(maxDays))) {
            throw new IllegalArgumentException("Give the date the case should resume, within " + maxDays
                    + " days for this reason.");
        }
        boolean approval = "Y".equals(reason.get("requires_approval"));
        String label = (String) reason.get("label");
        String note = text(body, "reason");
        c.requestPause(code, label + (note == null ? "" : ": " + note), evidence, resume, actor, approval);
        record(c, approval ? "Pause requested" : "Paused", previous, label + (note == null ? "" : ": " + note)
                + (evidence == null ? "" : " · evidence: " + evidence) + " · resume by " + resume
                + (approval ? " · waiting for a supervisor's approval; the clock keeps running" : ""), actor);
        if (approval) {
            alerts.raise("PAUSE_APPROVAL", "ASSIGN_WORK", "Pause of " + c.getRef() + " needs approval",
                    label + " asked by " + actor, "case-pause:" + c.getRef() + ":" + c.getVersion(), actor);
        }
    }

    private Map<String, Object> pauseReason(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from svc_pause_reason where code = ?", code);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        rows.get(0).forEach((k, v) -> out.put(k.toLowerCase(Locale.ROOT), v));
        return out;
    }

    private List<String> resolutionCodes(String caseType) {
        return jdbc.queryForList("select code from svc_resolution_code where active = 'Y' and case_type in ('ALL', ?)"
                + " order by code", String.class, caseType);
    }

    /** Pause reasons, resolution codes, complaint outcomes and escalation levels, for the case screens. */
    @Transactional(readOnly = true)
    public Map<String, Object> reference() {
        user("READ_SERVICE_CASE");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pauseReasons", lower(jdbc.queryForList("select * from svc_pause_reason where active = 'Y' order by"
                + " code")));
        out.put("resolutionCodes", lower(jdbc.queryForList("select * from svc_resolution_code where active = 'Y' order"
                + " by case_type, code")));
        out.put("escalationRules", lower(jdbc.queryForList("select case_type, level_no, label, after_percent,"
                + " notify_permission from svc_escalation_rule order by case_type, level_no")));
        out.put("outcomes", List.of("UPHELD", "PARTIALLY_UPHELD", "NOT_UPHELD"));
        return out;
    }

    /**
     * NS-SH10-004/005: escalates open cases (not paused ones) whose working-time age has reached an escalation level
     * of their type, and alerts supervisors once about cases nobody has taken within
     * {@code nsure.service-desk.unowned-alert-hours} working hours. Runs every ten minutes, or on demand.
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${nsure.service-desk.escalation-cron:0 */10 * * * *}")
    @Transactional
    public int escalateOverdue() {
        Map<String, Object> calendar = calendar();
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rules = lower(jdbc.queryForList("select * from svc_escalation_rule order by level_no"));
        int changed = 0;
        for (ServiceCase c : em.createQuery("select c from ServiceCase c where c.status in :active", ServiceCase.class)
                .setParameter("active", List.of(ServiceCase.OPEN, ServiceCase.IN_PROGRESS)).getResultList()) {
            ServiceDeskViews.CaseView view = ServiceDeskViews.CaseView.of(c, now, calendar);
            int sla = (c.getSlaHours() == null ? 72 : c.getSlaHours()) * 60;
            long used = view.workingMinutes();
            for (Map<String, Object> rule : rules) {
                int level = ((Number) rule.get("level_no")).intValue();
                int percent = ((Number) rule.get("after_percent")).intValue();
                if (!c.getCaseType().equals(rule.get("case_type")) || level <= c.getEscalationLevel()
                        || used * 100 < (long) sla * percent) {
                    continue;
                }
                c.escalate(level, null);
                record(c, "Escalated", c.getStatus(), "Level " + level + " · " + rule.get("label") + " · " + used
                        + " of " + sla + " working minutes used", WorkflowHistory.SYSTEM);
                alerts.raise("CASE_ESCALATED", (String) rule.get("notify_permission"), c.getRef() + ": "
                        + rule.get("label"), c.getCaseType().toLowerCase(Locale.ROOT).replace('_', ' ') + " for "
                        + c.getCustomerName() + " has used " + percent + "% of its time",
                        "case-escalation:" + c.getRef() + ":" + level, "system");
                changed++;
            }
            long unowned = calendar == null ? java.time.Duration.between(c.getOpenedAt(), now).toMinutes()
                    : com.nsure.ops.OpsService.workingMinutesBetween(c.getOpenedAt(), now, calendar);
            if (c.getOwner() == null && c.getUnownedAlertedAt() == null && unowned >= unownedAlertHours * 60L) {
                c.unownedAlerted();
                record(c, "Unowned alert", c.getStatus(), "Nobody has taken the case after " + unownedAlertHours
                        + " working hours; supervisors alerted", WorkflowHistory.SYSTEM);
                alerts.raise("CASE_UNOWNED", "ASSIGN_WORK", c.getRef() + " has no owner", "Opened "
                        + c.getOpenedAt().withNano(0) + " for " + c.getCustomerName(), "case-unowned:" + c.getRef(),
                        "system");
                changed++;
            }
        }
        return changed;
    }

    /** A supervisor runs the escalation check now (the timer does it every ten minutes). */
    @Transactional
    public Map<String, Object> runEscalations() {
        user("ASSIGN_WORK");
        return Map.of("changed", escalateOverdue());
    }

    // ── case evidence (NS-SH10-004 with NS-SH09A-004) ───────────────────────────────────────────────

    /** Staff attach evidence to a case: it waits in quarantine until someone else releases it. */
    @Transactional
    public Map<String, Object> attach(String ref, org.springframework.web.multipart.MultipartFile file, String type) {
        AppUser user = user("MANAGE_SERVICE_CASE");
        ServiceCase c = find(ref);
        requireActive(c, "given evidence");
        return receive(c, file, type, user.getUsername(), "STAFF");
    }

    /** The customer attaches evidence to their own case from the portal. */
    @Transactional
    public Map<String, Object> customerAttach(String ref, org.springframework.web.multipart.MultipartFile file,
            String type) {
        CustomerAccount customer = portfolio.customerForLogin();
        ServiceCase c = ownCase(ref, customer);
        requireActive(c, "given evidence");
        return receive(c, file, type, security.authenticatedUser().getUsername(), "PORTAL");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> documents(String ref) {
        user("READ_SERVICE_CASE");
        return documentsOf(find(ref));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> customerDocuments(String ref) {
        return documentsOf(ownCase(ref, portfolio.customerForLogin()));
    }

    /** Opens a released case document: its hash is checked and the access is logged. */
    @Transactional
    public com.nsure.portfolio.ClaimDocumentService.StoredFile document(String ref, Long id) {
        AppUser user = user("READ_SERVICE_CASE");
        ServiceCase c = find(ref);
        List<Map<String, Object>> rows = lower(jdbc.queryForList("select * from svc_case_document where id = ? and"
                + " case_ref = ?", id, c.getRef()));
        if (rows.isEmpty()) {
            throw new NotFoundException("Document " + id + " not found on " + c.getRef());
        }
        Map<String, Object> d = rows.get(0);
        custody.verify((String) d.get("sha256"), (String) d.get("storage_path"), "case document " + id);
        custody.logAccess("CASE", id, c.getRef(), (String) d.get("file_name"), user.getUsername(), "STAFF");
        return new com.nsure.portfolio.ClaimDocumentService.StoredFile((String) d.get("file_name"),
                (String) d.get("content_type"), ((Number) d.get("size_bytes")).longValue(),
                files.read((String) d.get("storage_path"), "case document " + id));
    }

    private Map<String, Object> receive(ServiceCase c, org.springframework.web.multipart.MultipartFile file,
            String type, String actor, String channel) {
        String documentType = type == null || type.isBlank() ? "Supporting evidence" : type.trim();
        if (documentType.length() > 60) {
            throw new IllegalArgumentException("Keep the document type to 60 characters.");
        }
        com.nsure.portfolio.DocumentIntake.Upload upload = com.nsure.portfolio.DocumentIntake.read(file);
        quarantine.receiveEvidence(com.nsure.portfolio.DocumentQuarantine.SERVICE_CASE, c.getId(), c.getRef(),
                c.getCustomerName(), upload, documentType, actor, channel);
        return documentsOf(c);
    }

    private Map<String, Object> documentsOf(ServiceCase c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("caseRef", c.getRef());
        out.put("documents", jdbc.queryForList("select id, document_type, file_name, content_type, size_bytes,"
                + " uploaded_by, uploaded_at, channel, released_by from svc_case_document where case_ref = ? order by"
                + " id desc", c.getRef()).stream().map(ServiceDeskService::camel).toList());
        out.put("held", quarantine.held(com.nsure.portfolio.DocumentQuarantine.SERVICE_CASE, c.getRef()));
        return out;
    }

    private ServiceCase ownCase(String ref, CustomerAccount customer) {
        ServiceCase c = em.createQuery("select c from ServiceCase c where c.ref = :ref and c.customerRef = :customer",
                        ServiceCase.class)
                .setParameter("ref", ref == null ? "" : ref.toUpperCase(Locale.ROOT))
                .setParameter("customer", customer.getRef())
                .getResultStream().findFirst().orElse(null);
        if (c == null) {
            throw new NotFoundException("Case " + ref + " not found");
        }
        return c;
    }

    /** The business calendar SLA times are counted in (Operations > Workflow), or null when none is set up. */
    private Map<String, Object> calendar() {
        List<Map<String, Object>> rows = jdbc.queryForList("select work_start_hour, work_end_hour, working_days,"
                + " holidays from wf_calendar where id = 1");
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = lower(rows).get(0);
        Map<String, Object> calendar = new LinkedHashMap<>();
        calendar.put("workStartHour", ((Number) r.get("work_start_hour")).intValue());
        calendar.put("workEndHour", ((Number) r.get("work_end_hour")).intValue());
        calendar.put("workingDays", r.get("working_days"));
        calendar.put("holidays", r.get("holidays"));
        return calendar;
    }

    private static Object value(Map<String, Object> row, String column) {
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static List<Map<String, Object>> lower(List<Map<String, Object>> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            row.forEach((k, v) -> m.put(k.toLowerCase(Locale.ROOT), v instanceof java.sql.Timestamp t
                    ? t.toLocalDateTime() : v instanceof java.sql.Date d ? d.toLocalDate() : v));
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            StringBuilder name = new StringBuilder();
            boolean up = false;
            for (char ch : k.toLowerCase(Locale.ROOT).toCharArray()) {
                if (ch == '_') {
                    up = true;
                } else {
                    name.append(up ? Character.toUpperCase(ch) : ch);
                    up = false;
                }
            }
            out.put(name.toString(), v instanceof java.sql.Timestamp t ? t.toLocalDateTime().toString() : v);
        });
        return out;
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
