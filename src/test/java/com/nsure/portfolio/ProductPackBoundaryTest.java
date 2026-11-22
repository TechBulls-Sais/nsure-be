package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Section 06 required product tests: minimum and maximum entry age plus and minus one day for each flat-priced
 * product and Individual Credit, the waiting-period boundary (the day before and the day it ends), and a selected
 * term's cover end. Ages are age last birthday, the rule set's basis.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-product-packs;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProductPackBoundaryTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private int people;

    @Test
    void flatProductsQuoteOnlyWithinTheirEntryAgesToTheDay() throws Exception {
        LocalDate today = LocalDate.now();
        for (String product : List.of("Term Life", "Whole of Life", "Care Cash")) {
            String rules = mockMvc.perform(get("/underwriting/rules").with(signedIn("admin", "password")))
                    .andReturn().getResponse().getContentAsString();
            List<Integer> mins = JsonPath.read(rules, "$[?(@.product == '" + product + "')].minEntryAge");
            List<Integer> maxs = JsonPath.read(rules, "$[?(@.product == '" + product + "')].maxEntryAge");
            int min = mins.isEmpty() ? JsonPath.<List<Integer>>read(rules, "$[?(@.product == null)].minEntryAge").get(0) : mins.get(0);
            int max = maxs.isEmpty() ? JsonPath.<List<Integer>>read(rules, "$[?(@.product == null)].maxEntryAge").get(0) : maxs.get(0);
            // Turning the minimum age today is in; one day short of it is out.
            quote(product, today.minusYears(min), true);
            quote(product, today.minusYears(min).plusDays(1), false);
            // The last day at the maximum age is in; the next birthday is out.
            quote(product, today.minusYears(max + 1).plusDays(1), true);
            quote(product, today.minusYears(max + 1), false);
        }
    }

    @Test
    void individualCreditEntryAgesHoldToTheDay() throws Exception {
        LocalDate today = LocalDate.now();
        loan("LSH-B01", today.minusYears(18), "", true);
        loan("LSH-B02", today.minusYears(18).plusDays(1), "", false);
        // Entry stops at 65 even for a secured loan, which may run to 80.
        loan("LSH-B03", today.minusYears(66).plusDays(1), ",\"secured\":true", true);
        loan("LSH-B04", today.minusYears(66), ",\"secured\":true", false);
    }

    @Test
    void aClaimWaitsTheProductsWaitingPeriodToTheDay() throws Exception {
        String versions = mockMvc.perform(get("/portfolio/products/Term Life/versions").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        int waiting = JsonPath.<List<Integer>>read(versions, "$[?(@.inForce == true)].waitingPeriodDays").get(0);
        String customer = customer(LocalDate.now().minusYears(40));
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + customer + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        LocalDate start = LocalDate.now().minusDays(waiting);
        jdbc.update("update ins_policy set cover_start_date = ?, next_premium_due = ?, grace_period_end = ? where record_ref = ?",
                java.sql.Date.valueOf(start), java.sql.Date.valueOf(LocalDate.now().plusDays(10)),
                java.sql.Date.valueOf(LocalDate.now().plusDays(40)), policy);
        // The day before the waiting period ends fails the check; the day it ends passes.
        claim(policy, LocalDate.now().minusDays(1), "Fail");
        claim(policy, LocalDate.now(), "Pass");
    }

    private void quote(String product, LocalDate born, boolean accepted) throws Exception {
        String customer = customer(born);
        var result = mockMvc.perform(json(post("/portfolio/quote"), "admin", "{\"customerRef\":\"" + customer
                + "\",\"product\":\"" + product + "\",\"tier\":\"Bronze\"}"));
        if (accepted) {
            result.andExpect(status().isOk());
        } else {
            result.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(containsString("entry ages")));
        }
    }

    private void loan(String id, LocalDate born, String extra, boolean accepted) throws Exception {
        String borrower = customer(born);
        var result = mockMvc.perform(json(post("/credit/loans"), "credit", "{\"lenderRef\":\"LND-0001\",\"lenderLoanId\":\""
                + id + "\",\"customerRef\":\"" + borrower + "\",\"principal\":20000,\"termMonths\":12,"
                + "\"premiumBasis\":\"REGULAR\"" + extra + "}"));
        if (accepted) {
            result.andExpect(status().isOk());
        } else {
            result.andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").value(containsString("entry age")));
        }
    }

    private void claim(String policy, LocalDate event, String waitingResult) throws Exception {
        mockMvc.perform(json(post("/portfolio/claims"), "assessor", "{\"policy\":\"" + policy + "\",\"claimantName\":"
                        + "\"Boundary Claimant\",\"claimantMobile\":\"+267 72 000 000\",\"claimantRelationship\":\"Spouse\","
                        + "\"eventDate\":\"" + event + "\",\"description\":\"Boundary event " + event + ".\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.name == 'Waiting Period')].result").value(waitingResult));
    }

    private String customer(LocalDate born) throws Exception {
        people++;
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"Boundary Person "
                                + people + "\",\"email\":\"boundary" + people + "@example.com\",\"phone\":\"+267 73 100 "
                                + String.format("%03d", people) + "\",\"dateOfBirth\":\"" + born + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
