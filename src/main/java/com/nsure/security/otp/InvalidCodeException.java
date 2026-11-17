package com.nsure.security.otp;

/** A one-time code was wrong, expired, already used or cancelled after too many attempts (answered 422). */
public class InvalidCodeException extends IllegalArgumentException {

    public InvalidCodeException(String message) {
        super(message);
    }
}
