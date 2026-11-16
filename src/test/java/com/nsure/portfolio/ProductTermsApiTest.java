package com.nsure.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.time.LocalDate;
import java.util.List;
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
 * NS-SH02-003/004, NS-IL03-001, AT-SH02-02: product terms are versioned. A policy keeps the waiting period, grace
 * period, beneficiary limit and wording it was sold under; a change is approved by a second person and reaches new
 * business only.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-product-terms;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProductTermsApiTest {

    private static final String TERM_LIFE = "/portfolio/products/Term Life/versions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void provisionUsers() {
        ModuleTestUsers.provision(jdbc);
        // A product maker who may propose terms but not approve them.
        String role = "Test product maker";
        jdbc.update("INSERT INTO app_role (name, description, disabled) SELECT ?, 'Test-only role.', FALSE "
                + "WHERE NOT EXISTS (SELECT 1 FROM app_role WHERE name = ?)", role, role);
        for (String code : List.of("READ_PRODUCT", "UPDATE_PRODUCT", "READ_POLICY")) {
            jdbc.update("INSERT INTO app_role_permission (role_id, permission_id) SELECT r.id, p.id FROM app_role r "
                    + "JOIN app_permission p ON p.code = ? WHERE r.name = ? AND NOT EXISTS (SELECT 1 FROM "
                    + "app_role_permission x WHERE x.role_id = r.id AND x.permission_id = p.id)", code, role);
        }
        jdbc.update("INSERT INTO app_user_role (user_id, role_id) SELECT u.id, r.id FROM app_user u JOIN app_role r "
                + "ON r.name = ? WHERE u.username = 'distribution' AND NOT EXISTS (SELECT 1 FROM app_user_role x "
                + "WHERE x.user_id = u.id AND x.role_id = r.id)", role);
    }

    @Test
    void aPolicyKeepsTheTermsItWasSoldUnderAndAChangeReachesNewBusinessOnly() throws Exception {
        String before = pendingPolicy("Thato Molefe", "thato");
        String terms = mockMvc.perform(get("/portfolio/policies/" + before + "/terms").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();
        int grace = JsonPath.read(terms, "$.gracePeriodDays");
        int waiting = JsonPath.read(terms, "$.waitingPeriodDays");

        String change = "{\"gracePeriodDays\":" + (grace + 16) + ",\"waitingPeriodDays\":" + (waiting + 150)
                + ",\"wordingRef\":\"Term Life wording v2.0\",\"reason\":\"Repriced waiting period\",\"baseVersion\":1}";
        // A reason is required, and the past cannot be rewritten.
        mockMvc.perform(json(post(TERM_LIFE), "distribution", change.replace("\"reason\":\"Repriced waiting period\",", "")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post(TERM_LIFE), "distribution",
                        change.replace("\"baseVersion\":1", "\"effectiveFrom\":\"" + LocalDate.now().minusDays(1) + "\"")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post(TERM_LIFE), "distribution", change))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.inForce").value(false));
        // Proposed is not approved: new business still gets v1.
        String meanwhile = pendingPolicy("Kabo Sello", "kabo");
        mockMvc.perform(get("/portfolio/policies/" + meanwhile + "/terms").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.version").value(1));
        // The maker cannot approve; another user can.
        mockMvc.perform(post(TERM_LIFE + "/2/approve").with(httpBasic("distribution", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(TERM_LIFE + "/2/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.inForce").value(true));

        // The earlier policies keep v1: paying the first premium sets grace from v1, not from v2.
        String paid = mockMvc.perform(json(post("/portfolio/policies/" + before + "/payments"), "admin",
                        "{\"method\":\"Card\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        LocalDate due = LocalDate.parse(JsonPath.read(paid, "$.nextPremiumDue"));
        assertThat(LocalDate.parse((String) JsonPath.read(paid, "$.gracePeriodEnd"))).isEqualTo(due.plusDays(grace));
        mockMvc.perform(get("/portfolio/policies/" + before + "/terms").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.waitingPeriodDays").value(waiting));

        // New business from today is sold under v2.
        String after = pendingPolicy("Neo Dube", "neo");
        mockMvc.perform(get("/portfolio/policies/" + after + "/terms").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.waitingPeriodDays").value(waiting + 150))
                .andExpect(jsonPath("$.wordingRef").value("Term Life wording v2.0"));

        // Both versions stay readable; v1 now ends yesterday for new business.
        String versions = mockMvc.perform(get(TERM_LIFE).with(httpBasic("distribution", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(versions, "$[?(@.version == 1)].effectiveTo"))
                .containsExactly(LocalDate.now().minusDays(1).toString());
        assertThat((List<Integer>) JsonPath.read(versions, "$[?(@.version == 1)].gracePeriodDays")).containsExactly(grace);
        assertThat(((List<Integer>) JsonPath.read(versions, "$[?(@.version == 1)].policies")).get(0))
                .isGreaterThanOrEqualTo(2);

        // Whoever proposes cannot approve, even with the permission to.
        mockMvc.perform(json(post(TERM_LIFE), "admin", "{\"maxBeneficiaries\":3,\"reason\":\"Fewer beneficiaries\","
                        + "\"effectiveFrom\":\"" + LocalDate.now().plusDays(30) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post(TERM_LIFE), "distribution", "{\"maxBeneficiaries\":4,\"reason\":\"Another\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post(TERM_LIFE + "/3/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post(TERM_LIFE + "/3/reject"), "admin", "{\"reason\":\"No\"}"))
                .andExpect(status().isForbidden());
    }

    /** A customer buys Term Life Silver and is left Pending Payment. Returns the policy reference. */
    private String pendingPolicy(String name, String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 003\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\",\"pep\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String customer = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(post("/portfolio/shop").with(httpBasic(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(shop, "$.requests[0].id");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
