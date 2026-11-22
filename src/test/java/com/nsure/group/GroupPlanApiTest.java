package com.nsure.group;

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
import com.nsure.integration.Events;
import com.nsure.integration.OutboxEvent;
import com.nsure.testsupport.GroupSchemeSetup;
import jakarta.persistence.EntityManager;
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
 * Plans, categories and benefits (NS-GL02-001..006, AT-GL02-01/02, section 08 packs): product rules with explicit
 * precedence and prohibited overrides, versioned terms approved by a second person, category factors, cap and
 * cessation, dated category versions and amendment impact.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-plan;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupPlanApiTest {

    private static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Test
    void categoryOverridesFollowTheProductRulesAndApplyOnlyOnceApproved() throws Exception {
        String scheme = scheme();
        category(scheme, "STAFF", "\"benefitBasis\":\"SALARY_MULTIPLE\",\"salaryMultiple\":2");
        category(scheme, "MGMT", "\"benefitBasis\":\"FIXED\",\"fixedCover\":800000");
        GroupSchemeSetup.activate(mockMvc, scheme);
        // Critical illness is no longer capped at the group credit CI limit; a GLA multiple above the product's is.
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "{\"benefitType\":\"CRITICAL_ILLNESS\",\"basis\":\"FIXED\",\"fixedAmount\":500000}");
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "{\"benefitType\":\"FAMILY\",\"basis\":\"FIXED\",\"fixedAmount\":40000}");
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefits"), "admin",
                        "{\"benefitType\":\"GLA\",\"basis\":\"SALARY_MULTIPLE\",\"multiple\":12}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("above the product maximum of 10")));

        // AT-GL02-01: above the product's maximum, or a parameter the product fixes, fails before any approval.
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefit-terms"), "admin",
                        "{\"benefitType\":\"FAMILY\",\"categoryCode\":\"MGMT\",\"parameters\":{\"MEMBER_AMOUNT\":50000}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("above the product's non-overridable maximum of 40000")));
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefit-terms"), "admin",
                        "{\"benefitType\":\"CRITICAL_ILLNESS\",\"parameters\":{\"SURVIVAL_DAYS\":14}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("fixed by the product at 30")));
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefit-terms"), "admin",
                        "{\"benefitType\":\"CRITICAL_ILLNESS\",\"parameters\":{\"CONDITION_SET\":\"PLATINUM\"}}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/benefit-terms").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(0));

        ok(put("/group-life/schemes/" + scheme + "/benefit-terms"), "{\"benefitType\":\"CRITICAL_ILLNESS\",\"categoryCode\":\"MGMT\","
                + "\"parameters\":{\"CONDITION_SET\":\"comprehensive\"}}");
        ok(put("/group-life/schemes/" + scheme + "/benefit-terms"), "{\"benefitType\":\"FAMILY\",\"parameters\":{\"SPOUSE_AMOUNT\":15000}}");
        ok(put("/group-life/schemes/" + scheme + "/benefit-terms"), "{\"benefitType\":\"CRITICAL_ILLNESS\",\"categoryCode\":\"STAFF\",\"included\":false}");
        // Pending changes do not apply yet.
        String resolved = resolved(scheme);
        assertThat((List<String>) JsonPath.read(resolved, "$.rows[?(@.benefitType == 'CRITICAL_ILLNESS' && @.category == 'MGMT')]"
                + ".parameters[?(@.parameter == 'CONDITION_SET')].resolved")).containsExactly("CORE");
        assertThat((List<Object>) JsonPath.read(resolved, "$.pending")).hasSize(3);

        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/plan-approval"), "admin", "{\"note\":\"mine\"}"))
                .andExpect(status().isForbidden());
        int plans = events(Events.SCHEME_PLAN_APPROVED, scheme).size();
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/plan-approval"), "secadmin",
                        "{\"note\":\"Category terms agreed with the sponsor\"}"))
                .andExpect(status().isOk());
        assertThat(events(Events.SCHEME_PLAN_APPROVED, scheme)).hasSize(plans + 1);
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/benefits").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.benefitType == 'CRITICAL_ILLNESS')].approvalStatus").value(hasItem("APPROVED")))
                .andExpect(jsonPath("$[?(@.benefitType == 'CRITICAL_ILLNESS')].productMaximum").value(hasItem((Object) null)));

        // Precedence: category over scheme over product default.
        resolved = resolved(scheme);
        assertThat((List<String>) JsonPath.read(resolved, "$.rows[?(@.benefitType == 'CRITICAL_ILLNESS' && @.category == 'MGMT')]"
                + ".parameters[?(@.parameter == 'CONDITION_SET')].resolved")).containsExactly("COMPREHENSIVE");
        assertThat((List<String>) JsonPath.read(resolved, "$.rows[?(@.benefitType == 'CRITICAL_ILLNESS' && @.category == 'MGMT')]"
                + ".parameters[?(@.parameter == 'CONDITION_SET')].source")).containsExactly("Category terms");
        assertThat((List<String>) JsonPath.read(resolved, "$.rows[?(@.benefitType == 'FAMILY' && @.category == 'STAFF')]"
                + ".parameters[?(@.parameter == 'SPOUSE_AMOUNT')].resolved")).containsExactly("15000");
        assertThat((List<String>) JsonPath.read(resolved, "$.rows[?(@.benefitType == 'FAMILY' && @.category == 'STAFF')]"
                + ".parameters[?(@.parameter == 'SPOUSE_AMOUNT')].source")).containsExactly("Scheme terms");
        assertThat((List<String>) JsonPath.read(resolved, "$.rows[?(@.benefitType == 'FAMILY' && @.category == 'STAFF')]"
                + ".parameters[?(@.parameter == 'STILLBIRTH_WEEKS')].resolved")).containsExactly("26");
        assertThat((List<Boolean>) JsonPath.read(resolved, "$.rows[?(@.benefitType == 'CRITICAL_ILLNESS' && @.category == 'STAFF')]"
                + ".included")).containsExactly(false);

        // A backdated change needs the authority's reason, and records its impact assessment.
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefit-terms"), "admin",
                        "{\"benefitType\":\"FAMILY\",\"parameters\":{\"CHILD_AMOUNT\":10000},\"effectiveFrom\":\"2026-02-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("needs a reason")));
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefit-terms"), "admin",
                        "{\"benefitType\":\"FAMILY\",\"parameters\":{\"CHILD_AMOUNT\":10000},\"effectiveFrom\":\"2026-02-01\","
                                + "\"reason\":\"Agreed at renewal negotiation, letter of 1 Feb\"}"))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.impact.backdated").value(true));
        mockMvc.perform(get("/group-life/product-rules").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.parameter == 'SURVIVAL_DAYS')].overridable").value(hasItem(false)));
    }

    @Test
    void categoryFactorsCapsVersionsAndCessationApplyFromTheirDates() throws Exception {
        String scheme = scheme();
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", body("STAFF",
                        "\"benefitBasis\":\"SALARY_MULTIPLE\",\"salaryMultiple\":2,\"exitAge\":60")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Exit age must be above the maximum entry age")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", body("STAFF",
                        "\"benefitBasis\":\"SALARY_MULTIPLE\",\"salaryMultiple\":2,\"employmentClass\":\"Permanent staff\","
                                + "\"occupationClass\":\"CLASS_1\",\"salaryBasis\":\"BASIC\",\"exitAge\":65,\"waitingDays\":90,"
                                + "\"maxCover\":450000,\"eligibilityRule\":\"Permanent employees after 3 months' service\"")))
                .andExpect(jsonPath("$.employmentClass").value("Permanent staff"))
                .andExpect(jsonPath("$.exitAge").value(65))
                .andExpect(jsonPath("$.maxCover").value(450000.0))
                .andExpect(jsonPath("$.version").value(1));
        GroupSchemeSetup.activate(mockMvc, scheme);
        // V3 was 64 at the start of 2026; the 300,000 salary would give 600,000, capped at 450,000.
        commit(scheme, "PL-1", "V1,Val,One,1990-01-01,STAFF,100000,JOIN,2026-01-01\n"
                + "V2,Val,Two,1990-01-01,STAFF,300000,JOIN,2026-01-01\nV3,Val,Three,1961-06-01,STAFF,100000,JOIN,2026-01-01");
        assertThat((List<Double>) JsonPath.read(detail(scheme), "$.members[?(@.memberKey == 'V2')].acceptedCover"))
                .containsExactly(450000.0);

        // The amendment's impact first, with nothing changed: the rate change moves the monthly premium.
        String amended = body("STAFF", "\"benefitBasis\":\"SALARY_MULTIPLE\",\"salaryMultiple\":3,\"ratePerMille\":2,"
                + "\"exitAge\":65,\"maxCover\":450000");
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories/STAFF/impact"), "admin", amended))
                .andExpect(jsonPath("$.membersOnCover").value(3))
                .andExpect(jsonPath("$.monthlyPremiumNow").value(850.0))
                .andExpect(jsonPath("$.monthlyPremiumProposed").value(1700.0))
                .andExpect(jsonPath("$.certificatesToReissue").value(3));
        mockMvc.perform(json(put("/group/schemes/" + scheme + "/categories/STAFF"), "admin",
                        amended.replace("}", ",\"effectiveFrom\":\"" + LocalDate.now().plusDays(3) + "\"}")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/group/schemes/" + scheme + "/categories/STAFF"), "admin",
                        amended.replace("}", ",\"effectiveFrom\":\"2026-06-01\"}")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("backdated amendment needs a reason")));
        mockMvc.perform(json(put("/group/schemes/" + scheme + "/categories/STAFF"), "admin", amended))
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(get("/group/schemes/" + scheme + "/categories/STAFF/versions").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].version").value(2))
                .andExpect(jsonPath("$[0].effectiveFrom").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[1].salaryMultiple").value(2.0));

        // AT-GL02-02: a salary change dated before the amendment uses the formula version then in force; one dated
        // from the amendment uses the new formula. Earlier cover is not rewritten.
        commit(scheme, "PL-2", "V1,,,,,120000,SALARY,2026-03-01");
        assertThat((List<Double>) JsonPath.read(detail(scheme), "$.members[?(@.memberKey == 'V1')].acceptedCover"))
                .containsExactly(240000.0);
        commit(scheme, "PL-3", "V1,,,,,130000,SALARY," + LocalDate.now());
        assertThat((List<Double>) JsonPath.read(detail(scheme), "$.members[?(@.memberKey == 'V1')].acceptedCover"))
                .containsExactly(390000.0);

        // Benefit cessation is its own run, not the renewal: V3 reached 65 on 1 June 2026.
        mockMvc.perform(get("/group/schemes/" + scheme + "/cessations").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].memberKey").value("V3"))
                .andExpect(jsonPath("$[0].ceasesOn").value("2026-05-31"));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/cessations"), "admin", "{}"))
                .andExpect(status().isOk());
        String detail = detail(scheme);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'V3')].status")).containsExactly("CEASED");
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'V3')].exitDate")).containsExactly("2026-05-31");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String scheme() throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin", "{\"name\":\"Plan Works " + System.nanoTime()
                        + "\",\"sector\":\"Retail\",\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.scheme.ref");
    }

    private void category(String scheme, String code, String terms) throws Exception {
        ok(post("/group/schemes/" + scheme + "/categories"), body(code, terms));
    }

    private static String body(String code, String terms) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + code + " category\"," + terms + ",\"freeCoverLimit\":500000,"
                + (terms.contains("ratePerMille") ? "" : "\"ratePerMille\":1,") + "\"minEntryAge\":18,\"maxEntryAge\":64}";
    }

    private void commit(String scheme, String id, String rows) throws Exception {
        String batch = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        "{\"externalBatchId\":\"" + id + "\",\"mode\":\"PARTIAL\",\"csv\":\"" + HEADER + "\\n"
                                + rows.replace("\n", "\\n") + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejectedRows").value(0));
    }

    private String detail(String scheme) throws Exception {
        return mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
    }

    private String resolved(String scheme) throws Exception {
        return mockMvc.perform(get("/group-life/schemes/" + scheme + "/resolved-benefits").with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        mockMvc.perform(json(request, "admin", body)).andExpect(status().isOk());
    }

    private List<OutboxEvent> events(String type, String ref) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :t and e.aggregateRef = :r", OutboxEvent.class)
                .setParameter("t", type).setParameter("r", ref).getResultList();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
