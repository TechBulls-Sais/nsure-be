package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.testsupport.GroupSchemeSetup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract set-up and activation (NS-GL01-003/004, AT-GL01-01): no scheme is in force without accepted terms an
 * underwriter approved, an approved group wording version, the inception conditions and free-cover rules within the
 * accepted terms; the set-up terms are recorded on the scheme.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-setup;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SchemeSetupApiTest {

    private static final String CONDITIONS = "\"conditions\":[\"SIGNED_PROPOSAL\",\"SIGNED_CENSUS\",\"FIRST_PREMIUM_OR_DEFERRAL\"]";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aDirectlyCreatedSchemeIsInSetUpUntilActivatedOnAcceptedTerms() throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin",
                        "{\"name\":\"Setup Works " + System.nanoTime() + "\",\"sector\":\"Retail\","
                                + "\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme.status").value("SETUP"))
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin",
                        "{\"code\":\"STAFF\",\"name\":\"Staff\",\"benefitBasis\":\"FIXED\",\"fixedCover\":600000,"
                                + "\"freeCoverLimit\":750000,\"ratePerMille\":1,\"minEntryAge\":18,\"maxEntryAge\":64}"))
                .andExpect(status().isOk());
        // Not in force: no census is taken.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        "{\"externalBatchId\":\"SU-1\",\"csv\":\"memberKey,firstName,lastName,dateOfBirth,category,salary,"
                                + "action,effectiveDate\\nA1,Ann,One,1985-01-01,STAFF,,JOIN,2026-01-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("in set-up")));

        String quote = acceptedQuote("Setup Sponsor " + System.nanoTime(), 500000);
        // Everything missing is reported together: wording, conditions, and a category above the accepted free cover.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/activation"), "admin", "{\"quoteRef\":\"" + quote + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("approved scheme wording")))
                .andExpect(jsonPath("$.message").value(containsString("Signed proposal")))
                .andExpect(jsonPath("$.message").value(containsString("above the accepted terms")));
        mockMvc.perform(get("/group-life/quotes/" + quote + "/activation-check").param("schemeRef", scheme)
                        .param("wordingRef", "WRD-G001").param("conditions", "SIGNED_PROPOSAL", "SIGNED_CENSUS",
                                "FIRST_PREMIUM_OR_DEFERRAL").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.problems[0]").value(containsString("STAFF free cover")))
                .andExpect(jsonPath("$.wordings[*].ref").value(hasItem("WRD-G001")));
        mockMvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/group/schemes/" + scheme + "/categories/STAFF"), "admin",
                        "{\"name\":\"Staff\",\"benefitBasis\":\"FIXED\",\"fixedCover\":600000,"
                                + "\"freeCoverLimit\":500000,\"ratePerMille\":1,\"minEntryAge\":18,\"maxEntryAge\":64}"))
                .andExpect(status().isOk());
        // A wording that is not group wording, or a broker that is not registered, is refused.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/activation"), "admin", "{\"quoteRef\":\"" + quote
                        + "\",\"wordingRef\":\"WRD-0001\"," + CONDITIONS + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("not a group scheme wording")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/activation"), "admin", "{\"quoteRef\":\"" + quote
                        + "\",\"wordingRef\":\"WRD-G001\",\"brokerRef\":\"INT-99999\"," + CONDITIONS + "}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("not a registered intermediary")));

        mockMvc.perform(json(post("/group/schemes/" + scheme + "/activation"), "admin", "{\"quoteRef\":\"" + quote
                        + "\",\"wordingRef\":\"WRD-G001\",\"registrationNo\":\"BW-REG-" + System.nanoTime() + "\","
                        + "\"payerName\":\"Setup Holdings (Pty) Ltd\",\"brokerRef\":\"INT-00001\",\"billingMethod\":\"DEBIT_ORDER\","
                        + "\"billingDueDay\":25,\"aawRule\":\"DEFER\",\"partialCoverAllowed\":false," + CONDITIONS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheme.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scheme.quoteRef").value(quote))
                .andExpect(jsonPath("$.scheme.setup.wordingRef").value("WRD-G001"))
                .andExpect(jsonPath("$.scheme.setup.wordingVersion").value(1))
                .andExpect(jsonPath("$.scheme.setup.payerName").value("Setup Holdings (Pty) Ltd"))
                .andExpect(jsonPath("$.scheme.setup.brokerRef").value("INT-00001"))
                .andExpect(jsonPath("$.scheme.setup.billingMethod").value("DEBIT_ORDER"))
                .andExpect(jsonPath("$.scheme.setup.billingDueDay").value(25))
                .andExpect(jsonPath("$.scheme.setup.aawRule").value("DEFER"))
                .andExpect(jsonPath("$.scheme.setup.partialCoverAllowed").value(false))
                .andExpect(jsonPath("$.scheme.setup.inceptionConditions").value(containsString("SIGNED_CENSUS")))
                .andExpect(jsonPath("$.scheme.setup.activatedBy").value("admin"))
                .andExpect(jsonPath("$.categories.length()").value(1));
        String sponsor = JsonPath.read(mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString(), "$.scheme.setup.sponsorRef");
        mockMvc.perform(get("/group-life/sponsors").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + sponsor + "')].status").value(hasItem("CLIENT")));
        // The quote is used up, and an active scheme is not activated twice.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/activation"), "admin", "{\"quoteRef\":\"" + quote
                        + "\",\"wordingRef\":\"WRD-G001\"," + CONDITIONS + "}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aQuoteActivatesWithTheCategoriesOfItsDesignAndTheSponsorRecord() throws Exception {
        String sponsor = JsonPath.read(mockMvc.perform(json(post("/group-life/sponsors"), "admin",
                        "{\"legalName\":\"Design Mills " + System.nanoTime() + "\",\"registrationNo\":\"BW-D-"
                                + System.nanoTime() + "\",\"sector\":\"Milling\",\"contactEmail\":\"hr@designmills.example\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROSPECT"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/sponsors"), "admin", "{\"legalName\":\"Bad\",\"sector\":\"X\","
                        + "\"contactEmail\":\"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity());
        String quote = acceptedQuote("Design Quote " + System.nanoTime(), 400000);
        String body = "{\"inceptionDate\":\"2026-03-01\",\"wordingRef\":\"WRD-G001\",\"sponsorRef\":\"" + sponsor + "\","
                + CONDITIONS + ",\"categories\":[{\"code\":\"STAFF\",\"name\":\"Staff\",\"benefitBasis\":\"SALARY_MULTIPLE\","
                + "\"salaryMultiple\":2,\"freeCoverLimit\":400000,\"ratePerMille\":0.5,\"minEntryAge\":18,\"maxEntryAge\":60},"
                + "{\"code\":\"MGMT\",\"name\":\"Management\",\"benefitBasis\":\"SALARY_MULTIPLE\",\"salaryMultiple\":4,"
                + "\"freeCoverLimit\":400000,\"ratePerMille\":0.6,\"minEntryAge\":18,\"maxEntryAge\":60}]}";
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes/" + quote + "/activation"), "admin", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andReturn().getResponse().getContentAsString(), "$.schemeRef");
        mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.scheme.status").value("ACTIVE"))
                .andExpect(jsonPath("$.scheme.renewalDate").value("2027-03-01"))
                .andExpect(jsonPath("$.scheme.setup.sponsorRef").value(sponsor))
                .andExpect(jsonPath("$.scheme.setup.aawRule").value("REFER"))
                .andExpect(jsonPath("$.scheme.setup.partialCoverAllowed").value(true))
                .andExpect(jsonPath("$.categories[*].code").value(org.hamcrest.Matchers.contains("MGMT", "STAFF")));
        mockMvc.perform(get("/group-life/sponsors").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + sponsor + "')].status").value(hasItem("CLIENT")));
    }

    private String acceptedQuote(String sponsor, int freeCover) throws Exception {
        String quote = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes"), "admin", "{\"sponsorName\":\""
                        + sponsor + "\",\"sector\":\"Retail\",\"lives\":2,\"salaryRoll\":1000000,\"benefits\":[\"GLA\"],"
                        + "\"ratingBasis\":\"SUM_ASSURED\",\"salaryMultiple\":1,\"ratePerMille\":1,\"freeCoverLimit\":"
                        + freeCover + ",\"census\":\"" + GroupSchemeSetup.QUOTE_CENSUS + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        for (String[] step : new String[][] {{"admin", "SUBMIT"}, {"secadmin", "APPROVE"}, {"admin", "ACCEPT"}}) {
            mockMvc.perform(json(post("/group-life/quotes/" + quote + "/progress"), step[0], "{\"action\":\"" + step[1] + "\"}"))
                    .andExpect(status().isOk());
        }
        return quote;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
