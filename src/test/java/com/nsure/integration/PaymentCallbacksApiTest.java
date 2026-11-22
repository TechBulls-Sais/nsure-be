package com.nsure.integration;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Signed payment callbacks (AT-SH12-01) and unknown provider outcomes that are re-queried, never re-sent (NS-SH12-004). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-payment-callbacks;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000",
        "nsure.integration.unknown-requery-ms=3600000"})
@AutoConfigureMockMvc
class PaymentCallbacksApiTest {

    private static final String SECRET = "local-payment-callback-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aRepeatedSignedCallbackIsAcknowledgedWithoutASecondEffect() throws Exception {
        String policy = pendingPolicy("Callback Kgosi", "+267 71 600 001");
        provider("OrangeMoney", "OM-CB-1", "50.00");
        String body = "{\"provider\":\"OrangeMoney\",\"providerTransactionId\":\"OM-CB-1\",\"policyRef\":\"" + policy
                + "\",\"amount\":\"50.00\",\"currency\":\"BWP\",\"method\":\"Orange Money\",\"status\":\"SETTLED\"}";
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String signature = "sha256=" + hmac(ts + "." + body);

        // Unsigned, wrongly signed, tampered or stale callbacks change nothing.
        mockMvc.perform(callback(body, null, ts)).andExpect(status().isForbidden());
        mockMvc.perform(callback(body.replace("50.00", "5.00"), signature, ts)).andExpect(status().isForbidden());
        String old = String.valueOf(Instant.now().getEpochSecond() - 3600);
        mockMvc.perform(callback(body, "sha256=" + hmac(old + "." + body), old)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from int_inbound_message where provider_ref = 'OM-CB-1'",
                Integer.class)).isZero();

        String first = mockMvc.perform(callback(body, signature, ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.channel").value("SIGNED_CALLBACK"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(callback(body, signature, ts))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.id").value((Integer) JsonPath.read(first, "$.id")));
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where event_type = 'ReceiptConfirmed'"
                + " and aggregate_ref = 'OM-CB-1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from premium_payment where policy_ref = ?", Integer.class,
                policy)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select payload from int_outbox_event where event_type = 'ReceiptConfirmed'"
                + " and aggregate_ref = 'OM-CB-1'", String.class)).contains("\"source\":\"SIGNED_CALLBACK\"");
    }

    @Test
    void theSimulatedProviderSendsSignedRepeatedAndTamperedCallbacks() throws Exception {
        String policy = pendingPolicy("Callback Sim", "+267 71 600 002");
        mockMvc.perform(json(post("/integration/simulator/payment-callbacks"), "ops",
                        "{\"provider\":\"MyZaka\",\"providerTransactionId\":\"MZ-SIM-1\",\"policyRef\":\"" + policy
                                + "\",\"amount\":\"50.00\",\"providerHolds\":true,\"repeat\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.attempts[1].status").value("APPLIED"))
                .andExpect(jsonPath("$.attempts[1].detail").value(containsString("no second effect")));
        mockMvc.perform(json(post("/integration/simulator/payment-callbacks"), "ops",
                        "{\"provider\":\"MyZaka\",\"providerTransactionId\":\"MZ-SIM-2\",\"policyRef\":\"" + policy
                                + "\",\"amount\":\"50.00\",\"tamper\":true}"))
                .andExpect(jsonPath("$.attempts[0].status").value("REFUSED"));
        mockMvc.perform(json(post("/integration/simulator/payment-callbacks"), "finance",
                "{\"provider\":\"MyZaka\",\"providerTransactionId\":\"MZ-SIM-3\",\"policyRef\":\"" + policy
                        + "\",\"amount\":\"50.00\"}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from premium_payment where policy_ref = ?", Integer.class,
                policy)).isEqualTo(1);
    }

    @Test
    void aTimedOutStatusQueryLeavesTheOutcomeUnknownUntilTheProviderAnswers() throws Exception {
        String policy = pendingPolicy("Unknown Outcome", "+267 71 600 003");
        provider("BankFeed", "BF-UNK-1", "50.00");
        mockMvc.perform(json(put("/integration/interfaces/BANK_PAYMENTS/availability"), "ops",
                "{\"available\":false,\"reason\":\"Bank status API timing out\"}")).andExpect(status().isOk());
        String inbound = mockMvc.perform(json(post("/integration/payment-notifications"), "ops",
                        "{\"provider\":\"BankFeed\",\"providerReference\":\"BF-UNK-1\",\"policyRef\":\"" + policy
                                + "\",\"amount\":\"50.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.detail").value(containsString("timed out")))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(inbound, "$.id");
        // Nothing is applied, suspended or re-sent while the outcome is unknown.
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where aggregate_ref = 'BF-UNK-1'",
                Integer.class)).isZero();
        mockMvc.perform(post("/integration/inbound/" + id + "/status-query").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.statusQueries").value(2));
        // The bank answers again: the unknown outcome is resolved by asking, and applied once.
        mockMvc.perform(json(put("/integration/interfaces/BANK_PAYMENTS/availability"), "ops",
                        "{\"available\":true,\"reason\":\"Bank status API restored\"}"))
                .andExpect(jsonPath("$.outcome").value(containsString("1 unknown payment outcome(s) resolved")));
        assertThat(jdbc.queryForObject("select status from int_inbound_message where id = ?", String.class, id))
                .isEqualTo("APPLIED");
        assertThat(jdbc.queryForObject("select count(*) from premium_payment where policy_ref = ?", Integer.class,
                policy)).isEqualTo(1);
        mockMvc.perform(post("/integration/inbound/" + id + "/status-query").with(signedIn("ops", "password")))
                .andExpect(status().isConflict());
    }

    private void provider(String provider, String ref, String amount) throws Exception {
        mockMvc.perform(json(post("/integration/simulator/provider-receipts"), "ops", "{\"provider\":\"" + provider
                + "\",\"providerReference\":\"" + ref + "\",\"amount\":\"" + amount + "\"}")).andExpect(status().isOk());
    }

    private String pendingPolicy(String name, String phone) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\",\"phone\":\"" + phone
                        + "\",\"dateOfBirth\":\"1985-03-14\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + customer + "/kyc"), "admin", "{}")).andExpect(status().isOk());
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static MockHttpServletRequestBuilder callback(String body, String signature, String ts) {
        MockHttpServletRequestBuilder r = post("/integration/callbacks/payments").contentType(MediaType.APPLICATION_JSON)
                .content(body).header("X-Nsure-Timestamp", ts);
        return signature == null ? r : r.header("X-Nsure-Signature", signature);
    }

    private static String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
