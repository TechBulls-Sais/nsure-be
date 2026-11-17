package com.nsure.security.otp;

/**
 * The messaging provider that delivers one-time codes (SMS, e-mail). Nsure ships no implementation: an installation
 * that has a provider registers one as a Spring bean. Without one, outside the dev/demo profiles, nothing that needs a
 * code (customer sign-up, staff and partner sign-in) is allowed, rather than falling back to a fixed code.
 */
public interface OneTimeCodeSender {

    /**
     * Sends {@code code} to {@code destination} (a phone number or e-mail address). Throws if the provider did not
     * accept the message, so the caller does not tell the person a code is on its way.
     */
    void send(OneTimeCodes.Purpose purpose, String destination, String code);
}
