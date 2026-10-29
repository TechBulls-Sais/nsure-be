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
