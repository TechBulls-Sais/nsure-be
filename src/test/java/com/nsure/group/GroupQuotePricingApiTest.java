package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Group quotation pricing (NS-GL01-001/002, section 08 pricing): the census is validated before rating, the premium
 * follows the stated basis with rates from approved rate table versions (or a negotiated rate recorded as such), and
 * every version keeps the terms it was offered on.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-pricing;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupQuotePricingApiTest {

    // Ages 36 and 51 in 2026-27: the 30-39 and 50-59 bands of the demo tables.
    private static final String CENSUS = "memberKey,dateOfBirth,category,salary\\nA1,1990-01-01,STAFF,200000"
            + "\\nA2,1975-06-01,MGMT,300000";
    private static final String DESIGN = "\"categories\":[{\"code\":\"STAFF\",\"name\":\"Staff\",\"benefitBasis\":\"SALARY_MULTIPLE\","
            + "\"salaryMultiple\":2},{\"code\":\"MGMT\",\"name\":\"Management\",\"benefitBasis\":\"FIXED\",\"fixedCover\":1000000}]";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aCensusIsValidatedBeforeRatingAndTheTableRateFollowsTheBasis() throws Exception {
        // Errors are kept per row; a census with errors only gives an indicative price that cannot be submitted.
        String bad = "memberKey,dateOfBirth,category,salary\\nB1,1990-01-01,STAFF,200000\\nB1,1990-01-01,STAFF,1000"
                + "\\nB3,1990/01/01,STAFF,1000\\nB4,1940-01-01,STAFF,1000\\nB5,1990-01-01,STAFF,\\nB6,1990-01-01,NOPE,1000";
        String created = mockMvc.perform(json(post("/group-life/quotes"), "admin", "{\"sponsorName\":\"Pricing Works "
                        + System.nanoTime() + "\",\"sector\":\"Retail\",\"lives\":2,\"salaryRoll\":500000,\"benefits\":[\"GLA\"],"
                        + "\"ratingBasis\":\"SUM_ASSURED\",\"freeCoverLimit\":500000," + DESIGN + ",\"census\":\"" + bad + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateSource").value("TABLE"))
                .andExpect(jsonPath("$.pricingStatus").value("INDICATIVE"))
                .andExpect(jsonPath("$.census.status").value("HAS_ERRORS"))
                .andExpect(jsonPath("$.census.validRows").value(1))
                .andExpect(jsonPath("$.census.invalidRows").value(5))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(created, "$.ref");
        mockMvc.perform(get("/group-life/quotes/" + ref + "/census/1").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.rows[1].error").value(containsString("duplicate memberKey B1")))
                .andExpect(jsonPath("$.rows[2].error").value(containsString("dateOfBirth must be a date")))
                .andExpect(jsonPath("$.rows[3].error").value(containsString("outside the 16-70 ages")))
                .andExpect(jsonPath("$.rows[4].error").value(containsString("salary is required")))
                .andExpect(jsonPath("$.rows[5].error").value(containsString("unknown category NOPE")));
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "admin", "{\"action\":\"SUBMIT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("priced indicatively")));

        // The corrected census is version 2; the draft is rated on it from the approved demo GLA table.
        // STAFF: 2 x 200,000 at 1.6 = 640; MGMT: 1,000,000 at 6.1 = 6,100; risk premium 6,740, +20% loadings.
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/census"), "admin", "{\"csv\":\"" + CENSUS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricingStatus").value("RATED"))
                .andExpect(jsonPath("$.census.version").value(2))
                .andExpect(jsonPath("$.census.lives").value(2))
                .andExpect(jsonPath("$.census.averageAge").isNumber())
                .andExpect(jsonPath("$.technicalPremium").value(6740.0))
                .andExpect(jsonPath("$.annualPremium").value(8088.0))
                .andExpect(jsonPath("$.rateTables").value("DEMO-GLA-SA v1"))
                .andExpect(jsonPath("$.pricing.categories[?(@.category == 'MGMT')].annualPremium").value(hasItem(7320.0)));
        mockMvc.perform(get("/group-life/quotes").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "')].lives").value(hasItem(2)))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "')].salaryRoll").value(hasItem(500000.0)))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "')].ratePerMille").value(hasItem(5.7771)));

        // A revision keeps the earlier offer as it was and carries the census over; a negotiated discount applies.
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/revision"), "admin",
                        "{\"adjustmentPercent\":-10,\"validityDays\":10}"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.census.version").value(2))
                .andExpect(jsonPath("$.annualPremium").value(7279.2))
                .andExpect(jsonPath("$.validUntil").value(LocalDate.now().plusDays(10).toString()));
        mockMvc.perform(get("/group-life/quotes").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "' && @.version == 1)].annualPremium").value(hasItem(8088.0)))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "' && @.version == 1)].status").value(hasItem("SUPERSEDED")));
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "admin", "{\"action\":\"SUBMIT\"}"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        mockMvc.perform(get("/group-life/quotes/" + ref + "/history").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[*].action").value(hasItem("Scheme quote prepared")))
                .andExpect(jsonPath("$[*].action").value(hasItem("Scheme quote revised")))
                .andExpect(jsonPath("$[*].action").value(hasItem("Scheme quote submitted")));
    }

    @Test
    void perMemberLoanBalanceAndNegotiatedBasesPriceDifferently() throws Exception {
        // PER_MEMBER: a rate per member a year by age (420 at 36, 1,650 at 51), +20%.
        mockMvc.perform(json(post("/group-life/quotes"), "admin", quote("PER_MEMBER", "\"census\":\"" + CENSUS + "\"")))
                .andExpect(jsonPath("$.rateTables").value("DEMO-GLA-PM v1"))
                .andExpect(jsonPath("$.technicalPremium").value(2070.0))
                .andExpect(jsonPath("$.annualPremium").value(2484.0))
                .andExpect(jsonPath("$.ratePerMille").value(1242.0));
        // SALARY: rate per 1,000 of salary (3.2 at 36, 12.2 at 51) on 200,000 and 300,000.
        mockMvc.perform(json(post("/group-life/quotes"), "admin", quote("SALARY", "\"census\":\"" + CENSUS + "\"")))
                .andExpect(jsonPath("$.rateTables").value("DEMO-GLA-SAL v1"))
                .andExpect(jsonPath("$.technicalPremium").value(4300.0));
        // LOAN_BALANCE needs the balances in the census.
        mockMvc.perform(json(post("/group-life/quotes"), "admin", quote("LOAN_BALANCE", "\"census\":\"" + CENSUS + "\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("loanBalance column")));
        // A negotiated rate is recorded as such: no table, no loadings.
        mockMvc.perform(json(post("/group-life/quotes"), "admin", quote("SALARY", "\"ratePerMille\":5,\"census\":\"" + CENSUS + "\"")))
                .andExpect(jsonPath("$.rateSource").value("NEGOTIATED"))
                .andExpect(jsonPath("$.rateTables").doesNotExist())
                .andExpect(jsonPath("$.annualPremium").value(2500.0));
    }

    @Test
    void aNewRateTableVersionNeedsASecondPersonAndOnlyNewQuotesUseIt() throws Exception {
        String before = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes"), "admin",
                        quote("SUM_ASSURED", "\"benefits\":[\"LUMP_SUM_DISABILITY\"],\"census\":\"" + CENSUS + "\"")))
                .andExpect(jsonPath("$.rateTables").value("DEMO-LSD-SA v1"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/rate-tables"), "admin", "{\"tableCode\":\"DEMO-LSD-SA\",\"label\":\"LSD rates 2027\","
                        + "\"benefitType\":\"LUMP_SUM_DISABILITY\",\"ratingBasis\":\"SUM_ASSURED\",\"bands\":[{\"ageFrom\":16,\"ageTo\":45,"
                        + "\"rate\":1},{\"ageFrom\":40,\"ageTo\":70,\"rate\":2}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("overlap")));
        mockMvc.perform(json(post("/group-life/rate-tables"), "admin", "{\"tableCode\":\"DEMO-LSD-SA\",\"label\":\"LSD rates 2027\","
                        + "\"benefitType\":\"LUMP_SUM_DISABILITY\",\"ratingBasis\":\"SUM_ASSURED\",\"bands\":[{\"ageFrom\":16,\"ageTo\":45,"
                        + "\"rate\":1},{\"ageFrom\":46,\"ageTo\":70,\"rate\":2}]}"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(post("/group-life/rate-tables/DEMO-LSD-SA/versions/2/approval").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/group-life/rate-tables/DEMO-LSD-SA/versions/2/approval").with(signedIn("customer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/group-life/rate-tables/DEMO-LSD-SA/versions/2/approval").with(signedIn("secadmin", "password")))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/group-life/rate-tables").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.tableCode == 'DEMO-LSD-SA' && @.version == 1)].status").value(hasItem("SUPERSEDED")));
        // 400,000 at 1 and 1,000,000 at 2: 2,400 risk premium on the new version.
        mockMvc.perform(json(post("/group-life/quotes"), "admin",
                        quote("SUM_ASSURED", "\"benefits\":[\"LUMP_SUM_DISABILITY\"],\"census\":\"" + CENSUS + "\"")))
                .andExpect(jsonPath("$.rateTables").value("DEMO-LSD-SA v2"))
                .andExpect(jsonPath("$.technicalPremium").value(2400.0));
        mockMvc.perform(get("/group-life/quotes").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + before + "')].rateTables").value(hasItem("DEMO-LSD-SA v1")));
    }

    private static String quote(String basis, String extra) {
        String benefits = extra.contains("\"benefits\"") ? "" : "\"benefits\":[\"GLA\"],";
        return "{\"sponsorName\":\"Basis " + basis + " " + System.nanoTime() + "\",\"sector\":\"Retail\"," + benefits
                + "\"ratingBasis\":\"" + basis + "\",\"freeCoverLimit\":500000," + DESIGN + "," + extra + "}";
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
