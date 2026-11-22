package com.nsure.reporting;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SH13: filtered dashboard sections, lives counted once, reconciliation to the subledger, permission-filtered and
 * masked drill-through, logged exports, the governed metric dictionary and the partner-scoped export
 * (NS-SH13-001..005, AT-SH13-01..03).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-management-reporting;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class ManagementReportingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void personsAreCountedOnceAndExposureSeparately() throws Exception {
        Map<String, Object> member = jdbc.queryForList("select first_name, last_name, date_of_birth from grp_member"
                + " where member_status = 'ACTIVE' order by id").get(0);
        String name = member.get("FIRST_NAME") + " " + member.get("LAST_NAME");
        String customer = customer(name, member.get("DATE_OF_BIRTH").toString(), "+267 71 800 001");
        String first = activePolicy(customer, 50);
        String second = activePolicy(customer, 60);

        String body = report("");
        long policies = number(body, "individual", "Policies in force");
        long lives = number(body, "individual", "Insured lives in force");
        // Two policies, one person.
        assertThat(policies - lives).isGreaterThanOrEqualTo(1);
        long distinct = number(body, "lives", "Distinct insured persons");
        long individual = number(body, "lives", "Individual insured lives");
        long group = number(body, "lives", "Group member lives");
        long both = number(body, "lives", "Covered in both");
        assertThat(both).isGreaterThanOrEqualTo(1);
        assertThat(distinct).isEqualTo(individual + group - both);
        assertThat(JsonPath.<List<String>>read(body, "$.sections[?(@.key == 'lives')].metrics[*].label"))
                .contains("Individual sum assured in force", "Group cover accepted");
        assertThat(JsonPath.<List<String>>read(body, "$.sections[*].key"))
                .contains("newbusiness", "collections", "documents", "actuarial");
        assertThat(JsonPath.<List<Object>>read(body, "$.sections[?(@.error != null)]")).isEmpty();

        // Drill-through reaches the source rows; the product filter narrows them.
        mockMvc.perform(get("/reports/drill").param("key", "policies.inforce").param("product", "Term Life")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[*].policy").value(org.hamcrest.Matchers.hasItems(first, second)))
                .andExpect(jsonPath("$.masked").value(false))
                .andExpect(jsonPath("$.definition.key").value("active_policies"));
        mockMvc.perform(get("/reports/drill").param("key", "policies.inforce").param("product", "Family Funeral")
                        .with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.rows[?(@.policy == '" + first + "')]").isEmpty());
    }

    @Test
    void drillThroughIsPermissionFilteredMaskedAndExportsAreLogged() throws Exception {
        // A group administrator reads reports and schemes, not policies or customers.
        mockMvc.perform(get("/reports/drill").param("key", "policies.inforce").with(signedIn("grpadmin", "password")))
                .andExpect(status().isForbidden());
        String members = mockMvc.perform(get("/reports/drill").param("key", "group.members")
                        .with(signedIn("grpadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(true))
                .andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(members, "$.rows[*].customer");
        assertThat(names).isNotEmpty().allSatisfy(n -> assertThat(n).contains("*"));
        mockMvc.perform(json(post("/reports/exports"), "grpadmin", "{\"kind\":\"DRILL\",\"key\":\"group.members\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(true))
                .andExpect(jsonPath("$.sensitive").value(false));
        mockMvc.perform(json(post("/reports/exports"), "compliance", "{\"kind\":\"DRILL\",\"key\":\"policies.inforce\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked").value(false))
                .andExpect(jsonPath("$.sensitive").value(true));
        mockMvc.perform(json(post("/reports/exports"), "compliance", "{\"kind\":\"SECTION\",\"key\":\"individual\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].group").value("Figure"));
        mockMvc.perform(get("/reports/exports").with(signedIn("compliance", "password")))
                .andExpect(jsonPath("$[*].exportedBy").value(org.hamcrest.Matchers.hasItems("grpadmin", "compliance")));
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where action = 'Report exported (personal data)'",
                Long.class)).isPositive();
        // Data extracts need the records' own read permission, and mask customers for those without it.
        mockMvc.perform(json(post("/platform/extracts"), "grpadmin", "{\"extractType\":\"POLICIES\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSubledgerReconciliationItemisesUnpostedPremiumUntilItPosts() throws Exception {
        String customer = customer("Recon Tumelo", "1980-05-05", "+267 71 800 002");
        activePolicy(customer, 75);
        String before = report("");
        assertThat(JsonPath.<List<String>>read(before,
                "$.sections[?(@.key == 'finance')].reconciliation[?(@.measure == 'Individual premium collected')].status"))
                .containsExactly("EXPLAINED");
        assertThat(JsonPath.<List<String>>read(before, "$.sections[?(@.key == 'finance')].reconciliation"
                + "[?(@.measure == 'Individual premium collected')].items[*].state")).contains("UNPOSTED");
        mockMvc.perform(post("/integration/outbox/dispatch").with(signedIn("admin", "password"))).andExpect(status().isOk());
        String after = report("");
        assertThat(JsonPath.<List<String>>read(after,
                "$.sections[?(@.key == 'finance')].reconciliation[?(@.measure == 'Individual premium collected')].status"))
                .containsExactly("RECONCILED");
    }

    @Test
    void metricDefinitionsAreVersionedAndApprovedByAnotherPerson() throws Exception {
        mockMvc.perform(get("/platform/metrics").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.name == 'Persistency (13-month)')].status").value(hasItem("PENDING_APPROVAL")))
                .andExpect(jsonPath("$[?(@.key == 'collection_rate')].denominator").value(hasItem("Billed amount of instalments due")));
        assertThat(JsonPath.<List<String>>read(report(""),
                "$.sections[?(@.key == 'actuarial')].metrics[?(@.key == 'claims_ratio')].definitionStatus"))
                .containsExactly("PENDING_APPROVAL");
        mockMvc.perform(json(post("/platform/metrics/claims_ratio/versions"), "admin", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/platform/metrics/claims_ratio/versions"), "admin",
                        "{\"changeNote\":\"Actuarial sign-off: paid claims over earned premium\",\"timeWindow\":\"Rolling 12 months\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(post("/platform/metrics/claims_ratio/versions/2/approval").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/platform/metrics/claims_ratio/versions/2/approval").with(signedIn("compliance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/platform/metrics/claims_ratio/versions/2/approval").with(signedIn("secadmin", "password")))
                .andExpect(status().isOk());
        assertThat(JsonPath.<List<String>>read(report(""),
                "$.sections[?(@.key == 'actuarial')].metrics[?(@.key == 'claims_ratio')].definitionStatus"))
                .containsExactly("APPROVED");
    }

    @Test
    void aBrokerExportHoldsOnlyTheBrokersPortfolioEvenWithABroaderTemplate() throws Exception {
        String customer = customer("Direct Onalenna", "1979-01-01", "+267 71 800 003");
        String direct = activePolicy(customer, 40);
        String agentRef = jdbc.queryForObject("select scope_ref from portal_account where username = 'agent'", String.class);
        String body = mockMvc.perform(get("/reports/partner-portfolio").param("template", "ALL_POLICIES")
                        .with(signedIn("agent", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("Intermediary " + agentRef + " only"))
                .andReturn().getResponse().getContentAsString();
        List<String> policies = JsonPath.read(body, "$.rows[*].policy");
        assertThat(policies).doesNotContain(direct);
        List<String> book = jdbc.queryForList("select p.record_ref from ins_policy p where p.record_ref in (select"
                + " a.policy_ref from dist_policy_assignment a join dist_intermediary i on i.id = a.intermediary_id"
                + " where i.record_ref = ? and a.ended_on is null) or p.application_ref in (select ap.record_ref from"
                + " ins_application ap where ap.intermediary_code = ?)", String.class, agentRef, agentRef);
        assertThat(policies).containsExactlyInAnyOrderElementsOf(book);
        assertThat(jdbc.queryForObject("select count(*) from ins_policy", Long.class)).isGreaterThan(policies.size());
        mockMvc.perform(get("/reports/partner-portfolio").with(signedIn("grpadmin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reports/exports").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.kind == 'PARTNER')].scope").value(hasItem("Intermediary " + agentRef + " only")));
    }

    private String report(String query) throws Exception {
        return mockMvc.perform(get("/reports/management" + query).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static long number(String body, String section, String label) {
        List<Object> values = JsonPath.read(body, "$.sections[?(@.key == '" + section + "')].metrics[?(@.label == '"
                + label + "')].value");
        return Math.round(Double.parseDouble(values.get(0).toString()));
    }

    private String customer(String name, String dob, String phone) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\",\"phone\":\"" + phone
                        + "\",\"dateOfBirth\":\"" + dob + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "admin", "{}")).andExpect(status().isOk());
        return ref;
    }

    private String activePolicy(String customer, int premium) throws Exception {
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":" + premium + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        return policy;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
