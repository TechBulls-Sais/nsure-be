package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-tiers;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CoverTierApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void productsListTheirTiersAndSeededPoliciesAreBackfilled() throws Exception {
        mockMvc.perform(get("/portfolio/products").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].tiers[*].name")
                        .value(org.hamcrest.Matchers.contains("Bronze", "Silver", "Gold")))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].tiers[?(@.popular == true)].name").value("Silver"))
                .andExpect(jsonPath("$[?(@.name == 'Care Cash')].tiers[?(@.name == 'Gold')].cover").value(2000));

        // Cover matches the Silver tier exactly -> backfilled; 350,000 matches no tier -> left blank.
        mockMvc.perform(get("/portfolio/policies").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'POL-10841')].tier").value("Silver"))
                .andExpect(jsonPath("$[?(@.id == 'POL-10839')].tier").value(""));
    }

    @Test
    void tierSetsCoverAndPremiumAndShowsOnTheCustomer() throws Exception {
        mockMvc.perform(post("/portfolio/policies")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Amara Dube","product":"Term Life","tier":"Gold","amount":1,"premium":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("Gold"))
                .andExpect(jsonPath("$.amount").value(1000000))
                .andExpect(jsonPath("$.premium").value(780));

        mockMvc.perform(post("/portfolio/applications")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Naledi Kgosi","product":"Family Funeral","tier":"Bronze"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("Bronze"))
                .andExpect(jsonPath("$.amount").value(25000))
                // Family Funeral Bronze is rate-table priced: the main insured's age band, not the flat BWP 105.
                .andExpect(jsonPath("$.rateTableVersion").value("RT-00001-v1"))
                .andExpect(jsonPath("$.premium").value(org.hamcrest.Matchers.lessThan(105.0)));

        // Newest policy first, so the Gold Term Life just issued leads Amara's tiers.
        mockMvc.perform(get("/portfolio/customers").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'CUS-1001')].tiers[0].tier").value("Gold"))
                .andExpect(jsonPath("$[?(@.id == 'CUS-1001')].tiers[0].product").value("Term Life"));
    }

    @Test
    void rejectsATierThatTheProductDoesNotOffer() throws Exception {
        mockMvc.perform(post("/portfolio/policies")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Amara Dube","product":"Term Life","tier":"Platinum"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Tier Platinum is not available for Term Life."));
    }
}
