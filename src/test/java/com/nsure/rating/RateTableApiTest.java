package com.nsure.rating;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-rate-tables;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RateTableApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void seededTablesFollowTheDemoServerFormat() throws Exception {
        mockMvc.perform(get("/admin/rate-tables").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8)) // 4 demo tables + the 4 Tap Cover plans (spec 02 §6.4)
                .andExpect(jsonPath("$[0].ref").value("RT-00001"))
                .andExpect(jsonPath("$[0].title").value("Family Funeral – Bronze"))
                .andExpect(jsonPath("$[0].productType").value("Funeral"))
                .andExpect(jsonPath("$[0].version").value("v1"))
                .andExpect(jsonPath("$[0].rows").value(55))
                .andExpect(jsonPath("$[3].plan").value("Bronze"))
                .andExpect(jsonPath("$[3].product").value("Individual Credit"))
                .andExpect(jsonPath("$[3].lifeTypes").value(Matchers.contains("Main Insured")));
    }

    @Test
    void gridIsTheDemoCurveScaledToTheTierCover() throws Exception {
        // Silver is BWP 50,000: five times the demo's per-BWP-10,000 Boikanyo Standard rates.
        mockMvc.perform(get("/admin/rate-tables/RT-00002").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("Silver"))
                .andExpect(jsonPath("$.rules.length()").value(6))
                .andExpect(jsonPath("$.version.label").value("RT-00002-v1"))
                .andExpect(jsonPath("$.version.cover['Main Insured']").value(50000.0))
                .andExpect(jsonPath("$.version.cover['Child']").value(10000.0))
                .andExpect(jsonPath("$.version.bands[?(@.ageFrom == 40 && @.ageTo == 44)].premiums['Main Insured']")
                        .value(64.75))
                .andExpect(jsonPath("$.version.bands[?(@.ageFrom == 0)].premiums['Child']").value(5.25))
                .andExpect(jsonPath("$.version.issues").isEmpty());
    }

    @Test
    void aChangeIsADraftUntilASecondPersonApprovesItWithNotice() throws Exception {
        // Spec 11 E-13: saving never changes the live price; it writes a DRAFT (Product Configurator: rate.write).
        mockMvc.perform(put("/admin/rate-tables/RT-00004/grid")
                        .with(httpBasic("configurator", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":1,"rows":[
                                  {"lifeType":"Main Insured","ageFrom":18,"ageTo":39,"coverAmount":90000,"monthlyPremium":60},
                                  {"lifeType":"Main Insured","ageFrom":40,"ageTo":65,"coverAmount":90000,"monthlyPremium":150.5}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.label").value("RT-00004-v2"))
                .andExpect(jsonPath("$.version.current").value(false))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.versions[0].createdBy").value("configurator"))
                .andExpect(jsonPath("$.versions[0].changeSummary").value(Matchers.containsString("Version v1 → v2")))
                .andExpect(jsonPath("$.versions[0].changeSummary")
                        .value(Matchers.containsString("Added Main Insured 18–39: cover P90,000, P60")))
                .andExpect(jsonPath("$.versions[1].status").value("PUBLISHED"));

        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/2/submit").with(httpBasic("configurator", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].status").value("PENDING_APPROVAL"));
        // E-11: only the Actuary holds rate.publish.
        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/2/approve").with(httpBasic("configurator", "password")))
                .andExpect(status().isForbidden());
        // E-26 / TC-29: a re-rate needs 90 days' written notice, or an override reason.
        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/2/approve").with(httpBasic("actuary", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.startsWith("A re-rate needs 90 days' written notice")));
        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/2/approve")
                        .with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"noticeIssuedAt\":\"" + java.time.LocalDate.now().minusDays(91) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.versions[0].approvedBy").value("actuary"))
                .andExpect(jsonPath("$.versions[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.versions[1].validTo").value(Matchers.not("")));

        // The superseded version is still readable, unchanged.
        mockMvc.perform(get("/admin/rate-tables/RT-00004?version=1").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.current").value(false))
                .andExpect(jsonPath("$.version.bands.length()").value(10));

        // Saving from the old version is refused rather than silently overwriting v2.
        mockMvc.perform(put("/admin/rate-tables/RT-00004/grid")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":1,"rows":[
                                  {"lifeType":"Main Insured","ageFrom":18,"ageTo":65,"coverAmount":90000,"monthlyPremium":70}
                                ]}
                                """))
                .andExpect(status().isConflict());

        // Four-eyes (E-24): the actuary authors v3 and cannot also publish it.
        mockMvc.perform(put("/admin/rate-tables/RT-00004/grid")
                        .with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":2,"rows":[
                                  {"lifeType":"Main Insured","ageFrom":18,"ageTo":39,"coverAmount":90000,"monthlyPremium":61},
                                  {"lifeType":"Main Insured","ageFrom":40,"ageTo":65,"coverAmount":90000,"monthlyPremium":150.5}
                                ]}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/3/submit").with(httpBasic("actuary", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/3/approve")
                        .with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"noticeIssuedAt\":\"" + java.time.LocalDate.now().minusDays(91) + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You authored this rate version, so you cannot also publish it. "
                        + "A second approver must action it."));
        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/3/reject").with(httpBasic("actuary", "password")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/admin/rate-tables/RT-00004/versions/3/reject")
                        .with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Withdrawn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.versions[0].rejectionReason").value("Withdrawn"));
    }

    @Test
    void invalidGridsAreRefusedWithEveryReason() throws Exception {
        mockMvc.perform(put("/admin/rate-tables/RT-00004/grid")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rows":[
                                  {"lifeType":"Spouse","ageFrom":18,"ageTo":65,"coverAmount":90000,"monthlyPremium":10},
                                  {"lifeType":"Main Insured","ageFrom":18,"ageTo":40,"coverAmount":90000,"monthlyPremium":10},
                                  {"lifeType":"Main Insured","ageFrom":35,"ageTo":65,"coverAmount":90000,"monthlyPremium":0}
                                ]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString(
                        "Row 1: 'Spouse' is not in the Life Assured Rules of plan 'Bronze'. Allowed: Main Insured")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString("Row 3: premium must be greater than 0")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(
                        "Main Insured: age band 35–65 overlaps with 18–40")));
    }

    @Test
    void ratedPlansPriceTheMainInsuredByAgeAndStampTheVersion() throws Exception {
        mockMvc.perform(get("/portfolio/products").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Family Funeral')].productType").value("Funeral"))
                .andExpect(jsonPath("$[?(@.name == 'Family Funeral')].tiers[?(@.name == 'Bronze')].priceIsFrom").value(true))
                .andExpect(jsonPath("$[?(@.name == 'Family Funeral')].tiers[?(@.name == 'Bronze')].premium").value(15))
                .andExpect(jsonPath("$[?(@.name == 'Family Funeral')].premium").value("from BWP 15"))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].tiers[?(@.name == 'Silver')].priceIsFrom").value(false));

        // Naledi Kgosi (born 1985) is in the 40-44 or 45-49 band; flat Bronze would have been BWP 105. Bronze is 2.5x the per-BWP-10,000 rates, kept to the thebe.
        mockMvc.perform(post("/portfolio/applications")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Naledi Kgosi","product":"Family Funeral","tier":"Bronze","premium":1}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateTableVersion").value("RT-00001-v1"))
                .andExpect(jsonPath("$.premium").value(Matchers.anyOf(Matchers.is(32.38), Matchers.is(45.25))));
    }

    @Test
    void readingNeedsTheRateCapability() throws Exception {
        mockMvc.perform(get("/admin/rate-tables")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/rate-tables").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
    }
}
