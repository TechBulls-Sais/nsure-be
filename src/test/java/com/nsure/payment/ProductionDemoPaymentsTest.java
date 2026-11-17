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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A demonstration installation on the prod profile: with only NSURE_DEMO_PAYMENTS=true (card gateway left unset),
 * every method settles through the mock or the hosted-checkout simulator, exactly as in development.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-payment-prod-demo;DB_CLOSE_DELAY=-1",
        DB_USER, DB_PASSWORD, MIGRATE, INSURER_CODE, INSURER_ENVIRONMENT, INSURER_NAME, BIND_IDENTITY,
        PUBLIC_BASE_URL, UI_BASE_URL, EXPOSE_OTP,
        "nsure.payments.demo=true"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
class ProductionDemoPaymentsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PaymentSettlementService settlement;

    @Autowired
    private PaymentGateways gateways;

    private ProductionPaymentFixtures fixtures;

    @BeforeEach
    void fixtures() {
        fixtures = new ProductionPaymentFixtures(mockMvc, em, tx);
    }

    @Test
    void mobileMoneyAndEftSettleThroughTheMock() throws Exception {
        assertThat(gateways.simulatorsEnabled()).isTrue();
        Map<String, String> customers = Map.of("Orange Money", "demoorange", "MyZaka", "demomyzaka",
                "Bank EFT", "demoeft");
        for (Map.Entry<String, String> entry : customers.entrySet()) {
            String policy = fixtures.pendingPolicy("Demo " + entry.getValue(), entry.getValue());
            mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), entry.getValue(),
                            "{\"method\":\"" + entry.getKey() + "\"}"))
                    .andExpect(status().isOk());
            assertThat(fixtures.policyStatus(policy)).isEqualTo("Active");
            assertThat(fixtures.count("select count(t) from PaymentTransaction t where t.policyRef = '" + policy
                    + "' and t.provider = 'MOCK' and t.status = 'SETTLED'")).isEqualTo(1);
        }
    }

    @Test
    void cardsGoThroughTheHostedCheckoutSimulator() throws Exception {
        String policy = fixtures.pendingPolicy("Tebogo Demo", "tebogodemo");
        String started = mockMvc.perform(json(post("/checkout/" + policy + "/pay"), "tebogodemo",
                        "{\"method\":\"Card\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(started, "$.mode")).isEqualTo("REDIRECT");
        assertThat(JsonPath.<String>read(started, "$.redirectUrl")).startsWith("http://localhost/api/payment/simulator/");
        String token = settlement.transaction(((Number) JsonPath.read(started, "$.transactionId")).longValue())
                .getProviderToken();

        mockMvc.perform(get("/payment/simulator/" + token)).andExpect(status().isOk());
        mockMvc.perform(post("/payment/simulator/" + token).param("decision", "pay")).andExpect(status().isFound());
        mockMvc.perform(get("/payment/return").param("TransID", token))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .startsWith("http://localhost/#/buy/confirmation?policy=" + policy));
        assertThat(fixtures.policyStatus(policy)).isEqualTo("Active");
        assertThat(fixtures.count("select count(p) from PremiumPayment p where p.policyRef = '" + policy + "'"))
                .isEqualTo(1);
    }
}
