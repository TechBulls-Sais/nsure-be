package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.testsupport.GroupSchemeSetup;
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
 * Category-scoped certificates and scheme documents (NS-GL01-005, AT-GL01-02) and group claims on the section 08
 * benefit packs (NS-GL02-004/005, AT-GL02-03, acceptance pack CI survival boundary): critical illness survival,
 * related conditions, acceleration and reinstatement; lump-sum disability assessment and partial benefit; family
 * limits; income protection's limit, deferred period, TIP cessation and the waiting period.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-packs;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupBenefitPacksApiTest {

    private static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void certificatesAndDocumentsAreScopedToEachCategorysTerms() throws Exception {
        String scheme = schemeWithPacks();
        // AT-GL01-02: STAFF has no critical illness; MGMT has it on the comprehensive condition set.
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.memberKey == 'S1')].benefits[*]").value(org.hamcrest.Matchers.not(hasItem("CRITICAL_ILLNESS"))))
                .andExpect(jsonPath("$[?(@.memberKey == 'M1')].benefits[*]").value(hasItem("CRITICAL_ILLNESS")))
                .andExpect(jsonPath("$[?(@.memberKey == 'M1')].wording").value(hasItem("WRD-G001 v1")))
                .andExpect(jsonPath("$[?(@.memberKey == 'M1')].acceptedTerms").value(hasItem(containsString(" v1"))))
                .andExpect(jsonPath("$[?(@.memberKey == 'M1')].categoryCode").value(hasItem("MGMT")));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/schedule").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("%PDF")));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates/M1/document").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates/NOPE/document").with(signedIn("admin", "password")))
                .andExpect(status().isNotFound());
        // A STAFF member cannot claim a benefit the category does not have.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", "{\"memberKey\":\"S1\","
                        + "\"benefitType\":\"CRITICAL_ILLNESS\",\"eventDate\":\"2026-06-01\",\"amount\":1000,\"conditionGroup\":\"CANCER\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("does not have Critical illness")));
    }

    @Test
    void criticalIllnessNeedsSurvivalPaysOncePerConditionAcceleratesAndIsNotReinstatedWithoutARule() throws Exception {
        String scheme = schemeWithPacks();
        LocalDate diagnosis = LocalDate.now().minusDays(60);
        // MGMT 3 x 400,000 = 1,200,000 life cover; CI 1 x salary = 400,000.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", ci("M1", diagnosis, 450000, "CANCER")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("limit of 400000.00")));
        String claim = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        ci("M1", diagnosis, 400000, "CANCER")))
                .andExpect(jsonPath("$.benefitLimit").value(400000.0))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        // Acceptance pack, CI survival boundary: 29 days after diagnosis is too early; 30 days is enough.
        mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"ok\","
                        + "\"survivalConfirmedOn\":\"" + diagnosis.plusDays(29) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("survival for 30 days")));
        mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Survived\","
                        + "\"survivalConfirmedOn\":\"" + diagnosis.plusDays(30) + "\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.survivalConfirmedOn").value(diagnosis.plusDays(30).toString()));
        // Related conditions share one payment.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", ci("M1", diagnosis.plusDays(5), 1000, "cancer")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("related cancer condition")));
        // Accelerated: the payment reduces the life cover.
        mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), "admin", "{\"action\":\"PAY\",\"paymentReference\":\"EFT-CI-1\"}"))
                .andExpect(jsonPath("$.acceleratedAmount").value(400000.0));
        assertThat((List<Double>) JsonPath.read(detail(scheme), "$.members[?(@.memberKey == 'M1')].acceptedCover"))
                .containsExactly(800000.0);
        // AT-GL02-03: no reinstatement until the insurer's rule is configured.
        mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), "admin", "{\"action\":\"REINSTATE\",\"note\":\"Asked\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("No approved reinstatement rule")));
        jdbc.update("update grp_product_rule set default_value = 'AFTER_12_MONTHS' where param_code = 'REINSTATEMENT_RULE'");
        try {
            mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), "admin", "{\"action\":\"REINSTATE\",\"note\":\"Asked\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value(containsString("reinstated from " + LocalDate.now().plusMonths(12))));
        } finally {
            jdbc.update("update grp_product_rule set default_value = 'NOT_CONFIGURED' where param_code = 'REINSTATEMENT_RULE'");
        }
    }

    @Test
    void disabilityFamilyAndIncomeProtectionFollowTheirOwnTerms() throws Exception {
        String scheme = schemeWithPacks();
        // Lump-sum disability: 2 x 200,000 = 400,000; a partial (limited duties) claim pays at most 50%.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", "{\"memberKey\":\"S1\",\"benefitType\":"
                        + "\"LUMP_SUM_DISABILITY\",\"eventDate\":\"2026-03-01\",\"amount\":250000,\"partial\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("limit of 200000.00 for a partial disability")));
        String lsd = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        "{\"memberKey\":\"S1\",\"benefitType\":\"LUMP_SUM_DISABILITY\",\"eventDate\":\"2026-03-01\","
                                + "\"amount\":200000,\"partial\":true}"))
                .andExpect(jsonPath("$.partial").value(true))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group/claims/" + lsd + "/decision"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Assessed\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // The partial payment reduces the remaining cover: 200,000 is left.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", "{\"memberKey\":\"S1\",\"benefitType\":"
                        + "\"LUMP_SUM_DISABILITY\",\"eventDate\":\"2026-04-01\",\"amount\":250000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("limit of 200000.00")));
        // A disability is approved only after its assessment period.
        String recent = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        "{\"memberKey\":\"M1\",\"benefitType\":\"LUMP_SUM_DISABILITY\",\"eventDate\":\"" + LocalDate.now().minusMonths(1)
                                + "\",\"amount\":1000}"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group/claims/" + recent + "/decision"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("assessment period runs to")));

        // Family: spouse 20,000 plus 2,000 funeral support; two spouses at most; stillbirth from week 26.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", family("SPOUSE", "\"lifeName\":\"Ann\"", 23000)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", family("SPOUSE", "\"lifeName\":\"Ann\"", 22000)))
                .andExpect(jsonPath("$.benefitLimit").value(22000.0));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        family("SPOUSE", "\"lifeName\":\"Beth\",\"eventDate\":\"2026-05-02\"", 22000)))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        family("SPOUSE", "\"lifeName\":\"Cara\",\"eventDate\":\"2026-05-03\"", 22000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("up to 2 spouse(s)")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        family("STILLBIRTH", "\"gestationWeeks\":24,\"eventDate\":\"2026-05-04\"", 1000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("from week 26")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        family("STILLBIRTH", "\"gestationWeeks\":26,\"eventDate\":\"2026-05-04\"", 1000)))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        family("CHILD", "\"childAge\":22,\"eventDate\":\"2026-05-05\"", 1000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("to age 21")));

        // Income protection (MGMT is TIP for 12 months): 75% of 400,000 / 12 = 25,000 a month after 3 months.
        LocalDate disabled = LocalDate.now().minusMonths(2);
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", "{\"memberKey\":\"M1\",\"benefitType\":"
                        + "\"INCOME_PROTECTION\",\"eventDate\":\"" + disabled + "\",\"amount\":26000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("limit of 25000.00")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", "{\"memberKey\":\"M1\",\"benefitType\":"
                        + "\"INCOME_PROTECTION\",\"eventDate\":\"" + disabled + "\",\"amount\":25000}"))
                .andExpect(jsonPath("$.firstPaymentDate").value(disabled.plusMonths(3).toString()))
                .andExpect(jsonPath("$.cessationDate").value(disabled.plusMonths(15).toString()))
                .andExpect(jsonPath("$.escalationPercent").value(5.0));
        // The MGMT category's 90-day waiting period: only an accident is covered inside it.
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", "{\"memberKey\":\"M1\",\"benefitType\":"
                        + "\"GLA\",\"eventDate\":\"2026-02-01\",\"amount\":1000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("waiting period")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin", "{\"memberKey\":\"M1\",\"benefitType\":"
                        + "\"GLA\",\"eventDate\":\"2026-02-01\",\"amount\":1000,\"accident\":true}"))
                .andExpect(jsonPath("$.accident").value(true));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A scheme with STAFF (2 x salary) and MGMT (3 x salary, 90-day waiting period, 2,000,000 free cover), critical
     * illness (1 x salary, accelerating, not for STAFF, comprehensive for MGMT), family, lump-sum disability (2 x
     * salary) and income protection (TIP for 12 months in MGMT), approved from inception (backdated with a reason);
     * S1 (200,000) and M1 (400,000) on cover.
     */
    private String schemeWithPacks() throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin", "{\"name\":\"Packs " + System.nanoTime()
                        + "\",\"sector\":\"Retail\",\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        ok(post("/group/schemes/" + scheme + "/categories"), "{\"code\":\"STAFF\",\"name\":\"Staff\",\"benefitBasis\":\"SALARY_MULTIPLE\","
                + "\"salaryMultiple\":2,\"freeCoverLimit\":2000000,\"ratePerMille\":1,\"minEntryAge\":18,\"maxEntryAge\":64}");
        ok(post("/group/schemes/" + scheme + "/categories"), "{\"code\":\"MGMT\",\"name\":\"Management\",\"benefitBasis\":\"SALARY_MULTIPLE\","
                + "\"salaryMultiple\":3,\"freeCoverLimit\":2000000,\"ratePerMille\":1,\"minEntryAge\":18,\"maxEntryAge\":64,\"waitingDays\":90}");
        GroupSchemeSetup.activate(mockMvc, scheme);
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "{\"benefitType\":\"CRITICAL_ILLNESS\",\"basis\":\"SALARY_MULTIPLE\",\"multiple\":1}");
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "{\"benefitType\":\"FAMILY\",\"basis\":\"FIXED\",\"fixedAmount\":40000}");
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "{\"benefitType\":\"LUMP_SUM_DISABILITY\",\"basis\":\"SALARY_MULTIPLE\",\"multiple\":2}");
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "{\"benefitType\":\"INCOME_PROTECTION\",\"basis\":\"MONTHLY_INCOME_PERCENT\","
                + "\"multiple\":75,\"waitingMonths\":3}");
        ok(put("/group-life/schemes/" + scheme + "/benefit-terms"), "{\"benefitType\":\"CRITICAL_ILLNESS\",\"categoryCode\":\"STAFF\",\"included\":false,\"effectiveFrom\":\"2026-01-01\",\"reason\":\"Plan as quoted, from inception\"}");
        ok(put("/group-life/schemes/" + scheme + "/benefit-terms"), "{\"benefitType\":\"CRITICAL_ILLNESS\",\"categoryCode\":\"MGMT\","
                + "\"parameters\":{\"CONDITION_SET\":\"COMPREHENSIVE\"},\"effectiveFrom\":\"2026-01-01\",\"reason\":\"Plan as quoted, from inception\"}");
        ok(put("/group-life/schemes/" + scheme + "/benefit-terms"), "{\"benefitType\":\"CRITICAL_ILLNESS\",\"parameters\":{\"ACCELERATES_GLA\":\"YES\"},\"effectiveFrom\":\"2026-01-01\",\"reason\":\"Plan as quoted, from inception\"}");
        ok(put("/group-life/schemes/" + scheme + "/benefit-terms"), "{\"benefitType\":\"INCOME_PROTECTION\",\"categoryCode\":\"MGMT\","
                + "\"parameters\":{\"VARIANT\":\"TIP\",\"TIP_MONTHS\":12},\"effectiveFrom\":\"2026-01-01\",\"reason\":\"Plan as quoted, from inception\"}");
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/plan-approval"), "secadmin", "{\"note\":\"Plan agreed\"}"))
                .andExpect(status().isOk());
        String batch = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        "{\"externalBatchId\":\"PK-1\",\"mode\":\"PARTIAL\",\"csv\":\"" + HEADER
                                + "\\nS1,Sam,Staff,1985-01-01,STAFF,200000,JOIN,2026-01-01"
                                + "\\nM1,Mia,Manager,1980-01-01,MGMT,400000,JOIN,2026-01-01\"}"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.acceptedRows").value(2));
        return scheme;
    }

    private static String ci(String member, LocalDate diagnosis, int amount, String group) {
        return "{\"memberKey\":\"" + member + "\",\"benefitType\":\"CRITICAL_ILLNESS\",\"eventDate\":\"" + diagnosis
                + "\",\"amount\":" + amount + ",\"conditionGroup\":\"" + group + "\"}";
    }

    private static String family(String life, String extra, int amount) {
        String body = "{\"memberKey\":\"S1\",\"benefitType\":\"FAMILY\",\"life\":\"" + life + "\",\"amount\":" + amount + ","
                + extra + "}";
        return body.contains("eventDate") ? body : body.replace("}", ",\"eventDate\":\"2026-05-01\"}");
    }

    private String detail(String scheme) throws Exception {
        return mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
    }

    private void ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        mockMvc.perform(json(request, "admin", body)).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
