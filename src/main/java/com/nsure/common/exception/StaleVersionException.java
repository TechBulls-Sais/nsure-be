package com.nsure.common.exception;

/**
 * The caller acted on a version of a record that is no longer current (NS-TXN-001): answered 409 with code
 * {@code STALE_VERSION}, so the client refreshes instead of overwriting another person's accepted change.
 */
public class StaleVersionException extends ConflictException {

    public StaleVersionException(String message) {
        super(message);
    }
}
