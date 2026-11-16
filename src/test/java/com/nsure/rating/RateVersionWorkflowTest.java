package com.nsure.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.portfolio.CoverTier;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** Tap Cover pricing (spec 02 §6.4, the Phase 2 exit criterion of spec 09) and the rate administration of spec 11. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-rate-workflow;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RateVersionWorkflowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateTableService rates;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ObjectMapper json;

    @Test
    void tapCoverSilverForAFamilyOfFourIsSeventeenFiftyAMonth() {
        // Spec 09 Phase 2 exit: principal 35 + spouse 32 + two children = P 9.65 + 5.60 + 1.05 + 1.20 = P 17.50.
        LocalDate today = LocalDate.now();
        RateTableService.PolicyPrice price = tx.execute(s -> {
            CoverTier silver = em.createQuery("select t from CoverTier t where t.product.name = 'Tap Cover'"
                    + " and t.name = 'Silver'", CoverTier.class).getSingleResult();
            return rates.pricePolicy(silver, List.of(
                    new RateTableService.LifeToPrice("Main Insured", "Kagiso", today.minusYears(35).minusDays(10)),
                    new RateTableService.LifeToPrice("Spouse", "Mpho", today.minusYears(32).minusDays(10)),
                    new RateTableService.LifeToPrice("Child", "Lesego", today.minusYears(3)),
                    new RateTableService.LifeToPrice("Child", "Neo", today.minusYears(7))), null).orElseThrow();
        });
        assertThat(price.monthlyTotal()).isEqualByComparingTo("17.50");
        assertThat(price.rateTableVersion()).isEqualTo("RT-00102-v1");
        assertThat(price.lines()).extracting(RateTableService.PricedLife::monthlyPremium)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("9.65"), new BigDecimal("5.60"), new BigDecimal("1.05"),
                        new BigDecimal("1.20"));
    }

    @Test
    void aGapIsAWarningInDraftButAnErrorOnPublish() throws Exception {
        // V9 (E-22): the 60-64 adult bands are left out of a Gold draft.
        String csv = mockMvc.perform(get("/admin/rate-tables/RT-00103/export").with(httpBasic("configurator", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("product_code,plan,life_type,age_from,age_to,cover_amount,monthly_premium\n")
                .contains("Tap Cover,Gold,Main Insured,35,39,15000,14.45");
        String trimmed = String.join("\n", csv.lines().filter(line -> !line.contains(",60,64,")).toList());
        saveDraft("RT-00103", 1, trimmed, "configurator");
        mockMvc.perform(get("/admin/rate-tables/RT-00103/versions/2/grid").with(httpBasic("configurator", "password")))
                .andExpect(jsonPath("$.version.issues").value(Matchers.hasItem("Main Insured: no band covers ages 60–64.")));
        mockMvc.perform(post("/admin/rate-tables/RT-00103/versions/2/submit").with(httpBasic("configurator", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/admin/rate-tables/RT-00103/versions/2/approve").with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"overrideReason\":\"Board-approved re-rate\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.startsWith("A version with gaps cannot be published")));
    }

    @Test
    void anImportIsADryRunWithTheMovementBeforeItBecomesADraft() throws Exception {
        String csv = mockMvc.perform(get("/admin/rate-tables/RT-00104/export").with(httpBasic("actuary", "password")))
                .andReturn().getResponse().getContentAsString();
        String raised = csv.replace("Tap Cover,Platinum,Main Insured,40,44,20000,25.90",
                "Tap Cover,Platinum,Main Insured,40,44,20000,26.81");
        String dryRun = mockMvc.perform(post("/admin/rate-tables/RT-00104/import").with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("csv", raised))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changedRows").value(1))
                .andExpect(jsonPath("$.issues").isEmpty())
                .andExpect(jsonPath("$.diff[0]").value("Main Insured 40–44: P25.90 → P26.81 (+3.5 %)"))
                .andReturn().getResponse().getContentAsString();
        // E-18: nothing was written by the dry run.
        mockMvc.perform(get("/admin/rate-tables/RT-00104").with(httpBasic("actuary", "password")))
                .andExpect(jsonPath("$.versions.length()").value(1));
        // A changed file is refused against the old token.
        mockMvc.perform(post("/admin/rate-tables/RT-00104/import/confirm").with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "csv", raised + "\n", "dryRunToken", "nope"))))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/admin/rate-tables/RT-00104/import/confirm").with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "csv", raised, "dryRunToken", JsonPath.read(dryRun, "$.dryRunToken")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.versions[0].source").value("IMPORT"));
        mockMvc.perform(get("/admin/rate-tables/RT-00104/versions/1/diff/2").with(httpBasic("compliance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(1));
        mockMvc.perform(get("/admin/rate-tables/RT-00104/versions/2/impact").with(httpBasic("actuary", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("RT-00104-v2"))
                .andExpect(jsonPath("$.policiesAffected").isNumber());
        // A rejected or bad file never becomes a draft: issues come back with line numbers.
        mockMvc.perform(post("/admin/rate-tables/RT-00104/import").with(httpBasic("actuary", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "csv", "product_code,plan,life_type,age_from,age_to,cover_amount,monthly_premium\nx,y,z"))))
                .andExpect(jsonPath("$.issues[0]").value(Matchers.startsWith("Line 2: expected 7 columns")))
                .andExpect(jsonPath("$.dryRunToken").doesNotExist());
    }

    @Test
    void aStaleCellPatchIsAConflictNotASilentOverwrite() throws Exception {
        mockMvc.perform(post("/admin/rate-tables/RT-00102/scale").with(httpBasic("configurator", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"factor\":1.05,\"lifeType\":\"Child\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.version.bands[?(@.ageFrom == 0)].premiums.Child").value(1.10));
        String detail = mockMvc.perform(get("/admin/rate-tables/RT-00102/versions/2/grid")
                        .with(httpBasic("configurator", "password")))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(detail, "$.versions[0].updatedAt");
        String cell = "{\"lifeType\":\"Spouse\",\"ageFrom\":30,\"ageTo\":34,\"monthlyPremium\":5.75}";
        mockMvc.perform(patch("/admin/rate-tables/RT-00102/versions/2/cells").with(httpBasic("configurator", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersionUpdatedAt\":\"" + token + "\",\"cells\":[" + cell + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.bands[?(@.ageFrom == 30)].premiums.Spouse").value(5.75));
        // Same token again: someone (this editor) changed the version since, so the patch is refused.
        mockMvc.perform(patch("/admin/rate-tables/RT-00102/versions/2/cells").with(httpBasic("configurator", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersionUpdatedAt\":\"1999-01-01T00:00\",\"cells\":[" + cell + "]}"))
                .andExpect(status().isConflict());
        // The live price is untouched by the draft.
        BigDecimal live = tx.execute(s -> rates.pricePolicy(em.createQuery("select t from CoverTier t where"
                        + " t.product.name = 'Tap Cover' and t.name = 'Silver'", CoverTier.class).getSingleResult(),
                List.of(new RateTableService.LifeToPrice("Main Insured", "A", LocalDate.now().minusYears(36))), null)
                .orElseThrow().monthlyTotal());
        assertThat(live).isEqualByComparingTo("9.65");
    }

    private void saveDraft(String ref, int baseVersion, String csv, String user) throws Exception {
        StringBuilder rows = new StringBuilder();
        for (String line : csv.lines().skip(1).toList()) {
            String[] cells = line.split(",");
            if (rows.length() > 0) {
                rows.append(',');
            }
            rows.append("{\"lifeType\":\"").append(cells[2]).append("\",\"ageFrom\":").append(cells[3])
                    .append(",\"ageTo\":").append(cells[4]).append(",\"coverAmount\":").append(cells[5])
                    .append(",\"monthlyPremium\":").append(cells[6]).append('}');
        }
        mockMvc.perform(put("/admin/rate-tables/" + ref + "/grid").with(httpBasic(user, "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseVersion\":" + baseVersion + ",\"rows\":[" + rows + "]}"))
                .andExpect(status().isOk());
    }
}
