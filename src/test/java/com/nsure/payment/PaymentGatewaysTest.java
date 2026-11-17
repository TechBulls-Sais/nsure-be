package com.nsure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Which rail each method gets: unchanged locally; under the prod profile no mock and no simulator, unless a
 * demonstration installation turns demo payments on.
 */
class PaymentGatewaysTest {

    private static final String[] NON_CARD = {"Orange Money", "MyZaka", "Bank EFT"};

    @Test
    void outsideProductionTheMockAndTheSimulatorStayAvailable() {
        PaymentGateways simulator = gateways(null, false, "SIMULATOR");
        assertThat(simulator.simulatorsEnabled()).isTrue();
        assertThat(simulator.forMethod("Card").provider()).isEqualTo(HostedCheckoutSimulator.PROVIDER);
        for (String method : NON_CARD) {
            assertThat(simulator.forMethod(method).provider()).isEqualTo(PaymentGateways.MOCK);
        }
        assertThat(gateways(null, false, "mock").forMethod("Card").provider()).isEqualTo(PaymentGateways.MOCK);
        assertThat(gateways(null, false, "").forMethod("Card").provider()).isEqualTo(HostedCheckoutSimulator.PROVIDER);
        assertThat(simulator.manual().provider()).isEqualTo(PaymentGateways.MANUAL);
    }

    @Test
    void productionStartsOnlyWithoutASimulatedCardGateway() {
        for (String simulated : new String[] {"SIMULATOR", "MOCK", "mock"}) {
            assertThatThrownBy(() -> gateways("prod", false, simulated))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("NSURE_CARD_GATEWAY=NONE")
                    .hasMessageContaining("NSURE_DEMO_PAYMENTS=true");
        }
        // A provider this build does not have is a misconfiguration everywhere, never a silent simulator.
        assertThatThrownBy(() -> gateways("prod", false, "DPO")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateways("prod", true, "DPO")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gateways(null, false, "DPO")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void productionRefusesEveryMethodWithoutARealProviderAndNeverVerifiesASimulatedAttempt() {
        for (String card : new String[] {"NONE", ""}) {
            PaymentGateways prod = gateways("prod", false, card);
            assertThat(prod.simulatorsEnabled()).isFalse();
            assertThatThrownBy(() -> prod.forMethod("Card"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("Card payments are not available yet");
            for (String method : NON_CARD) {
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
    }

    @Test
    void demoPaymentsOnTheProdProfileWorkAsInDevelopment() {
        PaymentGateways demo = gateways("prod", true, "");
        assertThat(demo.simulatorsEnabled()).isTrue();
        assertThat(demo.forMethod("Card").provider()).isEqualTo(HostedCheckoutSimulator.PROVIDER);
        for (String method : NON_CARD) {
            assertThat(demo.forMethod(method).provider()).isEqualTo(PaymentGateways.MOCK);
        }
        assertThat(gateways("prod", true, "SIMULATOR").forMethod("Card").provider())
                .isEqualTo(HostedCheckoutSimulator.PROVIDER);
        assertThat(gateways("prod", true, "MOCK").forMethod("Card").provider()).isEqualTo(PaymentGateways.MOCK);
        // A demo can still switch cards off explicitly.
        assertThatThrownBy(() -> gateways("prod", true, "NONE").forMethod("Card"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PaymentGateways gateways(String profile, boolean demo, String cardGateway) {
        MockEnvironment environment = new MockEnvironment();
        if (profile != null) {
            environment.setActiveProfiles(profile);
        }
        return new PaymentGateways(null, null, environment, demo, cardGateway, "http://localhost/api", 30);
    }
}
