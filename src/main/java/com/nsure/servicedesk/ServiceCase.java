package com.nsure.servicedesk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;

/**
 * A service request or complaint (SH10). {@code version} gives optimistic concurrency, so two operators can never
 * both act on the case without one of them seeing a conflict (NS-TXN-001, NS-SH10-002). While PAUSED the SLA clock
 * stops; on resume the paused minutes are added to {@code dueAt} (AT-SH10-03).
 */
@Entity
@Table(name = "svc_case")
public class ServiceCase {

    public static final String SERVICE_REQUEST = "SERVICE_REQUEST";
    public static final String COMPLAINT = "COMPLAINT";

    public static final String OPEN = "OPEN";
    public static final String IN_PROGRESS = "IN_PROGRESS";
    public static final String PAUSED = "PAUSED";
    public static final String RESOLVED = "RESOLVED";
    public static final String CLOSED = "CLOSED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_ref", nullable = false, unique = true, length = 20)
    private String ref;

    @Column(name = "case_type", nullable = false, length = 20)
    private String caseType;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(name = "customer_ref", nullable = false, length = 20)
    private String customerRef;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "linked_ref", length = 40)
    private String linkedRef;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 20)
    private String priority;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 100)
    private String owner;

    @Column(name = "opened_by", nullable = false, length = 100)
    private String openedBy;

    @Column(name = "opened_at", nullable = false)
    private LocalDateTime openedAt;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

    @Column(name = "pause_reason", length = 500)
    private String pauseReason;

    @Column(name = "paused_minutes", nullable = false)
    private int pausedMinutes;

    @Column(length = 1000)
    private String resolution;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Version
    @Column(nullable = false)
    private int version;

    // NS-SH10-004: what the customer asked to be put right, the coded resolution and (complaints) the outcome.
    @Column(name = "requested_remedy", length = 300)
    private String requestedRemedy;

    @Column(name = "resolution_code", length = 40)
    private String resolutionCode;

    @Column(name = "outcome", length = 30)
    private String outcome;

    // The escalation level reached (0 = none) and an external referral (e.g. the ombudsman's reference).
    @Column(name = "escalation_level", nullable = false)
    private int escalationLevel;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "external_reference", length = 60)
    private String externalReference;

    // NS-SH10-001: the workflow version and SLA the case was opened under; a later definition change does not move it.
    @Column(name = "workflow_version")
    private Integer workflowVersion;

    @Column(name = "sla_hours")
    private Integer slaHours;

    // WORKING_HOURS (business calendar) or ELAPSED (cases opened before the calendar applied).
    @Column(name = "sla_basis", length = 20)
    private String slaBasis;

    // NS-SH10-005: an approved pause reason with its evidence and expected resume date; who asked and who approved.
    @Column(name = "pause_code", length = 40)
    private String pauseCode;

    @Column(name = "pause_evidence", length = 300)
    private String pauseEvidence;

    @Column(name = "pause_expected_resume")
    private java.time.LocalDate pauseExpectedResume;

    @Column(name = "pause_requested_by", length = 100)
    private String pauseRequestedBy;

    @Column(name = "pause_approved_by", length = 100)
    private String pauseApprovedBy;

    @Column(name = "paused_working_minutes", nullable = false)
    private int pausedWorkingMinutes;

    @Column(name = "unowned_alerted_at")
    private LocalDateTime unownedAlertedAt;

    protected ServiceCase() {
    }

    ServiceCase(String ref, String caseType, String category, String customerRef, String customerName,
            String linkedRef, String subject, String description, String channel, String priority, String openedBy,
            int slaHours) {
        this.ref = ref;
        this.caseType = caseType;
        this.category = category;
        this.customerRef = customerRef;
        this.customerName = customerName;
        this.linkedRef = linkedRef;
        this.subject = subject;
        this.description = description;
        this.channel = channel;
        this.priority = priority;
        this.status = OPEN;
        this.openedBy = openedBy;
        this.openedAt = LocalDateTime.now();
        this.dueAt = openedAt.plusHours(slaHours);
        this.pausedMinutes = 0;
    }

    void assign(String owner) {
        this.owner = owner;
        if (OPEN.equals(status)) {
            status = IN_PROGRESS;
        }
    }

    void pause(String reason) {
        status = PAUSED;
        pausedAt = LocalDateTime.now();
        pauseReason = reason;
    }

    /** A pause for an approved reason (NS-SH10-005); one that needs approval waits until someone else approves it. */
    void requestPause(String code, String label, String evidence, java.time.LocalDate expectedResume, String by,
            boolean needsApproval) {
        pauseCode = code;
        pauseEvidence = evidence;
        pauseExpectedResume = expectedResume;
        pauseRequestedBy = by;
        pauseApprovedBy = null;
        if (!needsApproval) {
            pause(label);
        }
    }

    void approvePause(String label, String approver) {
        pauseApprovedBy = approver;
        pause(label);
    }

    void declinePause() {
        pauseCode = null;
        pauseEvidence = null;
        pauseExpectedResume = null;
        pauseRequestedBy = null;
    }

    /** Opened under a workflow version, with its SLA counted in working hours of the business calendar. */
    void sla(Integer workflowVersion, int hours, LocalDateTime due, String basis) {
        this.workflowVersion = workflowVersion;
        this.slaHours = hours;
        this.dueAt = due;
        this.slaBasis = basis;
    }

    /** Restarts the clock: the due time moves out by the working time the case was paused. */
    long resumeWorking(LocalDateTime newDue, long workingMinutes) {
        long minutes = Math.max(0, java.time.Duration.between(pausedAt, LocalDateTime.now()).toMinutes());
        pausedMinutes += (int) minutes;
        pausedWorkingMinutes += (int) workingMinutes;
        dueAt = newDue;
        pausedAt = null;
        pauseCode = null;
        pauseRequestedBy = null;
        status = owner == null ? OPEN : IN_PROGRESS;
        return minutes;
    }

    void remedy(String requestedRemedy) {
        this.requestedRemedy = requestedRemedy;
    }

    void resolveWith(String resolution, String code, String outcome) {
        resolve(resolution);
        this.resolutionCode = code;
        this.outcome = outcome;
    }

    void escalate(int level, String externalReference) {
        this.escalationLevel = level;
        this.escalatedAt = LocalDateTime.now();
        if (externalReference != null) {
            this.externalReference = externalReference;
        }
    }

    void unownedAlerted() {
        this.unownedAlertedAt = LocalDateTime.now();
    }

    public String getRequestedRemedy() {
        return requestedRemedy;
    }

    public String getResolutionCode() {
        return resolutionCode;
    }

    public String getOutcome() {
        return outcome;
    }

    public int getEscalationLevel() {
        return escalationLevel;
    }

    public LocalDateTime getEscalatedAt() {
        return escalatedAt;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public Integer getWorkflowVersion() {
        return workflowVersion;
    }

    public Integer getSlaHours() {
        return slaHours;
    }

    public String getSlaBasis() {
        return slaBasis;
    }

    public String getPauseCode() {
        return pauseCode;
    }

    public String getPauseEvidence() {
        return pauseEvidence;
    }

    public java.time.LocalDate getPauseExpectedResume() {
        return pauseExpectedResume;
    }

    public String getPauseRequestedBy() {
        return pauseRequestedBy;
    }

    public String getPauseApprovedBy() {
        return pauseApprovedBy;
    }

    public int getPausedWorkingMinutes() {
        return pausedWorkingMinutes;
    }

    public LocalDateTime getUnownedAlertedAt() {
        return unownedAlertedAt;
    }

    /** Restarts the clock and returns the minutes it was stopped, which push the due date out. */
    long resume() {
        long minutes = Math.max(0, java.time.Duration.between(pausedAt, LocalDateTime.now()).toMinutes());
        pausedMinutes += (int) minutes;
        dueAt = dueAt.plusMinutes(minutes);
        pausedAt = null;
        status = owner == null ? OPEN : IN_PROGRESS;
        return minutes;
    }

    void resolve(String resolution) {
        this.resolution = resolution;
        this.resolvedAt = LocalDateTime.now();
        this.status = RESOLVED;
    }

    void reopen() {
        resolution = null;
        resolvedAt = null;
        resolutionCode = null;
        outcome = null;
        status = owner == null ? OPEN : IN_PROGRESS;
    }

    void close() {
        closedAt = LocalDateTime.now();
        status = CLOSED;
    }

    public Long getId() {
        return id;
    }

    public String getRef() {
        return ref;
    }

    public String getCaseType() {
        return caseType;
    }

    public String getCategory() {
        return category;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getLinkedRef() {
        return linkedRef;
    }

    public String getSubject() {
        return subject;
    }

    public String getDescription() {
        return description;
    }

    public String getChannel() {
        return channel;
    }

    public String getPriority() {
        return priority;
    }

    public String getStatus() {
        return status;
    }

    public String getOwner() {
        return owner;
    }

    public String getOpenedBy() {
        return openedBy;
    }

    public LocalDateTime getOpenedAt() {
        return openedAt;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public LocalDateTime getPausedAt() {
        return pausedAt;
    }

    public String getPauseReason() {
        return pauseReason;
    }

    public int getPausedMinutes() {
        return pausedMinutes;
    }

    public String getResolution() {
        return resolution;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public LocalDateTime getClosedAt() {
        return closedAt;
    }

    public int getVersion() {
        return version;
    }
}
