package com.nsure.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** Individual application capture (NS-IL01-002/003, AT-IL01-02). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-application-capture;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApplicationCaptureApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aDraftMayBeIncompleteButSubmissionNamesEveryMissingItem() throws Exception {
        String customer = customer("Boitumelo Draft", "+267 71 700 001");
        String app = JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"status\":\"Draft\","
                                + "\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("Draft"))
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/portfolio/applications/" + app + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.disclosures[?(@.code == 'TOBACCO')].mandatory").value(true))
                .andExpect(jsonPath("$.submissionProblems.length()").value(3));

        mockMvc.perform(post("/portfolio/applications/" + app + "/submit").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("tobacco"),
                        org.hamcrest.Matchers.containsString("declaration has not been accepted"))));

        // A third-party payer by debit order, sold by a broker: each needs its own details.
        mockMvc.perform(json(put("/portfolio/applications/" + app + "/details"), "admin", "{\"payerName\":\"Kabo Draft\","
                        + "\"mandateMethod\":\"DEBIT_ORDER\",\"mandateInstitution\":\"First National Bank\",\"channel\":\"BROKER\","
                        + "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"},\"declarationAccepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionProblems.length()").value(3));
        mockMvc.perform(post("/portfolio/applications/" + app + "/submit").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("how the payer Kabo Draft is related"),
                        org.hamcrest.Matchers.containsString("debit order needs"),
                        org.hamcrest.Matchers.containsString("intermediary's code"))));
        mockMvc.perform(json(put("/portfolio/applications/" + app + "/details"), "admin", "{\"payerName\":\"Kabo Draft\","
                        + "\"payerRelationship\":\"Spouse\",\"mandateMethod\":\"DEBIT_ORDER\",\"mandateInstitution\":\"First National Bank\","
                        + "\"mandateAccount\":\"62001234\",\"mandateDebitDay\":25,\"channel\":\"BROKER\",\"intermediaryCode\":\"INT-00001\"}"))
                .andExpect(jsonPath("$.submissionProblems").isEmpty())
                .andExpect(jsonPath("$.mandateAccount").value("•••1234"))
                .andExpect(jsonPath("$.payer").value("Kabo Draft (Spouse)"));

        mockMvc.perform(post("/portfolio/applications/" + app + "/submit").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                // NS-IL01-005: inside every underwriting limit, the rules accept it and it is ready to issue.
                .andExpect(jsonPath("$.status").value("Ready to issue"))
                .andExpect(jsonPath("$.submittedBy").value("admin"));
        mockMvc.perform(json(put("/portfolio/applications/" + app + "/details"), "admin", "{\"disclosures\":{\"TOBACCO\":\"YES\"}}"))
                .andExpect(status().isUnprocessableEntity());
        Long submitted = tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType ="
                + " 'ApplicationSubmitted' and e.aggregateRef = :ref", Long.class).setParameter("ref", app).getSingleResult());
        assertThat(submitted).isEqualTo(1L);
    }

    @Test
    void aSavedDraftIsResumedAndItsQuoteIsAcceptedOnlyWhenItIsSubmitted() throws Exception {
        String customer = customer("Mpho Resume", "+267 71 700 004");
        String draft = mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"status\":\"Draft\","
                        + "\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"tier\":\"Silver\","
                        + "\"mandateMethod\":\"DEBIT_ORDER\",\"mandateInstitution\":\"First National Bank\","
                        + "\"mandateAccount\":\"62009876\",\"mandateDebitDay\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Draft"))
                .andExpect(jsonPath("$.customerRef").value(customer))
                .andReturn().getResponse().getContentAsString();
        String app = JsonPath.read(draft, "$.id");
        String quote = JsonPath.read(draft, "$.quoteRef");
        // A draft has a priced quote, but nobody has accepted it: the declaration is not made yet (A-16).
        mockMvc.perform(get("/portfolio/quotes/" + quote).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].status").value("QUOTED"))
                .andExpect(jsonPath("$[0].applicationRef").value(app));

        // Resumed later: answers and the declaration added, the payer changed; the bank details on record stay.
        mockMvc.perform(json(put("/portfolio/applications/" + app + "/details"), "admin", "{\"payerName\":\"Kabo Resume\","
                        + "\"payerRelationship\":\"Spouse\",\"mandateMethod\":\"DEBIT_ORDER\",\"mandateDebitDay\":7,"
                        + "\"channel\":\"DIRECT\",\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"},"
                        + "\"declarationAccepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionProblems").isEmpty())
                .andExpect(jsonPath("$.mandateAccount").value("•••9876"))
                .andExpect(jsonPath("$.mandateDebitDay").value(7))
                .andExpect(jsonPath("$.payerName").value("Kabo Resume"))
                .andExpect(jsonPath("$.payerRelationship").value("Spouse"));
        mockMvc.perform(post("/portfolio/applications/" + app + "/submit").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Ready to issue"));
        mockMvc.perform(get("/portfolio/quotes/" + quote).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[0].acceptedBy").value("admin"));
        // IL01 workspace: the application's own timeline, not a sample one.
        mockMvc.perform(get("/portfolio/history/application/" + app).with(signedIn("support", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("Saved as draft"))
                .andExpect(jsonPath("$[0].newState").value("Draft"))
                .andExpect(jsonPath("$[1].action").value("Details updated"))
                .andExpect(jsonPath("$[2].action").value("Submitted"))
                .andExpect(jsonPath("$[2].previousState").value("Draft"))
                .andExpect(jsonPath("$[2].newState").value("Ready to issue"));
    }

    @Test
    void anApplicationSubmittedBeforeCaptureIsNotAskedToFixItsSubmission() throws Exception {
        String customer = customer("Naledi Historic", "+267 71 700 003");
        String app = JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\"" + customer
                                + "\",\"product\":\"Term Life\",\"tier\":\"Silver\",\"declarationAccepted\":true,"
                                + "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"}}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");
        // As a record carried over from before capture: submitted, with no declaration or answers behind it.
        tx.executeWithoutResult(s -> {
            em.createQuery("update InsuranceApplication a set a.declaredAt = null, a.declaredBy = null,"
                    + " a.submittedAt = null, a.submittedBy = null where a.ref = :ref").setParameter("ref", app).executeUpdate();
            em.createQuery("delete from ApplicationAnswer a where a.applicationRef = :ref").setParameter("ref", app).executeUpdate();
        });
        mockMvc.perform(get("/portfolio/applications/" + app + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.preCapture").value(true))
                .andExpect(jsonPath("$.submissionProblems").isEmpty());
    }

    @Test
    void outstandingMandatoryEvidenceHoldsIssueWithASpecificError() throws Exception {
        jdbc.update("insert into app_evidence_requirement (product, document_type, mandatory, min_sum_assured, satisfied_by,"
                + " display_order) values ('Family Funeral', 'Signed debit order form', 1, null, null, 9)");
        String customer = customer("Tebogo Evidence", "+267 71 700 002");
        String app = JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\"" + customer
                                + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\",\"declarationAccepted\":true,"
                                + "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"}}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("Evidence needed"))
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/portfolio/applications/" + app + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.evidence[?(@.documentType == 'Identity verification')].received").value(true))
                .andExpect(jsonPath("$.missingEvidence[0]").value("Signed debit order form"));

        String issue = "{\"applicationRef\":\"" + app + "\",\"customerRef\":\"" + customer
                + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}";
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "cannot be issued until this evidence is received: Signed debit order form")));
        mockMvc.perform(json(put("/portfolio/applications/" + app), "admin", "{\"status\":\"Ready to issue\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/applications/" + app + "/evidence"), "admin",
                        "{\"documentType\":\"Birth certificate\",\"note\":\"Not asked for\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/applications/" + app + "/evidence"), "admin",
                        "{\"documentType\":\"Signed debit order form\",\"note\":\"Original form checked and filed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Ready to issue"))
                .andExpect(jsonPath("$.missingEvidence").isEmpty());
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"));
    }

    private String customer(String name, String phone) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\",\"phone\":\"" + phone
                                + "\",\"dateOfBirth\":\"" + java.time.LocalDate.now().minusYears(36) + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
