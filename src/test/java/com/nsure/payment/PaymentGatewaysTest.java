package com.nsure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Which rail each method gets: unchanged locally and in demos; under the prod profile, no mock and no simulator. */
class PaymentGatewaysTest {

    @Test
    void outsideProductionTheMockAndTheSimulatorStayAvailable() {
        PaymentGateways simulator = gateways(null, "SIMULATOR");
        assertThat(simulator.simulatorsEnabled()).isTrue();
        assertThat(simulator.forMethod("Card").provider()).isEqualTo(HostedCheckoutSimulator.PROVIDER);
        for (String method : new String[] {"Orange Money", "MyZaka", "Bank EFT"}) {
            assertThat(simulator.forMethod(method).provider()).isEqualTo(PaymentGateways.MOCK);
        }
        assertThat(gateways(null, "mock").forMethod("Card").provider()).isEqualTo(PaymentGateways.MOCK);
        assertThat(gateways(null, "SIMULATOR").manual().provider()).isEqualTo(PaymentGateways.MANUAL);
    }

    @Test
    void productionStartsOnlyWithoutASimulatedCardGateway() {
        for (String simulated : new String[] {"SIMULATOR", "MOCK", "mock"}) {
            assertThatThrownBy(() -> gateways("prod", simulated))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NSURE_CARD_GATEWAY=NONE");
        }
        // A provider this build does not have is a misconfiguration everywhere, never a silent simulator.
        assertThatThrownBy(() -> gateways("prod", "DPO")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateways(null, "DPO")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRefusesEveryMethodWithoutARealProviderAndNeverVerifiesASimulatedAttempt() {
        PaymentGateways prod = gateways("prod", "NONE");
        assertThat(prod.simulatorsEnabled()).isFalse();
        for (String method : new String[] {"Card", "Orange Money", "MyZaka", "Bank EFT"}) {
            assertThatThrownBy(() -> prod.forMethod(method))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith(method + " payments are not available yet");
        }
        for (String provider : new String[] {PaymentGateways.MOCK, HostedCheckoutSimulator.PROVIDER}) {
            PaymentGateway.Verification verification = prod.byProvider(provider).verify("NSP-X", "token");
            assertThat(verification.isPaid()).isFalse();
            assertThat(verification.outcome()).isEqualTo(PaymentGateway.Outcome.PENDING);
        }
        // Staff-recorded receipts are not a simulator and keep their rail.
        assertThat(prod.manual().provider()).isEqualTo(PaymentGateways.MANUAL);
    }

    private static PaymentGateways gateways(String profile, String cardGateway) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        return new PaymentGateways(null, null, environment, cardGateway, "http://localhost/api", 30);
    }
}
