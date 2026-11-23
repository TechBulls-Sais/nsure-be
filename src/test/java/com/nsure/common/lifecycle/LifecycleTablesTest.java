package com.nsure.common.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nsure.portfolio.ClaimWorkflow;
import com.nsure.portfolio.PolicyWorkflow;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * §09 acceptance: for each state machine, every allowed transition passes and every other pair of states is refused
 * with 409 ILLEGAL_TRANSITION.
 */
class LifecycleTablesTest {

    static Stream<Arguments> allowed() {
        List<Arguments> moves = new ArrayList<>();
        for (Lifecycle machine : List.of(PolicyWorkflow.LIFECYCLE, ClaimWorkflow.LIFECYCLE)) {
            for (String state : machine.initial()) {
                moves.add(Arguments.of(machine.aggregate(), "create", null, state));
            }
            for (Lifecycle.Transition t : machine.transitions()) {
                for (String from : t.from()) {
                    moves.add(Arguments.of(machine.aggregate(), t.action(), from, t.to()));
                }
            }
        }
        return moves.stream();
    }

    static Stream<Arguments> forbidden() {
        List<Arguments> moves = new ArrayList<>();
        for (Lifecycle machine : List.of(PolicyWorkflow.LIFECYCLE, ClaimWorkflow.LIFECYCLE)) {
            List<String> states = new ArrayList<>(machine.states());
            for (String to : states) {
                if (!machine.initial().contains(to)) {
                    moves.add(Arguments.of(machine.aggregate(), null, to));
                }
            }
            for (String from : states) {
                for (String to : states) {
                    if (!from.equals(to) && !machine.allows(from, to)) {
                        moves.add(Arguments.of(machine.aggregate(), from, to));
                    }
                }
            }
        }
        return moves.stream();
    }

    @ParameterizedTest(name = "{0}: {1} {2} -> {3}")
    @MethodSource("allowed")
    void everyListedTransitionIsAllowed(String aggregate, String action, String from, String to) {
        assertThatCode(() -> machine(aggregate).check("REF-1", from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}: {1} -> {2} refused")
    @MethodSource("forbidden")
    void everyOtherTransitionIsRefused(String aggregate, String from, String to) {
        assertThatThrownBy(() -> machine(aggregate).check("REF-1", from, to))
                .isInstanceOf(IllegalTransitionException.class)
                .hasMessageContaining("REF-1")
                .hasMessageContaining(to);
    }

    @Test
    void terminalPolicyStatesHaveNoWayOut() {
        for (String end : List.of(PolicyWorkflow.EXPIRED, PolicyWorkflow.CANCELLED, PolicyWorkflow.LAPSED,
                PolicyWorkflow.CLAIMED, PolicyWorkflow.TERMINATED)) {
            assertThat(PolicyWorkflow.LIFECYCLE.transitions()).noneMatch(t -> t.from().contains(end));
        }
    }

    @Test
    void graceIsInCoverAndEndsOnlyByPaymentOrWithoutCover() {
        assertThat(PolicyWorkflow.IN_COVER).contains(PolicyWorkflow.GRACE);
        assertThat(PolicyWorkflow.LIFECYCLE.allows(PolicyWorkflow.ACTIVE, PolicyWorkflow.GRACE)).isTrue();
        assertThat(PolicyWorkflow.LIFECYCLE.allows(PolicyWorkflow.GRACE, PolicyWorkflow.ACTIVE)).isTrue();
        assertThat(PolicyWorkflow.LIFECYCLE.allows(PolicyWorkflow.GRACE, PolicyWorkflow.ACTIVE_NO_COVER)).isTrue();
        // Grace never lapses directly: cover must first have ended.
        assertThat(PolicyWorkflow.LIFECYCLE.allows(PolicyWorkflow.GRACE, PolicyWorkflow.LAPSED)).isFalse();
    }

    @Test
    void claimPersonActionsMatchWhatTheApiOffers() {
        assertThat(ClaimWorkflow.allowedActions(ClaimWorkflow.INITIATED)).containsExactly("assess", "reject");
        assertThat(ClaimWorkflow.allowedActions(ClaimWorkflow.PAYOUT_SENT))
                .containsExactly("confirm-payout", "payout-failed");
        assertThat(ClaimWorkflow.allowedActions(ClaimWorkflow.PAID)).containsExactly("close");
        assertThat(ClaimWorkflow.allowedActions(ClaimWorkflow.CLOSED)).containsExactly("reopen");
        // The bank's moves are the system's alone.
        assertThat(ClaimWorkflow.LIFECYCLE.find("bank-returned", ClaimWorkflow.CLOSED).bySystem()).isTrue();
        assertThat(ClaimWorkflow.allowedActions(ClaimWorkflow.REJECTED)).containsExactly("reopen");
    }

    @Test
    void anActionIsDefinedOnceFromAnyState() {
        assertThatThrownBy(() -> Lifecycle.of("Test").system("go", "B", "A").system("go", "C", "A").build())
                .isInstanceOf(IllegalStateException.class);
    }

    private static Lifecycle machine(String aggregate) {
        return Stream.of(PolicyWorkflow.LIFECYCLE, ClaimWorkflow.LIFECYCLE)
                .filter(m -> Objects.equals(m.aggregate(), aggregate)).findFirst().orElseThrow();
    }
}
