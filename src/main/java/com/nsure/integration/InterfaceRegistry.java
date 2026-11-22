package com.nsure.integration;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The interface catalogue as the runtime sees it (NS-SH12-007): which interface each outbox consumer belongs to and
 * whether that interface is available. The dispatcher asks it before every delivery, so a simulated outage of an
 * interface holds its deliveries back as transient failures (AT-SH12-02). Read through a small cache that is dropped
 * whenever availability changes.
 */
@Component
public class InterfaceRegistry {

    /** One catalogue row's runtime facts. */
    public record Entry(String code, String name, String kind, String connection, String exceptionOwner,
            List<String> handlers, boolean available, String note) {
    }

    private final JdbcTemplate jdbc;
    private volatile Map<String, Entry> byCode;

    public InterfaceRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entry> entries() {
        return List.copyOf(load().values());
    }

    public Optional<Entry> entry(String code) {
        return Optional.ofNullable(code == null ? null : load().get(code));
    }

    /** The catalogue entry a consumer belongs to, if it is registered. */
    public Optional<Entry> forHandler(String handler) {
        return load().values().stream().filter(e -> e.handlers().contains(handler)).findFirst();
    }

    /** Why this consumer's interface cannot take deliveries now, or null when it can. */
    public String outage(String handler) {
        return forHandler(handler).filter(e -> !e.available())
                .map(e -> unavailableMessage(e))
                .orElse(null);
    }

    /** True unless the interface is registered and switched off. */
    public boolean available(String code) {
        return entry(code).map(Entry::available).orElse(true);
    }

    public static String unavailableMessage(Entry e) {
        return "Unavailable: " + e.name() + " is not responding (simulated outage"
                + (e.note() == null || e.note().isBlank() ? "" : ": " + e.note()) + ")";
    }

    public void invalidate() {
        byCode = null;
    }

    private Map<String, Entry> load() {
        Map<String, Entry> cached = byCode;
        if (cached != null) {
            return cached;
        }
        Map<String, Entry> fresh = new LinkedHashMap<>();
        jdbc.query("select code, name, kind, connection_mode, exception_owner, handlers, available, availability_note"
                + " from int_interface order by sort_order, code", rs -> {
                    String handlers = rs.getString("handlers");
                    fresh.put(rs.getString("code"), new Entry(rs.getString("code"), rs.getString("name"),
                            rs.getString("kind"), rs.getString("connection_mode"), rs.getString("exception_owner"),
                            handlers == null || handlers.isBlank() ? List.of()
                                    : Arrays.stream(handlers.split(",")).map(String::trim).toList(),
                            rs.getBoolean("available"), rs.getString("availability_note")));
                });
        byCode = fresh;
        return fresh;
    }
}
