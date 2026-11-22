package com.nsure.integration;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** NS-TXN-004: a compensating event shows each leg it must correct, the original event, and stays an exception until done. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-compensations;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class CompensationTrackerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aReturnedPremiumShowsEveryLegAndWhatIsLeftToCorrect() throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "{\"name\":\"Comp Tsholo\","
                        + "\"email\":\"comp@example.com\",\"phone\":\"+267 71 960 001\",\"dateOfBirth\":\"1982-03-03\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + customer + "/kyc"), "{}")).andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":55}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/integration/outbox/dispatch").with(signedIn("admin", "password"))).andExpect(status().isOk());
        String receipt = jdbc.queryForObject("select receipt_ref from premium_payment where policy_ref = ?", String.class,
                policy);
        mockMvc.perform(json(post("/collections/receipts/" + receipt + "/reverse"),
                "{\"kind\":\"RETURNED\",\"reason\":\"Debit returned: insufficient funds\"}")).andExpect(status().isOk());

        String before = list();
        String path = "$[?(@.eventType == 'PaymentReturned' && @.aggregateRef == '" + policy + "')]";
        assertThat(JsonPath.<List<String>>read(before, path + ".status")).containsExactly("IN_PROGRESS");
        assertThat(JsonPath.<List<String>>read(before, path + ".original.eventType")).containsExactly("PremiumReceived");
        assertThat(JsonPath.<List<String>>read(before, path + ".legs[?(@.leg == 'Journal reversal')].state"))
                .containsExactly("IN_PROGRESS");
        // No consumer sends the customer a message about a returned premium: a manual follow-up for its owner.
        assertThat(JsonPath.<List<String>>read(before, path + ".legs[?(@.leg == 'Customer communication')].state"))
                .containsExactly("NOT_AUTOMATED");

        mockMvc.perform(post("/integration/outbox/dispatch").with(signedIn("admin", "password"))).andExpect(status().isOk());
        String after = list();
        assertThat(JsonPath.<List<String>>read(after, path + ".legs[?(@.leg == 'Journal reversal')].state"))
                .containsExactly("DONE");
        assertThat(JsonPath.<List<String>>read(after, path + ".status")).containsExactly("MANUAL_FOLLOW_UP");
        mockMvc.perform(get("/integration/compensations").with(signedIn("compliance", "password")))
                .andExpect(status().isForbidden());
    }

    private String list() throws Exception {
        return mockMvc.perform(get("/integration/compensations").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.with(signedIn("admin", "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
