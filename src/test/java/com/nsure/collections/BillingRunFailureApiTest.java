package com.nsure.collections;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * §14 failure and concurrency: a crash part-way through the billing run (here the second instalment it issues) leaves
 * no instalment and no InvoiceIssued behind, reports the failed operation with an actionable error, and running it
 * again issues every instalment exactly once.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-billing-crash;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class BillingRunFailureApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoSpyBean
    private BillingService billing;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @AfterEach
    void healthy() {
        reset(spy());
    }

    /** The Mockito spy behind the transactional proxy: stubbing through the proxy would start its advice. */
    private BillingService spy() {
        return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(billing);
    }

    @Test
    void aCrashDuringTheBillingRunLeavesNothingHalfDoneAndARerunIssuesEachInstalmentOnce() throws Exception {
        String first = duePolicy("Crash First", "+267 71 600 001");
        String second = duePolicy("Crash Second", "+267 71 600 002");
        int instalments = count("select count(*) from bil_instalment");
        int invoices = count("select count(*) from int_outbox_event where event_type = 'InvoiceIssued'");

        // The second instalment of the run fails, as if the server died mid-batch.
        doCallRealMethod().doThrow(new IllegalStateException("Simulated crash")).when(spy())
                .instalmentFor(any(), any(), any(), any(), any());
        mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password")))
                .andExpect(status().isInternalServerError());
        assertThat(count("select count(*) from bil_instalment")).isEqualTo(instalments);
        assertThat(count("select count(*) from int_outbox_event where event_type = 'InvoiceIssued'")).isEqualTo(invoices);

        // In the background the same crash is a FAILED operation that says nothing was kept.
        reset(spy());
        doCallRealMethod().doThrow(new IllegalStateException("Simulated crash")).when(spy())
                .instalmentFor(any(), any(), any(), any(), any());
        String failed = follow(mockMvc.perform(post("/collections/billing-run").param("async", "true")
                        .with(signedIn("finance", "password")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat((String) JsonPath.read(failed, "$.status")).isEqualTo("FAILED");
        assertThat((String) JsonPath.read(failed, "$.error")).contains("nothing it did was kept");
        assertThat(count("select count(*) from bil_instalment")).isEqualTo(instalments);

        // Healthy again: the rerun issues each due instalment once, and a further run issues nothing more.
        reset(spy());
        String done = follow(mockMvc.perform(post("/collections/billing-run").param("async", "true")
                        .with(signedIn("finance", "password")))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat((String) JsonPath.read(done, "$.status")).isEqualTo("SUCCEEDED");
        for (String policy : new String[] {first, second}) {
            assertThat(count("select count(*) from bil_instalment where policy_ref = '" + policy + "'")).isEqualTo(2);
        }
        mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issued").value(0));
        for (String policy : new String[] {first, second}) {
            assertThat(count("select count(*) from bil_instalment where policy_ref = '" + policy + "'")).isEqualTo(2);
            assertThat(count("select count(*) from int_outbox_event where event_type = 'InvoiceIssued' and payload like"
                    + " '%\"policyRef\":\"" + policy + "\"%'")).isEqualTo(2);
        }
    }

    /** Polls the operation until it finishes. */
    private String follow(String accepted) throws Exception {
        String id = JsonPath.read(accepted, "$.id");
        for (int i = 0; i < 100; i++) {
            String body = mockMvc.perform(get("/operations/" + id).with(signedIn("finance", "password")))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String state = JsonPath.read(body, "$.status");
            if (state.equals("SUCCEEDED") || state.equals("FAILED")) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Operation " + id + " did not finish");
    }

    /** A paid policy whose next premium fell due five days ago, so the billing run issues it. */
    private String duePolicy(String name, String phone) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                                "{\"name\":\"" + name + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\","
                                        + "\"phone\":\"" + phone + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                                + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        jdbc.update("update ins_policy set next_premium_due = ? where record_ref = ?", LocalDate.now().minusDays(5), policy);
        return policy;
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
