package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * IL01 new business as an installation runs it (NS-IL01-001/002/003/005/006, AT-IL01-03, IL01 workspace): no direct
 * issue, the portal purchase is an underwritten application with disclosures, and quotes name their product terms.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-new-business;DB_CLOSE_DELAY=-1",
        "nsure.new-business.direct-issue-enabled=false",
        "nsure.new-business.portal-disclosures-required=true"})
@AutoConfigureMockMvc
class NewBusinessApiTest {

    private static final String CLEAN = "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"},\"declarationAccepted\":true";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void staffCannotIssueWithoutAnUnderwrittenApplication() throws Exception {
        String customer = customer("Tebogo Direct", "tebogo.direct", "+267 72 900 001", 36);
        mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("issued from an application")));
        assertThat(jdbc.queryForObject("select count(*) from ins_policy where customer_ref = ?", Long.class, customer))
                .isZero();
    }

    @Test
    void aPortalPurchaseIsAnUnderwrittenApplicationAndAReferredOneWaitsForTheUnderwriter() throws Exception {
        String customer = customer("Lorato Portal", "lorato.portal", "+267 72 900 002", 36);
        // NS-IL01-003: the portal asks the product's questions; a purchase without them is refused.
        mockMvc.perform(get("/portfolio/shop/questions").param("product", "Family Funeral")
                        .with(signedIn("lorato.portal", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].code").value(hasItem("TOBACCO")));
        mockMvc.perform(json(post("/portfolio/shop"), "lorato.portal", "{\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("disclosure question")));

        // Inside every limit: the rules accept it and the policy opens, Pending Payment, linked to its application.
        mockMvc.perform(json(post("/portfolio/shop"), "lorato.portal", "{\"product\":\"Family Funeral\",\"tier\":\"Bronze\","
                        + CLEAN + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].status").value("Pending Payment"))
                .andExpect(jsonPath("$.applications").isEmpty());
        String app = jdbc.queryForObject("select application_ref from ins_policy where customer_ref = ?", String.class,
                customer);
        assertThat(app).startsWith("APP-");
        assertThat(jdbc.queryForObject("select status from ins_application where record_ref = ?", String.class, app))
                .isEqualTo("Issued");
        assertThat(jdbc.queryForObject("select channel from ins_application where record_ref = ?", String.class, app))
                .isEqualTo("PORTAL");

        // A disclosed smoker is referred: no policy, an application the customer can follow.
        String referred = JsonPath.read(mockMvc.perform(json(post("/portfolio/shop"), "lorato.portal",
                                "{\"product\":\"Term Life\",\"tier\":\"Bronze\",\"disclosures\":{\"TOBACCO\":\"YES\","
                                        + "\"TREATMENT\":\"NO\"},\"declarationAccepted\":true}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.applications[0].status").value("Under review"))
                        .andExpect(jsonPath("$.requests.length()").value(1))
                        .andReturn().getResponse().getContentAsString(), "$.applications[0].id");
        // Asking again does not open a second application.
        mockMvc.perform(json(post("/portfolio/shop"), "lorato.portal", "{\"product\":\"Term Life\",\"tier\":\"Bronze\","
                        + "\"disclosures\":{\"TOBACCO\":\"YES\",\"TREATMENT\":\"NO\"},\"declarationAccepted\":true}"))
                .andExpect(jsonPath("$.applications.length()").value(1));
        String caseRef = JsonPath.read(mockMvc.perform(get("/underwriting/applications/" + referred)
                        .with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.status").value("REFERRED"))
                .andReturn().getResponse().getContentAsString(), "$.underwritingCase.ref");
        mockMvc.perform(json(post("/underwriting/cases/" + caseRef + "/decision"), "underwriter",
                        "{\"decision\":\"ACCEPT_WITH_TERMS\",\"loadingPercent\":25,\"reason\":\"Smoker loading for this life.\"}"))
                .andExpect(status().isOk());
        // The customer sees the offer's terms and accepts it themselves.
        mockMvc.perform(get("/portfolio/shop").with(signedIn("lorato.portal", "password1")))
                .andExpect(jsonPath("$.applications[0].status").value("Offered"))
                .andExpect(jsonPath("$.offers['" + referred + "'].premium").value(287.5))
                .andExpect(jsonPath("$.offers['" + referred + "'].loadingPercent").value(25.0));
        mockMvc.perform(json(post("/portfolio/shop/applications/" + referred + "/offer"), "lorato.portal", "{\"accept\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applications[0].status").value("Ready to issue"));
        mockMvc.perform(get("/underwriting/cases/" + caseRef).with(signedIn("underwriter", "password")))
                .andExpect(jsonPath("$.underwritingCase.offerAcceptedBy").value("lorato.portal"));
        // Staff issue it from the application; the customer then pays the loaded premium in the portal.
        mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"applicationRef\":\"" + referred + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"))
                .andExpect(jsonPath("$.premium").value(287.5));
        mockMvc.perform(get("/portfolio/shop").with(signedIn("lorato.portal", "password1")))
                .andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.applications").isEmpty());
        // Another customer cannot answer someone else's offer.
        customer("Other Portal", "other.portal", "+267 72 900 009", 30);
        mockMvc.perform(json(post("/portfolio/shop/applications/" + referred + "/offer"), "other.portal", "{\"accept\":true}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aQuoteNamesItsProductTermsAndBenefitsAndAFlatPlanKeepsItsEntryAges() throws Exception {
        String customer = customer("Kefilwe Quote", "kefilwe.quote", "+267 72 900 003", 36);
        mockMvc.perform(json(post("/portfolio/quotes"), "admin", "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace.productVersion.label").isNotEmpty())
                .andExpect(jsonPath("$.trace.productVersion.wordingRef").exists())
                .andExpect(jsonPath("$.trace.benefits[0]").isNotEmpty());
        // NS-IL01-001: a flat-priced plan is not quoted outside the product's entry ages.
        String older = customer("Mma Older", "mma.older", "+267 72 900 004", 72);
        mockMvc.perform(json(post("/portfolio/quotes"), "admin", "{\"customerRef\":\"" + older
                        + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("entry ages")));
    }

    @Test
    void anApplicationCapturesPartiesBeneficiariesAndAValidChannel() throws Exception {
        String customer = customer("Gofaone Capture", "gofaone.capture", "+267 72 900 005", 40);
        String payer = customer("Kgosi Payer", "kgosi.payer", "+267 72 900 006", 45);
        String draft = JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\""
                                + customer + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\",\"status\":\"Draft\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        // NS-IL01-003: an ineligible relationship, an unknown intermediary and a product outside the channel are named.
        mockMvc.perform(json(put("/portfolio/applications/" + draft + "/details"), "admin", "{\"payerRef\":\"" + payer
                        + "\",\"payerRelationship\":\"Neighbour\",\"channel\":\"AGENT\",\"intermediaryCode\":\"INT-99999\","
                        + "\"occupation\":\"Teacher\"," + CLEAN + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payer").value("Kgosi Payer (Neighbour)"))
                .andExpect(jsonPath("$.payerRef").value(payer))
                .andExpect(jsonPath("$.submissionProblems[?(@ =~ /.*not an eligible relationship.*/)]").exists())
                .andExpect(jsonPath("$.submissionProblems[?(@ =~ /.*INT-99999 is not an appointed.*/)]").exists());
        // NS-IL01-002: beneficiaries are nominated on the application and become the policy's at issue.
        mockMvc.perform(json(put("/portfolio/applications/" + draft + "/details"), "admin", "{\"payerRef\":\"" + payer
                        + "\",\"payerRelationship\":\"Employer\",\"channel\":\"AGENT\",\"intermediaryCode\":\"INT-00001\","
                        + "\"beneficiaries\":[{\"fullName\":\"Naledi Capture\",\"relationship\":\"Child\",\"primary\":true},"
                        + "{\"fullName\":\"Pako Capture\",\"relationship\":\"Spouse\"}]}"))
                .andExpect(jsonPath("$.submissionProblems").isEmpty())
                .andExpect(jsonPath("$.beneficiaries.length()").value(2))
                .andExpect(jsonPath("$.occupation").value("Teacher"));
        mockMvc.perform(post("/portfolio/applications/" + draft + "/submit").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Ready to issue"));
        mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"applicationRef\":\"" + draft + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.beneficiaries.length()").value(2))
                .andExpect(jsonPath("$.beneficiaries[0].fullName").value("Naledi Capture"))
                .andExpect(jsonPath("$.beneficiaries[0].primary").value(true));

        // NS-IL01-001: Individual Credit is sold only through lenders, never as an application.
        String credit = JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\""
                                + customer + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\",\"status\":\"Draft\"}"))
                        .andReturn().getResponse().getContentAsString(), "$.id");
        jdbc.update("update ins_application set product = 'Individual Credit' where record_ref = ?", credit);
        mockMvc.perform(get("/portfolio/applications/" + credit + "/checklist").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.submissionProblems[?(@ =~ /.*not distributed through the DIRECT channel.*/)]").exists());
        mockMvc.perform(get("/portfolio/application-relationships").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$").value(hasItem("Employer")))
                .andExpect(jsonPath("$").value(hasItem("Spouse")));
    }

    @Test
    void theMissingEvidenceQueueListsOutstandingItemsAndAnAgentCannotGuessAnApplication() throws Exception {
        String customer = customer("Masego Evidence", "masego.evidence", "+267 72 900 007", 38);
        String app = JsonPath.read(mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\""
                                + customer + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\",\"declarationAccepted\":true,"
                                + "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"YES\"}}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        // The underwriting case asked for a restricted doctor's report: listed with its due date.
        mockMvc.perform(get("/portfolio/applications/evidence-queue").with(signedIn("underwriter", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationRef == '" + app + "')].item").value(hasItem("Attending doctor's report")))
                .andExpect(jsonPath("$[?(@.applicationRef == '" + app + "')].dueDate")
                        .value(hasItem(LocalDate.now().plusDays(14).toString())));
        // Someone who is not an underwriter sees that restricted evidence is outstanding, not what it is.
        mockMvc.perform(get("/portfolio/applications/evidence-queue").with(signedIn("support", "password")))
                .andExpect(jsonPath("$[?(@.applicationRef == '" + app + "')].item").value(hasItem("Restricted medical evidence")));
        // AT-IL01-03: a partner login cannot read an application by guessing its reference.
        mockMvc.perform(get("/portfolio/applications/" + app + "/checklist").with(signedIn("agent", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/underwriting/applications/" + app).with(signedIn("agent", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portfolio/applications/evidence-queue").with(signedIn("agent", "password")))
                .andExpect(status().isForbidden());
    }

    private String customer(String name, String username, String phone, int age) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"" + phone + "\",\"username\":\""
                                + username + "\",\"password\":\"password1\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(age)
                                .minusDays(10) + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password".equals(user) ? "password" : user.contains(".") ? "password1" : "password"))
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
