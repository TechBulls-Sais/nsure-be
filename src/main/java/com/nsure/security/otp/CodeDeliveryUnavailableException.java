package com.nsure.security.otp;

/** No one-time code can be delivered here (no messaging provider, or no contact to send to), so the step is refused. */
public class CodeDeliveryUnavailableException extends RuntimeException {

    public CodeDeliveryUnavailableException(String message) {
        super(message);
    }
}
