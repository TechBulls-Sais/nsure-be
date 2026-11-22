package com.nsure.integration;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** §11 Identity/KYC/AML: matched outcomes with evidence and expiry, pending during outages, compliance referrals. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-screening;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000",
        "nsure.integration.screening.retry-ms=3600000"})
@AutoConfigureMockMvc
class ScreeningApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void everyCheckGetsOneOutcomeAndAHitIsDecidedByCompliance() throws Exception {
        String clear = customer("Goitse Clear", "+267 71 950 001");
        mockMvc.perform(json(post("/integration/screening"), "onboarding", "{\"customerRef\":\"" + clear + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAR"))
                .andExpect(jsonPath("$.evidenceRef").value(containsString("SIMSCR-")))
                .andExpect(jsonPath("$.expiresOn").value(LocalDate.now().plusMonths(12).toString()));

        String hit = customer("Watchlist Tester", "+267 71 950 002");
        String ref = JsonPath.read(mockMvc.perform(json(post("/integration/screening"), "onboarding",
                        "{\"customerRef\":\"" + hit + "\"}"))
                .andExpect(jsonPath("$.status").value("REFERRED"))
                .andExpect(jsonPath("$.referralStatus").value("OPEN"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        assertThat(jdbc.queryForObject("select count(*) from staff_alert where alert_type = 'SCREENING_REFERRAL'",
                Long.class)).isPositive();
        mockMvc.perform(json(post("/integration/screening"), "onboarding", "{\"customerRef\":\"" + hit + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/integration/screening/" + ref + "/disposition"), "onboarding",
                "{\"outcome\":\"CLEARED\",\"note\":\"Different date of birth from the listed person\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/integration/screening/" + ref + "/disposition"), "admin",
                "{\"outcome\":\"CLEARED\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/integration/screening/" + ref + "/disposition"), "admin",
                        "{\"outcome\":\"CLEARED\",\"note\":\"Different date of birth from the listed person\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEAR"))
                .andExpect(jsonPath("$.referralStatus").value("CLEARED"));

        // The requester cannot decide their own referral.
        String own = customer("Sanctioned Sample", "+267 71 950 003");
        String ownRef = JsonPath.read(mockMvc.perform(json(post("/integration/screening"), "admin",
                "{\"customerRef\":\"" + own + "\"}")).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/integration/screening/" + ownRef + "/disposition"), "admin",
                "{\"outcome\":\"CONFIRMED_MATCH\",\"note\":\"Matches the listed person exactly\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/integration/screening").with(signedIn("compliance", "password")))
                .andExpect(status().isOk());
    }

    @Test
    void aProviderOutageLeavesTheCheckPendingUntilItAnswers() throws Exception {
        String customer = customer("Pending Neo", "+267 71 950 004");
        mockMvc.perform(json(put("/integration/interfaces/KYC_AML/availability"), "ops",
                "{\"available\":false,\"reason\":\"Screening provider maintenance\"}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/integration/screening"), "onboarding", "{\"customerRef\":\"" + customer + "\"}"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(1));
        mockMvc.perform(json(put("/integration/interfaces/KYC_AML/availability"), "ops",
                        "{\"available\":true,\"reason\":\"Screening provider back\"}"))
                .andExpect(jsonPath("$.outcome").value(containsString("1 pending screening check(s) answered")));
        assertThat(jdbc.queryForObject("select status from int_screening_check where customer_ref = ?", String.class,
                customer)).isEqualTo("CLEAR");
        mockMvc.perform(get("/integration/reconciliation").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[?(@.code == 'KYC_AML')].lines[0].measure").value(
                        org.hamcrest.Matchers.hasItem("Screening checks")));
    }

    private String customer(String name, String phone) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\",\"phone\":\"" + phone
                        + "\",\"dateOfBirth\":\"1990-07-07\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
