package com.nsure.platform;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Governance around the rule-based assistants (no AI model is called): review authority, four eyes on drafted
 * messages, overrides with the final human text and sending only after review, withheld log outputs, approved version
 * changes, threshold monitoring and the section 17 registers (NS-AIG-001/002/004/005, §15-DRAFT-COMMS, §17).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-governed-assistants;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class GovernedAssistantsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aDraftIsReviewedByAnotherPersonOverriddenWithAReasonAndOnlyThenSent() throws Exception {
        String customer = customer("Lesedi Draft", "+267 71 900 001");
        String draft = mockMvc.perform(json(post("/platform/assistants/draft-message"), "admin",
                        "{\"customerRef\":\"" + customer + "\",\"purpose\":\"PREMIUM_REMINDER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateRef").value("PREMIUM_DUE_EMAIL v1"))
                .andExpect(jsonPath("$.engine").value("Deterministic rules (no AI model)"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(draft, "$.id");
        // No policy yet: the policy, product, amount and due date cannot be merged, so confidence is computed lower.
        assertThat((Integer) JsonPath.read(draft, "$.confidence")).isLessThan(100);
        assertThat(JsonPath.<List<String>>read(draft, "$.explanation")).anySatisfy(l -> assertThat(l).contains("merge field"));

        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/send"), "secadmin", "{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/review"), "admin",
                "{\"decision\":\"ACCEPT\"}")).andExpect(status().isForbidden());
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/review"), "secadmin",
                "{\"decision\":\"OVERRIDE\",\"finalText\":\"Dear Lesedi, your premium is due.\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/review"), "secadmin",
                        "{\"decision\":\"OVERRIDE\",\"reason\":\"Template fields not on record\","
                                + "\"finalText\":\"Dear Lesedi, your first premium is due. Pay in the portal.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("OVERRIDDEN"));
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/send"), "secadmin", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageRef").isNotEmpty());
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/send"), "secadmin", "{}"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select body from com_message where customer_ref = ? order by id desc",
                String.class, customer)).isEqualTo("Dear Lesedi, your first premium is due. Pay in the portal.");
        mockMvc.perform(get("/platform/assistants/log").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].finalText").value(hasItem(containsString("first premium"))))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].reviewReason").value(hasItem("Template fields not on record")));
        // NS-AIG-002: a log reader who may not handle customer messages does not see the drafted text.
        mockMvc.perform(get("/platform/assistants/log").with(signedIn("compliance", "password")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].output[0]").value(hasItem(containsString("Withheld"))));
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where action = 'Assistant output overridden'",
                Long.class)).isPositive();
    }

    @Test
    void reviewersNeedAuthorityForTheSubjectAndVersionChangesNeedASecondPerson() throws Exception {
        String claim = jdbc.queryForList("select record_ref from ins_claim order by id", String.class).get(0);
        Integer id = JsonPath.read(mockMvc.perform(post("/platform/assistants/claim-summary/" + claim)
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output[*]").value(hasItem(containsString("Freshness"))))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // The service desk may read customers, not claims: it cannot review a claim summary.
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/review"), "service", "{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/review"), "admin", "{\"decision\":\"ACCEPT\"}"))
                .andExpect(jsonPath("$.reviewStatus").value("ACCEPTED"));

        mockMvc.perform(json(post("/platform/assistants/RISK_SCORE/changes"), "admin",
                        "{\"toVersion\":\"rules-1.1\",\"changeNote\":\"Claims indicator at 2 claims instead of 3\"}"))
                .andExpect(status().isOk());
        Long change = jdbc.queryForObject("select max(id) from ai_model_change", Long.class);
        mockMvc.perform(json(post("/platform/assistants/changes/" + change + "/decision"), "admin", "{\"approve\":true}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/platform/assistants/changes/" + change + "/decision"), "secadmin",
                "{\"approve\":true,\"note\":\"Validated on the review log\"}")).andExpect(status().isOk());
        String customer = customer("Version Kopano", "+267 71 900 002");
        mockMvc.perform(post("/platform/assistants/risk-indicators/" + customer).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.modelVersion").value("rules-1.1"));
    }

    @Test
    void assistantsBelowTheirThresholdAreFlaggedAndScopeDecisionsAreRecorded() throws Exception {
        String customer = customer("Threshold Mpho", "+267 71 900 003");
        for (int i = 0; i < 5; i++) {
            Integer id = JsonPath.read(mockMvc.perform(post("/platform/assistants/risk-indicators/" + customer)
                    .with(signedIn("admin", "password"))).andReturn().getResponse().getContentAsString(), "$.id");
            mockMvc.perform(json(post("/platform/assistants/log/" + id + "/review"), "admin",
                    "{\"decision\":\"REJECT\",\"reason\":\"Indicator does not fit this customer\"}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/platform/assistants/monitoring").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.code == 'RISK_SCORE')].status").value(hasItem("BELOW_THRESHOLD")))
                .andExpect(jsonPath("$[?(@.code == 'RISK_SCORE')].falsePositiveRate").value(hasItem(100.0)));
        assertThat(jdbc.queryForObject("select count(*) from staff_alert where alert_type = 'ASSISTANT_BELOW_THRESHOLD'",
                Long.class)).isPositive();

        mockMvc.perform(get("/platform/ai-coverage").with(signedIn("support", "password")))
                .andExpect(jsonPath("$.length()").value(6));
        mockMvc.perform(get("/platform/scope-exceptions").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.code == 'FR-012')].status").value(hasItem("PROPOSED")));
        mockMvc.perform(json(post("/platform/scope-exceptions/FR-012/decision"), "support",
                "{\"decision\":\"ACCEPTED\",\"note\":\"Insurer letter 2026-11-01\"}")).andExpect(status().isForbidden());
        mockMvc.perform(json(post("/platform/scope-exceptions/FR-012/decision"), "admin",
                        "{\"decision\":\"ACCEPTED\",\"note\":\"Insurer steering committee decision 2026-11-01\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/platform/scope-exceptions/FR-012/decision"), "admin",
                "{\"decision\":\"REJECTED\",\"note\":\"Second thoughts on the decision\"}")).andExpect(status().isConflict());
    }

    private String customer(String name, String phone) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\",\"phone\":\"" + phone
                        + "\",\"dateOfBirth\":\"1984-02-02\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
