package com.nsure.integration;

/**
 * The interface a delivery or request needs is unavailable (here: switched off in the provider simulator). A transient
 * failure: the work is kept and retried, never dropped or blindly re-submitted (NS-SH12-003/004, AT-SH12-02).
 */
public class InterfaceUnavailableException extends RuntimeException {

    public InterfaceUnavailableException(String message) {
        super(message);
    }
}
