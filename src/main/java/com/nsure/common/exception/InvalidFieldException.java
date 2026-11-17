package com.nsure.common.exception;

/**
 * A request field that is missing or not in the expected form (NS-API-001 schema error): answered 400 INVALID_REQUEST
 * with the field in {@code fieldErrors}, unlike a business rule refusal (422). It is an IllegalArgumentException, so
 * code that already treats invalid input that way keeps working.
 */
public class InvalidFieldException extends IllegalArgumentException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
