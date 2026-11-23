package com.nsure.common.lifecycle;

import com.nsure.common.exception.ConflictException;

/** A status write the aggregate's transition table does not allow (§09): answered 409 with code ILLEGAL_TRANSITION. */
public class IllegalTransitionException extends ConflictException {

    private final String aggregate;
    private final String from;
    private final String to;

    public IllegalTransitionException(String aggregate, String ref, String from, String to) {
        super(aggregate + " " + ref + " cannot move " + (from == null ? "into" : "from " + from + " to") + " " + to
                + ": the " + aggregate.toLowerCase(java.util.Locale.ROOT) + " lifecycle does not allow it.");
        this.aggregate = aggregate;
        this.from = from;
        this.to = to;
    }

    public String getAggregate() {
        return aggregate;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
