package com.nsure.integration;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/**
 * NS-API-002: an Idempotency-Key on a claim, payment or collections command makes a repeat return the first answer
 * without a second effect, a changed request with the same key is a conflict, and keys are scoped to the caller.
 * Spec §10: a long-running command runs in the background with a status resource only its requester (or a holder
 * of its read permission) can see.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-idempotency;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class IdempotencyAndOperationsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void provisionModuleUsers() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aRepeatedClaimLodgementWithTheSameKeyReturnsTheFirstClaim() throws Exception {
        String policy = activePolicy("Idem Potent");
        String lodgement = "{\"policy\":\"" + policy + "\",\"claimantName\":\"Idem Potent\",\"claimantMobile\":\"+267 72 000 000\","
                + "\"claimantRelationship\":\"Self\",\"eventDate\":\"" + LocalDate.now().minusDays(1)
                + "\",\"description\":\"Admitted to hospital.\"}";
        String first = mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement).header("Idempotency-Key", "lodge-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(first, "$.id");
        String again = mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement).header("Idempotency-Key", "lodge-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.REPLAYED, "true"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(again, "$.id")).isEqualTo(claim);
        assertThat(claims(policy)).isEqualTo(1);

        // The same key with a different request is a conflict, not a second claim.
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement.replace("Admitted to hospital.", "Other"))
                        .header("Idempotency-Key", "lodge-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        // Keys belong to their caller: another person's identical key is their own request.
        mockMvc.perform(json(post("/portfolio/claims"), "admin", lodgement).header("Idempotency-Key", "lodge-1"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(IdempotencyFilter.REPLAYED));
        assertThat(claims(policy)).isEqualTo(2);
        // No key: no idempotency, as before.
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement)).andExpect(status().isOk());
        assertThat(claims(policy)).isEqualTo(3);
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", lodgement).header("Idempotency-Key", "bad key!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // A refused request keeps no record, so it can be corrected and retried with the same key.
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{\"reason\":\"\"}")
                        .header("Idempotency-Key", "reject-1"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{\"reason\":\"Duplicate notification\"}")
                        .header("Idempotency-Key", "reject-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"));
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{\"reason\":\"Duplicate notification\"}")
                        .header("Idempotency-Key", "reject-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(IdempotencyFilter.REPLAYED, "true"));
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where event_type = 'ClaimDeclined'"
                + " and aggregate_ref = ?", Integer.class, claim)).isEqualTo(1);
    }

    /** §14: a repeated policy issue with the same key and a changed payload is a conflict, not a second policy. */
    @Test
    void aRepeatedIssueWithAChangedPayloadIsAConflict() throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                                "{\"name\":\"Issue Twice\",\"email\":\"twice@example.com\",\"phone\":\"+267 71 000 010\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String issue = "{\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}";
        String first = mockMvc.perform(json(post("/portfolio/policies"), "admin", issue).header("Idempotency-Key", "issue-twice"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue).header("Idempotency-Key", "issue-twice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")));
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue.replace("20000", "30000"))
                        .header("Idempotency-Key", "issue-twice"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from ins_policy where customer_ref = ?", Integer.class, customer))
                .isEqualTo(1);
    }

    @Test
    void theBillingRunRunsInTheBackgroundWithAStatusOnlyItsRequesterCanRead() throws Exception {
        String accepted = mockMvc.perform(post("/collections/billing-run").param("async", "true")
                        .with(signedIn("finance", "password")))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/operations/")))
                .andExpect(jsonPath("$.type").value("BILLING_RUN"))
                .andExpect(jsonPath("$.requestedBy").value("finance"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(accepted, "$.id");
        String status = "ACCEPTED";
        String body = null;
        for (int i = 0; i < 100 && !status.equals("SUCCEEDED") && !status.equals("FAILED"); i++) {
            Thread.sleep(100);
            body = mockMvc.perform(get("/operations/" + id).with(signedIn("finance", "password")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            status = JsonPath.read(body, "$.status");
        }
        assertThat(status).isEqualTo("SUCCEEDED");
        assertThat((String) JsonPath.read(body, "$.resultRef")).startsWith("BILLING-RUN-");
        assertThat((Integer) JsonPath.read(body, "$.result.issued")).isNotNull();
        assertThat((String) JsonPath.read(body, "$.finishedAt")).isNotNull();

        // Someone without the read permission is told nothing about it; unknown ids are 404 too.
        mockMvc.perform(get("/operations/" + id).with(signedIn("assessor", "password")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/operations/" + id).with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/operations/00000000-0000-0000-0000-000000000000").with(signedIn("finance", "password")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/collections/billing-run").param("async", "true").with(signedIn("ops", "password")))
                .andExpect(status().isForbidden());
    }

    private int claims(String policy) {
        return jdbc.queryForObject("select count(*) from ins_claim where policy_ref = ?", Integer.class, policy);
    }

    private String activePolicy(String name) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"idem@example.com\",\"phone\":\"+267 71 000 009\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                .with(signedIn("admin", "password"))).andExpect(status().isOk());
        String body = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":50000,\"premium\":50}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + ref + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        jdbc.update("update ins_policy set cover_start_date = ? where record_ref = ?", LocalDate.now().minusDays(60), ref);
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
