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
            int version) {

        static CaseView of(ServiceCase c, LocalDateTime now) {
            Sla sla = Sla.of(c, now);
            return new CaseView(c.getRef(), c.getCaseType(), c.getCategory(), c.getCustomerRef(), c.getCustomerName(),
                    blank(c.getLinkedRef()), c.getSubject(), blank(c.getDescription()), c.getChannel(),
                    c.getPriority(), c.getStatus(), blank(c.getOwner()), c.getOpenedBy(), time(c.getOpenedAt()),
                    time(c.getDueAt()), time(sla.effectiveDue()), time(c.getPausedAt()),
                    ServiceCase.PAUSED.equals(c.getStatus()) ? blank(c.getPauseReason()) : "",
                    c.getPausedMinutes(), sla.elapsed(), sla.adjusted(), sla.minutesToDue(), sla.breached(),
                    blank(c.getResolution()), time(c.getResolvedAt()), time(c.getClosedAt()), c.getVersion());
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
            String closedAt) {

        static CustomerCaseView of(ServiceCase c, LocalDateTime now) {
            return new CustomerCaseView(c.getRef(), c.getCaseType(), c.getCategory(), c.getSubject(),
                    blank(c.getDescription()), blank(c.getLinkedRef()), c.getChannel(), c.getStatus(),
                    time(c.getOpenedAt()), time(Sla.of(c, now).effectiveDue()), blank(c.getResolution()),
                    time(c.getResolvedAt()), time(c.getClosedAt()));
        }
    }

    record Sla(LocalDateTime effectiveDue, long elapsed, long adjusted, long minutesToDue, boolean breached) {

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
            return new Sla(effectiveDue, elapsed, adjusted, toDue, !done && now.isAfter(effectiveDue));
        }
    }

    static String time(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    static String blank(String value) {
        return value == null ? "" : value;
    }
}
