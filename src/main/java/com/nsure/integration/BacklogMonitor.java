package com.nsure.integration;

import com.nsure.controls.StaffAlertService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AT-SH12-02: a backlog is monitored, not just counted. When the oldest undelivered event is older than
 * {@code nsure.integration.backlog-alert-minutes} (demo default 15), integration staff get an alert, at most once an
 * hour, naming the backlog and its age.
 */
@Component
public class BacklogMonitor {

    private static final Logger log = LoggerFactory.getLogger(BacklogMonitor.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");

    private final JdbcTemplate jdbc;
    private final StaffAlertService alerts;
    private final long thresholdMinutes;

    public BacklogMonitor(JdbcTemplate jdbc, StaffAlertService alerts,
            @Value("${nsure.integration.backlog-alert-minutes:15}") long thresholdMinutes) {
        this.jdbc = jdbc;
        this.alerts = alerts;
        this.thresholdMinutes = thresholdMinutes;
    }

    @Scheduled(fixedDelayString = "${nsure.integration.backlog-check-ms:60000}",
            initialDelayString = "${nsure.integration.backlog-check-ms:60000}")
    public void scheduled() {
        try {
            check();
        } catch (RuntimeException ex) {
            log.warn("Integration backlog check failed", ex);
        }
    }

    /** Raises the backlog alert when due; returns true when one was raised by this call. */
    public boolean check() {
        Object[] backlog = jdbc.queryForObject("select count(*), min(e.occurred_at) from int_event_delivery d"
                + " join int_outbox_event e on e.id = d.outbox_event_id where d.status = 'PENDING'", (rs, n) ->
                new Object[] {rs.getLong(1), rs.getTimestamp(2)});
        long pending = (Long) backlog[0];
        if (pending == 0 || backlog[1] == null) {
            return false;
        }
        LocalDateTime oldest = ((java.sql.Timestamp) backlog[1]).toLocalDateTime();
        long minutes = Duration.between(oldest, LocalDateTime.now()).toMinutes();
        if (minutes < thresholdMinutes) {
            return false;
        }
        return alerts.raise("INTEGRATION_BACKLOG", "READ_INTEGRATION", "Integration backlog is " + minutes
                        + " minutes old",
                pending + " delivery(ies) are waiting; the oldest event is from " + oldest.withNano(0)
                        + ". Check the interface catalogue for an unavailable interface and the dead-letter queue.",
                "integration-backlog:" + LocalDateTime.now().format(HOUR), "system");
    }
}
