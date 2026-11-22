package com.nsure.rating;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NS-SH03-001 / NS-SH03-003: a quote states and checks its inputs — effective date, currency, rate and product
 * version — and refuses an unsupported or mismatched one instead of guessing; with manual premiums switched off, a
 * record without a plan is refused for a product sold through plans.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:nsure-rating-inputs;DB_CLOSE_DELAY=-1",
        "nsure.rating.manual-premium-allowed=false"})
@AutoConfigureMockMvc
class RatingInputsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aQuoteStatesItsInputsAndRefusesUnsupportedOrMismatchedOnes() throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Rati Inputs\",\"email\":\"ratiinputs@example.com\",\"phone\":\"+267 71 000 003\","
                                + "\"dateOfBirth\":\"" + LocalDate.now().minusYears(32) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String base = "{\"customerRef\":\"" + customer + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"";
        mockMvc.perform(json(post("/portfolio/quote"), "admin", base + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateTableVersion").value("RT-00001-v1"))
                .andExpect(jsonPath("$.effectiveDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.currency").value("BWP"))
                .andExpect(jsonPath("$.productVersion").isNumber())
                .andExpect(jsonPath("$.ageBasis").value("AGE_LAST_BIRTHDAY"));
        mockMvc.perform(json(post("/portfolio/quote"), "admin", base + ",\"currency\":\"USD\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("USD is not supported")));
        mockMvc.perform(json(post("/portfolio/quote"), "admin", base + ",\"effectiveDate\":\"" + LocalDate.now().minusDays(1) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/quote"), "admin", base + ",\"effectiveDate\":\"next week\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("effectiveDate"));
        mockMvc.perform(json(post("/portfolio/quote"), "admin", base + ",\"rateTableVersion\":\"RT-00001-v0\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("not the version in force")));
        mockMvc.perform(json(post("/portfolio/quote"), "admin", base + ",\"rateTableVersion\":\"RT-00001-v1\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/quote"), "admin", base + ",\"productVersion\":999}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("not the product version in force")));
        // A plan is required: no fallback plan is guessed.
        mockMvc.perform(json(post("/portfolio/quote"), "admin",
                        "{\"customerRef\":\"" + customer + "\",\"product\":\"Family Funeral\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("tier is required")));
        // NS-SH03-003: with manual premiums off, a policy without a plan is refused (no uncalculated premium).
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":50000,\"premium\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("choose a plan")));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
