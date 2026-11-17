package com.nsure.payment;


import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spec 13 settlement through the hosted-checkout simulator, and the Phase 4 exit criterion of spec 09: kill the
 * browser before the redirect returns and the sweep still activates the policy; fire the triggers concurrently and get
 * exactly one payment, one activation and one issue event.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-payment-settlement;DB_CLOSE_DELAY=-1",
        "nsure.payments.card-gateway=SIMULATOR",
        "nsure.payments.public-base-url=http://localhost/api",
        "nsure.payments.ui-base-url=http://localhost/#"})
@AutoConfigureMockMvc
class PaymentSettlementTest {

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

    @Test
    void aCardPaymentRedirectsThenSettlesOnTheBrowserReturn() throws Exception {
        String policy = pendingPolicy("Onalenna Pule", "onalenna");
        String first = pay("onalenna", policy, "Card");
        assertThat(JsonPath.<String>read(first, "$.mode")).isEqualTo("REDIRECT");
        assertThat(JsonPath.<String>read(first, "$.redirectUrl")).startsWith("http://localhost/api/payment/simulator/");
        long id = ((Number) JsonPath.read(first, "$.transactionId")).longValue();
        // S-8: a double-clicked Pay resumes the same attempt.
        assertThat(((Number) JsonPath.read(pay("onalenna", policy, "Card"), "$.transactionId")).longValue()).isEqualTo(id);

        mockMvc.perform(get("/checkout/transactions/" + id).with(signedIn("onalenna", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REDIRECTED"))
                .andExpect(jsonPath("$.terminal").value(false));

        String token = token(id);
        // The provider's hosted page renders (the customer's browser lands here).
        mockMvc.perform(get("/payment/simulator/" + token))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("Secure checkout").contains("BWP"));
        mockMvc.perform(post("/payment/simulator/" + token).param("decision", "pay"))
                .andExpect(status().isFound());
        mockMvc.perform(get("/payment/return").param("TransID", token))
                .andExpect(status().isFound())
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .isEqualTo("http://localhost/#/buy/confirmation?policy=" + policy + "&tx=" + id));
        assertThat(policyStatus(policy)).isEqualTo("Active");
        assertThat(count("select count(p) from PremiumPayment p where p.policyRef = '" + policy + "'")).isEqualTo(1);
        mockMvc.perform(get("/checkout/transactions/" + id).with(signedIn("onalenna", "password1")))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.policyNumber").value(policy))
                .andExpect(jsonPath("$.settledVia").value("RETURN"));
        // A second return is answered from the terminal state, without a second payment.
        mockMvc.perform(get("/payment/return").param("TransID", token)).andExpect(status().isFound());
        assertThat(count("select count(p) from PremiumPayment p where p.policyRef = '" + policy + "'")).isEqualTo(1);
    }

    @Test
    void theSweepSettlesAPaymentWhoseBrowserNeverCameBack() throws Exception {
        String policy = pendingPolicy("Kago Sebele", "kago");
        long id = ((Number) JsonPath.read(pay("kago", policy, "Card"), "$.transactionId")).longValue();
        mockMvc.perform(post("/payment/simulator/" + token(id)).param("decision", "pay"));
        // No /payment/return: the customer closed the browser.
        assertThat(policyStatus(policy)).isEqualTo("Pending Payment");
        assertThat(sweep.sweep(LocalDateTime.now().plusMinutes(1))).isGreaterThanOrEqualTo(1);
        assertThat(policyStatus(policy)).isEqualTo("Active");
        assertThat(settlement.transaction(id).getSettledVia()).isEqualTo("SWEEP");
    }

    @Test
    void concurrentTriggersSettleExactlyOnce() throws Exception {
        String policy = pendingPolicy("Tumelo Kgari", "tumelo");
        long id = ((Number) JsonPath.read(pay("tumelo", policy, "Card"), "$.transactionId")).longValue();
        String token = token(id);
        mockMvc.perform(post("/payment/simulator/" + token).param("decision", "pay"));

        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        String[] vias = {"RETURN", "CALLBACK", "SWEEP"};
        for (int i = 0; i < 10; i++) {
            String via = vias[i % 3];
            results.add(pool.submit(() -> {
                go.await();
                return settlement.settle(token, via).getStatus();
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (Future<String> result : results) {
            assertThat(result.get()).isEqualTo("SETTLED");
        }
        assertThat(count("select count(p) from PremiumPayment p where p.policyRef = '" + policy + "'")).isEqualTo(1);
        assertThat(count("select count(r) from PremiumReceipt r where r.customerRef = (select p.customerRef"
                + " from InsurancePolicy p where p.ref = '" + policy + "')")).isEqualTo(1);
        assertThat(count("select count(e) from OutboxEvent e where e.eventType = 'PolicyIssued'"
                + " and e.aggregateRef = '" + policy + "'")).isEqualTo(1);
        assertThat(policyStatus(policy)).isEqualTo("Active");
    }

    @Test
    void aDeclineFailsTheAttemptAndARetryStartsAFreshOne() throws Exception {
        String policy = pendingPolicy("Boipelo Moeng", "boipelo");
        long id = ((Number) JsonPath.read(pay("boipelo", policy, "Card"), "$.transactionId")).longValue();
        mockMvc.perform(post("/payment/simulator/" + token(id)).param("decision", "decline"));
        mockMvc.perform(get("/payment/return").param("TransID", token(id)))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location"))
                        .isEqualTo("http://localhost/#/buy/payment?status=failed&tx=" + id));
        assertThat(policyStatus(policy)).isEqualTo("Pending Payment");
        long retry = ((Number) JsonPath.read(pay("boipelo", policy, "Card"), "$.transactionId")).longValue();
        assertThat(retry).isNotEqualTo(id);
    }

    @Test
    void aChargeForTheWrongAmountFailsTheTamperCheck() throws Exception {
        String policy = pendingPolicy("Refilwe Masire", "refilwe");
        long id = ((Number) JsonPath.read(pay("refilwe", policy, "Card"), "$.transactionId")).longValue();
        mockMvc.perform(post("/payment/simulator/" + token(id)).param("decision", "pay").param("amount", "1.00"));
        mockMvc.perform(get("/payment/return").param("TransID", token(id)));
        PaymentTransaction failed = settlement.transaction(id);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getFailureReason()).startsWith("Amount or currency mismatch");
        assertThat(policyStatus(policy)).isEqualTo("Pending Payment");
        mockMvc.perform(get("/me/alerts").with(signedIn("support", "password")))
                .andExpect(jsonPath("$[?(@.type == 'PAYMENT_TAMPER')]").isNotEmpty());
    }

    @Test
    void theCallbackIsNeverAVerdictAndAlwaysAnswers200() throws Exception {
        mockMvc.perform(post("/payment/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("TransactionToken", "not-a-token").param("Result", "000"))
                .andExpect(status().isOk());
        String policy = pendingPolicy("Lesedi Phiri", "lesedi");
        long id = ((Number) JsonPath.read(pay("lesedi", policy, "Card"), "$.transactionId")).longValue();
        // The callback claims success, but the provider has no payment: nothing settles (S-13).
        mockMvc.perform(post("/payment/callback").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("TransactionToken", token(id)).param("Result", "000"))
                .andExpect(status().isOk());
        assertThat(policyStatus(policy)).isEqualTo("Pending Payment");
        assertThat(settlement.transaction(id).getStatus()).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    void aCustomerSeesOnlyTheirOwnPaymentAndStaffNeedReadPayment() throws Exception {
        String policy = pendingPolicy("Neo Ditshwanelo", "neoditsh");
        long id = ((Number) JsonPath.read(pay("neoditsh", policy, "Card"), "$.transactionId")).longValue();
        mockMvc.perform(get("/checkout/transactions/" + id).with(signedIn("customer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/checkout/transactions/" + id).with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/checkout/transactions/" + id).with(signedIn("support", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/payments/transactions/" + id + "/log").with(signedIn("compliance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operation").value("createToken.request"));
        // Staff cannot start a customer checkout: staff record payments through the policy workflow.
        mockMvc.perform(json(post("/checkout/" + policy + "/pay"), "admin", "{\"method\":\"Card\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theAgreedPremiumIsChargedAndNeverSilentlyRepriced() throws Exception {
        // Nsure Life NS-IL01-004 / NS-SH02-004 / AT-SH02-02: the premium agreed with the customer is what is charged,
        // even when today's rate table would price the policy differently; it is never rewritten behind their back.
        String policy = pendingPolicy("Lorato Agreed", "loratoagreed");
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.premium = :agreed, "
                        + "p.rateTableVersion = 'RT-AGREED-v1' where p.ref = :ref")
                .setParameter("agreed", new java.math.BigDecimal("123.45")).setParameter("ref", policy).executeUpdate());

        String started = pay("loratoagreed", policy, "Card");
        long id = ((Number) JsonPath.read(started, "$.transactionId")).longValue();
        assertThat(settlement.transaction(id).getAmount()).isEqualByComparingTo("123.45");
        java.math.BigDecimal premium = tx.execute(s -> em.createQuery(
                "select p.premium from InsurancePolicy p where p.ref = :ref", java.math.BigDecimal.class)
                .setParameter("ref", policy).getSingleResult());
        String version = tx.execute(s -> em.createQuery(
                "select p.rateTableVersion from InsurancePolicy p where p.ref = :ref", String.class)
                .setParameter("ref", policy).getSingleResult());
        assertThat(premium).isEqualByComparingTo("123.45");
        assertThat(version).isEqualTo("RT-AGREED-v1");
        assertThat(count("select count(h) from WorkflowLog h where h.entityRef = '" + policy + "' and h.action = 'Re-priced'"))
                .isZero();
    }

    private String pendingPolicy(String name, String username) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 009\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(json(post("/portfolio/shop"), username, "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(shop, "$.requests[0].id");
        // Spec 01 BR-5: the OTP-verified declaration before the first premium.
        String sent = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), username, "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), username,
                        "{\"code\":\"" + JsonPath.read(sent, "$.devCode") + "\",\"accuracy\":true,\"terms\":true,"
                                + "\"dataProtection\":true,\"falseInformation\":true}"))
                .andExpect(status().isOk());
        return policy;
    }

    private String pay(String username, String policy, String method) throws Exception {
        return mockMvc.perform(json(post("/checkout/" + policy + "/pay"), username, "{\"method\":\"" + method + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String user, String body) {
        String password = user.equals("admin") ? "password" : "password1";
        return request.with(signedIn(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String token(long id) {
        return settlement.transaction(id).getProviderToken();
    }

    private String policyStatus(String ref) {
        return tx.execute(s -> em.createQuery("select p.status from InsurancePolicy p where p.ref = :ref", String.class)
                .setParameter("ref", ref).getSingleResult());
    }

    private long count(String jpql) {
        return tx.execute(s -> em.createQuery(jpql, Long.class).getSingleResult());
    }
}
