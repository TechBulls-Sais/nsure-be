package com.nsure.servicedesk;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nsure.portfolio.PortfolioViews;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Response shapes for the service desk. SLA figures are computed at read time. */
public final class ServiceDeskViews {

    private ServiceDeskViews() {
    }

    /**
     * A case as staff see it. {@code elapsedMinutes} is wall-clock age; {@code adjustedMinutes} excludes paused time
     * (AT-SH10-03). {@code effectiveDueAt} is the due date with the current pause added; {@code breached} means not
     * resolved or closed and past it.
     */
    public record CaseView(
            String ref,
            String caseType,
            String category,
            String customerRef,
            String customerName,
            String linkedRef,
            String subject,
            String description,
            String channel,
            String priority,
            String status,
            String owner,
            String openedBy,
            String openedAt,
            String dueAt,
            String effectiveDueAt,
            String pausedAt,
            String pauseReason,
            int pausedMinutes,
            long elapsedMinutes,
            long adjustedMinutes,
            long minutesToDue,
            boolean breached,
            String resolution,
            String resolvedAt,
            String closedAt,
            int version,
            // NS-SH10-004/005/001 (work package e).
            String requestedRemedy,
            String resolutionCode,
            String outcome,
            int escalationLevel,
            String escalatedAt,
            String externalReference,
            Integer workflowVersion,
            Integer slaHours,
            String slaBasis,
            String pauseCode,
            String pauseEvidence,
            String pauseExpectedResume,
            String pauseRequestedBy,
            String pauseApprovedBy,
            boolean pausePending,
            int pausedWorkingMinutes,
            long workingMinutes) {

        static CaseView of(ServiceCase c, LocalDateTime now) {
            return of(c, now, null);
        }

        /** With the business calendar, the working minutes so far and a working-time effective due time. */
        static CaseView of(ServiceCase c, LocalDateTime now, java.util.Map<String, Object> calendar) {
            Sla sla = Sla.of(c, now, calendar);
            boolean pending = c.getPauseRequestedBy() != null && !ServiceCase.PAUSED.equals(c.getStatus());
            return new CaseView(c.getRef(), c.getCaseType(), c.getCategory(), c.getCustomerRef(), c.getCustomerName(),
                    blank(c.getLinkedRef()), c.getSubject(), blank(c.getDescription()), c.getChannel(),
                    c.getPriority(), c.getStatus(), blank(c.getOwner()), c.getOpenedBy(), time(c.getOpenedAt()),
                    time(c.getDueAt()), time(sla.effectiveDue()), time(c.getPausedAt()),
                    ServiceCase.PAUSED.equals(c.getStatus()) ? blank(c.getPauseReason()) : "",
                    c.getPausedMinutes(), sla.elapsed(), sla.adjusted(), sla.minutesToDue(), sla.breached(),
                    blank(c.getResolution()), time(c.getResolvedAt()), time(c.getClosedAt()), c.getVersion(),
                    blank(c.getRequestedRemedy()), blank(c.getResolutionCode()), blank(c.getOutcome()),
                    c.getEscalationLevel(), time(c.getEscalatedAt()), blank(c.getExternalReference()),
                    c.getWorkflowVersion(), c.getSlaHours(), c.getSlaBasis() == null ? "ELAPSED" : c.getSlaBasis(),
                    ServiceCase.PAUSED.equals(c.getStatus()) || pending ? blank(c.getPauseCode()) : "",
                    ServiceCase.PAUSED.equals(c.getStatus()) || pending ? blank(c.getPauseEvidence()) : "",
                    c.getPauseExpectedResume() == null || !(ServiceCase.PAUSED.equals(c.getStatus()) || pending) ? ""
                            : c.getPauseExpectedResume().toString(),
                    pending || ServiceCase.PAUSED.equals(c.getStatus()) ? blank(c.getPauseRequestedBy()) : "",
                    ServiceCase.PAUSED.equals(c.getStatus()) ? blank(c.getPauseApprovedBy()) : "", pending,
                    c.getPausedWorkingMinutes(), sla.working());
        }
    }

    /** A case with its workflow history, oldest first; a reopen keeps the earlier resolution here. */
    public record CaseDetail(@JsonProperty("case") CaseView serviceCase, List<PortfolioViews.HistoryView> history) {
    }

    /** What the customer sees of their own case: no internal owner or notes. */
    public record CustomerCaseView(
            String ref,
            String caseType,
            String category,
            String subject,
            String description,
            String linkedRef,
            String channel,
            String status,
            String openedAt,
            String dueAt,
            String resolution,
            String resolvedAt,
            String closedAt,
            String requestedRemedy,
            String outcome) {

        static CustomerCaseView of(ServiceCase c, LocalDateTime now) {
            return new CustomerCaseView(c.getRef(), c.getCaseType(), c.getCategory(), c.getSubject(),
                    blank(c.getDescription()), blank(c.getLinkedRef()), c.getChannel(), c.getStatus(),
                    time(c.getOpenedAt()), time(Sla.of(c, now, null).effectiveDue()), blank(c.getResolution()),
                    time(c.getResolvedAt()), time(c.getClosedAt()), blank(c.getRequestedRemedy()),
                    blank(c.getOutcome()));
        }
    }

    record Sla(LocalDateTime effectiveDue, long elapsed, long adjusted, long minutesToDue, boolean breached,
            long working) {

        /**
         * Elapsed (wall-clock) and adjusted (less pauses) minutes, and with the business calendar the working minutes
         * less paused working time. A case counted in working hours stays due later while it is paused by the working
         * time the pause has used.
         */
        static Sla of(ServiceCase c, LocalDateTime now, java.util.Map<String, Object> calendar) {
            Sla wall = of(c, now);
            if (calendar == null || !"WORKING_HOURS".equals(c.getSlaBasis())) {
                return wall;
            }
            boolean done = ServiceCase.RESOLVED.equals(c.getStatus()) || ServiceCase.CLOSED.equals(c.getStatus());
            LocalDateTime end = done
                    ? (c.getResolvedAt() != null ? c.getResolvedAt() : c.getClosedAt() != null ? c.getClosedAt() : now)
                    : now;
            boolean paused = ServiceCase.PAUSED.equals(c.getStatus()) && c.getPausedAt() != null;
            long pausedNow = paused ? com.nsure.ops.OpsService.workingMinutesBetween(c.getPausedAt(), now, calendar) : 0;
            long working = Math.max(0, com.nsure.ops.OpsService.workingMinutesBetween(c.getOpenedAt(), end, calendar)
                    - c.getPausedWorkingMinutes() - pausedNow);
            LocalDateTime due = paused ? com.nsure.ops.OpsService.addWorkingMinutes(c.getDueAt(), pausedNow, calendar)
                    : c.getDueAt();
            return new Sla(due, wall.elapsed(), wall.adjusted(), Duration.between(now, due).toMinutes(),
                    !done && !paused && now.isAfter(due), working);
        }

        static Sla of(ServiceCase c, LocalDateTime now) {
            boolean done = ServiceCase.RESOLVED.equals(c.getStatus()) || ServiceCase.CLOSED.equals(c.getStatus());
            LocalDateTime end = done
                    ? (c.getResolvedAt() != null ? c.getResolvedAt() : c.getClosedAt() != null ? c.getClosedAt() : now)
                    : now;
            long currentPause = ServiceCase.PAUSED.equals(c.getStatus()) && c.getPausedAt() != null
                    ? Math.max(0, Duration.between(c.getPausedAt(), now).toMinutes())
                    : 0;
            long elapsed = Math.max(0, Duration.between(c.getOpenedAt(), end).toMinutes());
            long adjusted = Math.max(0, elapsed - c.getPausedMinutes() - currentPause);
            LocalDateTime effectiveDue = c.getDueAt().plusMinutes(currentPause);
            long toDue = Duration.between(now, effectiveDue).toMinutes();
            return new Sla(effectiveDue, elapsed, adjusted, toDue, !done && now.isAfter(effectiveDue), adjusted);
        }
    }

    static String time(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    static String blank(String value) {
        return value == null ? "" : value;
    }
}
