package com.nsure.common.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The transition table of one aggregate's state machine (spec §09): which named moves exist, the states each may
 * start from, the state it leads to, and the permission a person needs to make it (none for a system move). Every
 * status write on the aggregate is checked against this table, so a move that is not listed cannot happen through any
 * path; history and events are recorded by the owning workflow in the same transaction.
 */
public final class Lifecycle {

    /** One move. {@code permission} is null for a move only the system makes (a schedule, a provider callback). */
    public record Transition(String action, Set<String> from, String to, String permission) {

        public boolean bySystem() {
            return permission == null;
        }
    }

    private final String aggregate;
    private final Set<String> initial;
    private final List<Transition> transitions;

    private Lifecycle(String aggregate, Set<String> initial, List<Transition> transitions) {
        this.aggregate = aggregate;
        this.initial = Collections.unmodifiableSet(initial);
        this.transitions = Collections.unmodifiableList(transitions);
    }

    public static Builder of(String aggregate) {
        return new Builder(aggregate);
    }

    public String aggregate() {
        return aggregate;
    }

    /** The states a new record may be created in. */
    public Set<String> initial() {
        return initial;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    /** Every state named in the table, in the order first named. */
    public Set<String> states() {
        Set<String> states = new LinkedHashSet<>(initial);
        for (Transition t : transitions) {
            states.addAll(t.from());
            states.add(t.to());
        }
        return states;
    }

    /** The move {@code action} makes from {@code state}, or null when it cannot be made from there. */
    public Transition find(String action, String state) {
        for (Transition t : transitions) {
            if (t.action().equals(action) && t.from().contains(state)) {
                return t;
            }
        }
        return null;
    }

    /** Whether the table names {@code action} at all. */
    public boolean knows(String action) {
        return transitions.stream().anyMatch(t -> t.action().equals(action));
    }

    /** The moves a person may ask for from {@code state} (system moves excluded), each named once, in table order. */
    public List<String> personActionsFrom(String state) {
        Set<String> actions = new LinkedHashSet<>();
        for (Transition t : transitions) {
            if (!t.bySystem() && t.from().contains(state)) {
                actions.add(t.action());
            }
        }
        return List.copyOf(actions);
    }

    /** Whether some move leads from {@code from} to {@code to}; null {@code from} is creation. */
    public boolean allows(String from, String to) {
        if (from == null) {
            return initial.contains(to);
        }
        return transitions.stream().anyMatch(t -> t.from().contains(from) && t.to().equals(to));
    }

    /**
     * Refuses a status write that the table does not allow, with 409 ILLEGAL_TRANSITION. Staying in the same state is
     * always allowed: it records a fact without changing the state.
     */
    public void check(String ref, String from, String to) {
        if (Objects.equals(from, to) && from != null) {
            return;
        }
        if (!allows(from, to)) {
            throw new IllegalTransitionException(aggregate, ref, from, to);
        }
    }

    public static final class Builder {

        private final String aggregate;
        private final Set<String> initial = new LinkedHashSet<>();
        private final List<Transition> transitions = new ArrayList<>();

        private Builder(String aggregate) {
            this.aggregate = aggregate;
        }

        public Builder initial(String... states) {
            initial.addAll(List.of(states));
            return this;
        }

        /** A move a person asks for, needing {@code permission}. */
        public Builder person(String action, String permission, String to, String... from) {
            Objects.requireNonNull(permission, "permission");
            return add(action, permission, to, from);
        }

        /** A move only the system makes. */
        public Builder system(String action, String to, String... from) {
            return add(action, null, to, from);
        }

        /** A move made by a command that checks its own authority; never offered as a person action by the table. */
        public Builder move(String action, String to, String... from) {
            return add(action, null, to, from);
        }

        private Builder add(String action, String permission, String to, String... from) {
            Set<String> starts = Set.of(from);
            for (Transition existing : transitions) {
                if (existing.action().equals(action)
                        && existing.from().stream().anyMatch(starts::contains)) {
                    throw new IllegalStateException(aggregate + ": " + action + " is defined twice from the same state");
                }
            }
            transitions.add(new Transition(action, starts, to, permission));
            return this;
        }

        public Lifecycle build() {
            return new Lifecycle(aggregate, new LinkedHashSet<>(initial), new ArrayList<>(transitions));
        }
    }
}
