package com.nsure.grouplife;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Group workspace views: the master-policy timeline and accepted terms (GL01), the resolved rule set (GL02), the
 * member underwriting queue (GL03), the arrears queue, policy-year references and renewal comparison (GL05), and the
 * lender's bordereau history (GL06).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-grouplife;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupWorkspaceApiTest {

    private static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theMasterPolicyTimelineAndResolvedRulesShowTheSchemeFromQuoteToRenewal() throws Exception {
        String quote = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes"), "admin",
                        "{\"sponsorName\":\"Timeline Mills " + System.nanoTime() + "\",\"sector\":\"Milling\",\"lives\":5,"
                                + "\"salaryRoll\":500000,\"benefits\":[\"GLA\",\"FAMILY\"],\"ratePerMille\":2,"
                                + "\"freeCoverLimit\":500000,\"census\":\"" + com.nsure.testsupport.GroupSchemeSetup.QUOTE_CENSUS + "\"}"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        for (String[] step : new String[][] {{"admin", "SUBMIT"}, {"secadmin", "APPROVE"}, {"admin", "ACCEPT"}}) {
            mockMvc.perform(json(post("/group-life/quotes/" + quote + "/progress"), step[0], "{\"action\":\"" + step[1] + "\"}"))
                    .andExpect(status().isOk());
        }
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes/" + quote + "/activation"), "admin",
                        "{\"inceptionDate\":\"" + LocalDate.now() + "\",\"wordingRef\":\"WRD-G001\",\"conditions\":[\"SIGNED_PROPOSAL\",\"SIGNED_CENSUS\",\"FIRST_PREMIUM_OR_DEFERRAL\"],"
                                + "\"categories\":[{\"code\":\"STAFF\",\"name\":\"Staff\",\"benefitBasis\":\"SALARY_MULTIPLE\","
                                + "\"salaryMultiple\":2,\"freeCoverLimit\":500000,\"ratePerMille\":2,\"minEntryAge\":18,"
                                + "\"maxEntryAge\":64}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.schemeRef");
        // The accepted terms the master policy came from are on the scheme.
        mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.scheme.quoteRef").value(quote))
                .andExpect(jsonPath("$.scheme.policyYear").value(1));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/resolved-rules").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.acceptedTerms").value(quote))
                .andExpect(jsonPath("$.categories[0].cover").value("2 x annual salary"))
                .andExpect(jsonPath("$.categories[0].source").value(containsString("Category terms")))
                .andExpect(jsonPath("$.benefits[?(@.benefitType == 'FAMILY')].productMaximum").value(40000))
                .andExpect(jsonPath("$.benefits[?(@.benefitType == 'FAMILY')].withinProductMaximum").value(true));

        // Renewal comparison: expiring and proposed rates side by side before anyone approves.
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/impact").param("eventType", "RENEWAL")
                        .param("rateChangePercent", "10").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.comparison[0].category").value("STAFF"))
                .andExpect(jsonPath("$.comparison[0].ratePerMille").value(2.0))
                .andExpect(jsonPath("$.comparison[0].proposedRatePerMille").value(2.2));
        String renewal = JsonPath.read(mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"RENEWAL\",\"rateChangePercent\":10,\"reason\":\"Claims experience\"}"))
                .andExpect(jsonPath("$.impactDetail.comparison[0].proposedRatePerMille").value(2.2))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/events/" + renewal + "/decision"), "secadmin", "{\"approve\":true,\"note\":\"Agreed\"}"))
                .andExpect(status().isOk());

        String timeline = mockMvc.perform(get("/group-life/schemes/" + scheme + "/timeline").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("QUOTE"))
                .andExpect(jsonPath("$[*].title").value(hasItem(quote + " v1 approved")))
                .andExpect(jsonPath("$[*].title").value(hasItem("Master policy activated")))
                .andExpect(jsonPath("$[*].title").value(hasItem("Renewal approved (" + renewal + ")")))
                .andReturn().getResponse().getContentAsString();
        List<String> kinds = JsonPath.read(timeline, "$[*].kind");
        assertThat(kinds.indexOf("ACTIVATION")).isGreaterThan(kinds.indexOf("QUOTE"));
        assertThat(kinds.lastIndexOf("RENEWAL")).isEqualTo(kinds.size() - 1);
    }

    @Test
    void theUnderwritingAndArrearsQueuesSpanSchemesAndBillsCarryThePolicyYear() throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin",
                        "{\"name\":\"Queue Works " + System.nanoTime() + "\",\"sector\":\"Retail\","
                                + "\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin",
                        "{\"code\":\"STAFF\",\"name\":\"Staff\",\"benefitBasis\":\"FIXED\",\"fixedCover\":800000,"
                                + "\"freeCoverLimit\":500000,\"ratePerMille\":1,\"minEntryAge\":18,\"maxEntryAge\":64}"))
                .andExpect(status().isOk());
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme);
        String batch = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        "{\"externalBatchId\":\"Q-1\",\"csv\":\"" + HEADER + "\\nQ1,Quinn,One,1985-01-01,STAFF,,JOIN,2026-01-01\"}"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/group/underwriting-queue").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.schemeRef == '" + scheme + "')].memberKey").value(hasItem("Q1")))
                .andExpect(jsonPath("$[?(@.schemeRef == '" + scheme + "')].pendingCover").value(hasItem(300000.0)))
                .andExpect(jsonPath("$[?(@.schemeRef == '" + scheme + "')].freeCoverLimit").value(hasItem(500000.0)))
                .andExpect(jsonPath("$[?(@.schemeRef == '" + scheme + "')].schemeCoverPending").value(hasItem(300000.0)))
                .andExpect(jsonPath("$[?(@.schemeRef == '" + scheme + "')].referredBecause")
                        .value(hasItem(containsString("free-cover limit"))));
        // January is long past and unpaid: it is in arrears, with its policy year.
        String invoice = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin",
                        "{\"period\":\"2026-01\"}"))
                .andExpect(jsonPath("$.policyYear").value(1))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(get("/group/arrears").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.invoiceRef == '" + invoice + "')].outstanding").value(hasItem(500.0)))
                .andExpect(jsonPath("$[?(@.invoiceRef == '" + invoice + "')].policyYear").value(hasItem(1)))
                .andExpect(jsonPath("$[?(@.invoiceRef == '" + invoice + "')].daysOverdue")
                        .value(hasItem((int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 1, 31), LocalDate.now()))));
        mockMvc.perform(get("/group/arrears").with(signedIn("customer", "password"))).andExpect(status().isForbidden());
    }

    @Test
    void theLenderBordereauHistoryCountsEachFile() throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin",
                        "{\"name\":\"Bordereau Bank " + System.nanoTime() + "\",\"sector\":\"Banking\","
                                + "\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefits"), "admin",
                        "{\"benefitType\":\"CREDIT\",\"basis\":\"FIXED\",\"fixedAmount\":300000}"))
                .andExpect(status().isOk());
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme);
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin",
                        "{\"batchReference\":\"BH-1\",\"rows\":[{\"lenderLoanId\":\"H1\",\"borrowerName\":\"A\",\"loanAmount\":100000,\"termMonths\":12},"
                                + "{\"lenderLoanId\":\"H2\",\"borrowerName\":\"B\",\"loanAmount\":400000,\"termMonths\":12}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin",
                        "{\"batchReference\":\"BH-2\",\"rows\":[{\"lenderLoanId\":\"H3\",\"borrowerName\":\"C\",\"loanAmount\":50000,\"termMonths\":6}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/bordereaux").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].batchReference").value("BH-2"))
                .andExpect(jsonPath("$[1].loans").value(2))
                .andExpect(jsonPath("$[1].active").value(1))
                .andExpect(jsonPath("$[1].referred").value(1));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
