package com.nsure.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.NsureApplication;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The prod profile: no mock or simulated rail may confirm money or activate cover. The payment settings here are the
 * prod profile's own (application-prod.yml, NSURE_CARD_GATEWAY unset); the test supplies only the database and
 * identity the profile requires from the environment, and exposes the declaration code so a policy can reach Pending
 * Payment through the API.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-payment-prod;DB_CLOSE_DELAY=-1",
        ProductionPaymentRailsTest.DB_USER, ProductionPaymentRailsTest.DB_PASSWORD,
        ProductionPaymentRailsTest.MIGRATE, ProductionPaymentRailsTest.INSURER_CODE,
        ProductionPaymentRailsTest.INSURER_ENVIRONMENT, ProductionPaymentRailsTest.INSURER_NAME,
        ProductionPaymentRailsTest.BIND_IDENTITY, ProductionPaymentRailsTest.PUBLIC_BASE_URL,
        ProductionPaymentRailsTest.UI_BASE_URL,
        "nsure.declaration.expose-otp=true"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProductionPaymentRailsTest {

    static final String DB_USER = "spring.datasource.username=sa";
    static final String DB_PASSWORD = "spring.datasource.password=";
    /** The prod profile leaves migration to the migrate job; the test database needs its schema. */
    static final String MIGRATE = "spring.liquibase.enabled=true";
    static final String INSURER_CODE = "nsure.insurer.code=test-insurer";
    static final String INSURER_ENVIRONMENT = "nsure.insurer.environment=test";
    static final String INSURER_NAME = "nsure.insurer.display-name=Test Insurer";
    static final String BIND_IDENTITY = "nsure.insurer.bind-on-first-start=true";
    static final String PUBLIC_BASE_URL = "nsure.payments.public-base-url=http://localhost/api";
    static final String UI_BASE_URL = "nsure.payments.ui-base-url=http://localhost/#";

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

    @Test
    void mobileMoneyAndEftAreRefusedAndNeverActivateCover() throws Exception {
        assertThat(gateways.simulatorsEnabled()).isFalse();
        String policy = pendingPolicy("Mpho Prod", "mphoprod");
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
        assertThat(count("select count(t) from PaymentTransaction t where t.policyRef = '" + policy + "'")).isZero();
    }

    @Test
    void cardPaymentsAreRefusedWithoutARealCardProvider() throws Exception {
        String policy = pendingPolicy("Kabo Prod", "kaboprod");
        mockMvc.perform(json(post("/checkout/" + policy + "/pay"), "kaboprod", "{\"method\":\"Card\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.startsWith("Card payments are not available")));
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "kaboprod", "{\"method\":\"Card\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertNothingCollected(policy);
        assertThat(count("select count(t) from PaymentTransaction t where t.policyRef = '" + policy + "'")).isZero();
    }

    @Test
    void anAttemptASimulatedRailStartedEarlierIsNeverConfirmed() throws Exception {
        String policy = pendingPolicy("Naledi Prod", "lediprod");
        // Left in the database by the simulator and the mock before this installation ran with the prod profile.
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
        assertThat(count("select count(t) from PaymentTransaction t where t.id = " + open.getId()
                + " and t.providerResultCode = 'NO_PROVIDER'")).isEqualTo(1);
        // Past its expiry, an unconfirmed attempt expires instead of settling.
        assertThat(transaction(wallet).getStatus()).isEqualTo(PaymentTransaction.EXPIRED);
        assertThat(count("select count(c) from SimulatedCheckout c where c.token = '" + card
                + "' and c.outcome = 'PAID'")).isEqualTo(1);
        assertNothingCollected(policy);
    }

    @Test
    void aSimulatedCardGatewayStopsStartUp() {
        // Same environment as above, but the operator points cards at the simulator. Command-line arguments, like the
        // environment in a deployment, outrank application-prod.yml.
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
        assertThat(policyStatus(policy)).isEqualTo("Pending Payment");
        assertThat(count("select count(p) from PremiumPayment p where p.policyRef = '" + policy + "'")).isZero();
        assertThat(count("select count(t) from PaymentTransaction t where t.policyRef = '" + policy
                + "' and t.status = 'SETTLED'")).isZero();
        assertThat(count("select count(e) from OutboxEvent e where e.eventType = 'PolicyIssued'"
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

    private String pendingPolicy(String name, String username) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 019\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(json(post("/portfolio/shop"), username, "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(shop, "$.requests[0].id");
        String sent = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), username, "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), username,
                        "{\"code\":\"" + JsonPath.read(sent, "$.devCode") + "\",\"accuracy\":true,\"terms\":true,"
                                + "\"dataProtection\":true,\"falseInformation\":true}"))
                .andExpect(status().isOk());
        assertThat(policyStatus(policy)).isEqualTo("Pending Payment");
        return policy;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = user.equals("admin") ? "password" : "password1";
        return request.with(httpBasic(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String policyStatus(String ref) {
        return tx.execute(s -> em.createQuery("select p.status from InsurancePolicy p where p.ref = :ref", String.class)
                .setParameter("ref", ref).getSingleResult());
    }

    private long count(String jpql) {
        return tx.execute(s -> em.createQuery(jpql, Long.class).getSingleResult());
    }
}
