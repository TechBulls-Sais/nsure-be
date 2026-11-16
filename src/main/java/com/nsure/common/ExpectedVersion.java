package com.nsure.common;

import com.nsure.common.exception.StaleVersionException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Optimistic concurrency for people (NS-TXN-001): a client sends the version it read in {@code If-Match}; a command
 * on a record that has moved on since is refused with 409 {@code STALE_VERSION} and a refresh path, so nobody
 * overwrites another operator's accepted change. Without the header the command runs against the current record
 * (API clients and older screens), still protected from concurrent transactions by the row version.
 */
public final class ExpectedVersion {

    public static final String HEADER = "If-Match";

    private ExpectedVersion() {
    }

    /** Refuses the command when the caller's {@code If-Match} names another version of {@code what}. */
    public static void check(String what, long current) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        String header = attributes.getRequest().getHeader(HEADER);
        if (header == null || header.isBlank() || "*".equals(header.trim())) {
            return;
        }
        long expected;
        try {
            expected = Long.parseLong(header.trim().replace("W/", "").replace("\"", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("If-Match must be the version number of the record you read.");
        }
        if (expected != current) {
            throw new StaleVersionException(what + " was changed by someone else since you opened it. Refresh to see"
                    + " the latest and try again.");
        }
    }
}
