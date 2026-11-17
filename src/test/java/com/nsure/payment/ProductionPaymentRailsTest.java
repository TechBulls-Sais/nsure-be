package com.nsure.payment;

import static com.nsure.payment.ProductionPaymentFixtures.BIND_IDENTITY;
import static com.nsure.payment.ProductionPaymentFixtures.DB_PASSWORD;
import static com.nsure.payment.ProductionPaymentFixtures.DB_USER;
import static com.nsure.payment.ProductionPaymentFixtures.EXPOSE_OTP;
import static com.nsure.payment.ProductionPaymentFixtures.INSURER_CODE;
import static com.nsure.payment.ProductionPaymentFixtures.INSURER_ENVIRONMENT;
import static com.nsure.payment.ProductionPaymentFixtures.INSURER_NAME;
import static com.nsure.payment.ProductionPaymentFixtures.MIGRATE;
import static com.nsure.payment.ProductionPaymentFixtures.PUBLIC_BASE_URL;
import static com.nsure.payment.ProductionPaymentFixtures.UI_BASE_URL;
import static com.nsure.payment.ProductionPaymentFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nsure.NsureApplication;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The prod profile without demo payments: no mock or simulated rail may confirm money or activate cover. The payment
 * settings are the prod profile's own (application-prod.yml; NSURE_CARD_GATEWAY and NSURE_DEMO_PAYMENTS unset).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-payment-prod;DB_CLOSE_DELAY=-1",
        DB_USER, DB_PASSWORD, MIGRATE, INSURER_CODE, INSURER_ENVIRONMENT, INSURER_NAME, BIND_IDENTITY,
        PUBLIC_BASE_URL, UI_BASE_URL, EXPOSE_OTP})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProductionPaymentRailsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PaymentSettlementService settlement;

    @Autowired
    private PaymentSweep sweep;

    @Autowired
    private PaymentGateways gateways;

    private ProductionPaymentFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new ProductionPaymentFixtures(mockMvc, em, tx);
    }

    @Test
    void mobileMoneyAndEftAreRefusedAndNeverActivateCover() throws Exception {
        assertThat(gateways.simulatorsEnabled()).isFalse();
        String policy = fixtures.pendingPolicy("Mpho Prod", "mphoprod");
        for (String method : List.of("Orange Money", "MyZaka", "Bank EFT")) {
            // Both customer entry points: the checkout API and the shop's pay action.
            mockMvc.perform(json(post("/checkout/" + policy + "/pay"), "mphoprod", "{\"method\":\"" + method + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(Matchers.startsWith(method + " payments are not available")));
            mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "mphoprod",
                            "{\"method\":\"" + method + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(Matchers.startsWith(method + " payments are not available")));
        }
        assertNothingCollected(policy);
        assertThat(fixtures.count("select count(t) from PaymentTransaction t where t.policyRef = '" + policy + "'"))
                .isZero();
    }

    @Test
    void cardPaymentsAreRefusedWithoutARealCardProvider() throws Exception {
        String policy = fixtures.pendingPolicy("Kabo Prod", "kaboprod");
        mockMvc.perform(json(post("/checkout/" + policy + "/pay"), "kaboprod", "{\"method\":\"Card\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.startsWith("Card payments are not available")));
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "kaboprod", "{\"method\":\"Card\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertNothingCollected(policy);
        assertThat(fixtures.count("select count(t) from PaymentTransaction t where t.policyRef = '" + policy + "'"))
                .isZero();
    }

    @Test
    void anAttemptASimulatedRailStartedEarlierIsNeverConfirmed() throws Exception {
        String policy = fixtures.pendingPolicy("Naledi Prod", "lediprod");
        // Left in the database by the simulator and the mock, e.g. while demo payments were on.
        String card = legacyAttempt(policy, HostedCheckoutSimulator.PROVIDER, "Card",
                LocalDateTime.now().plusMinutes(30));
        String wallet = legacyAttempt(policy, PaymentGateways.MOCK, "Orange Money",
                LocalDateTime.now().minusMinutes(1));

        // The simulator's page is gone: it can neither be shown nor mark the checkout paid.
        mockMvc.perform(get("/payment/simulator/" + card)).andExpect(status().isNotFound());
        mockMvc.perform(post("/payment/simulator/" + card).param("decision", "pay"))
                .andExpect(status().isNotFound());

        // Every trigger asks the rail; none is told "paid".
        mockMvc.perform(get("/payment/return").param("TransID", card))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .startsWith("http://localhost/#/buy/payment/pending"));
        mockMvc.perform(post("/payment/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("TransactionToken", card).param("Result", "000"))
                .andExpect(status().isOk());
        settlement.settle(wallet, PaymentSettlementService.CALLBACK);
        sweep.sweep(LocalDateTime.now().plusMinutes(1));

        PaymentTransaction open = transaction(card);
        assertThat(open.getStatus()).isEqualTo(PaymentTransaction.PENDING_VERIFICATION);
        assertThat(fixtures.count("select count(t) from PaymentTransaction t where t.id = " + open.getId()
                + " and t.providerResultCode = 'NO_PROVIDER'")).isEqualTo(1);
        // Past its expiry, an unconfirmed attempt expires instead of settling.
        assertThat(transaction(wallet).getStatus()).isEqualTo(PaymentTransaction.EXPIRED);
        assertThat(fixtures.count("select count(c) from SimulatedCheckout c where c.token = '" + card
                + "' and c.outcome = 'PAID'")).isEqualTo(1);
        assertNothingCollected(policy);
    }

    @Test
    void aSimulatedCardGatewayStopsStartUp() {
        // Same environment as above, but the operator points cards at the simulator without demo payments.
        // Command-line arguments, like the environment in a deployment, outrank application-prod.yml.
        String[] args = List.of("spring.datasource.url=jdbc:h2:mem:nsure-payment-prod-refused;DB_CLOSE_DELAY=-1",
                        DB_USER, DB_PASSWORD, MIGRATE, INSURER_CODE, INSURER_ENVIRONMENT, INSURER_NAME, BIND_IDENTITY,
                        PUBLIC_BASE_URL, UI_BASE_URL, "server.port=0", "nsure.payments.card-gateway=SIMULATOR")
                .stream().map(property -> "--" + property).toArray(String[]::new);
        assertThatThrownBy(() -> new SpringApplicationBuilder(NsureApplication.class)
                .profiles("prod")
                .run(args)
                .close())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("NSURE_CARD_GATEWAY=NONE");
    }

    private void assertNothingCollected(String policy) {
        assertThat(fixtures.policyStatus(policy)).isEqualTo("Pending Payment");
        assertThat(fixtures.count("select count(p) from PremiumPayment p where p.policyRef = '" + policy + "'"))
                .isZero();
        assertThat(fixtures.count("select count(t) from PaymentTransaction t where t.policyRef = '" + policy
                + "' and t.status = 'SETTLED'")).isZero();
        assertThat(fixtures.count("select count(e) from OutboxEvent e where e.eventType = 'PolicyIssued'"
                + " and e.aggregateRef = '" + policy + "'")).isZero();
    }

    /** An open attempt as the simulator or the mock left it, for the policy's full agreed premium; returns its token. */
    private String legacyAttempt(String policy, String provider, String method, LocalDateTime expiresAt) {
        String token = UUID.randomUUID().toString();
        tx.executeWithoutResult(s -> {
            Object[] row = em.createQuery("select p.customerRef, p.premium from InsurancePolicy p where p.ref = :ref",
                    Object[].class).setParameter("ref", policy).getSingleResult();
            BigDecimal amount = ((BigDecimal) row[1]).setScale(2, RoundingMode.HALF_UP);
            String reference = "NSP-" + policy + "-" + token.substring(0, 8).toUpperCase();
            PaymentTransaction attempt = new PaymentTransaction(policy, (String) row[0], provider, method, reference,
                    UUID.randomUUID().toString(), amount, "BWP", "FIRST_PREMIUM", LocalDate.now(),
                    LocalDate.now().plusMonths(1).minusDays(1), expiresAt, "legacy");
            if (HostedCheckoutSimulator.PROVIDER.equals(provider)) {
                SimulatedCheckout checkout = new SimulatedCheckout(token, reference, amount, "BWP", "legacy");
                checkout.decide(SimulatedCheckout.PAID, amount);
                em.persist(checkout);
                attempt.redirected(token, "http://localhost/api/payment/simulator/" + token, expiresAt);
            } else {
                attempt.inline(token);
            }
            em.persist(attempt);
        });
        return token;
    }

    private PaymentTransaction transaction(String token) {
        return tx.execute(s -> em.createQuery("select t from PaymentTransaction t where t.providerToken = :token",
                PaymentTransaction.class).setParameter("token", token).getSingleResult());
    }
}
