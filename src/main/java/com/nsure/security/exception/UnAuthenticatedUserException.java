package com.nsure.security.exception;

public class UnAuthenticatedUserException extends RuntimeException {

    public UnAuthenticatedUserException() {
        super("User is not authenticated.");
    }
}
