package com.nsure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The guards on the grant table itself (spec 04 §8) and the rules of IMPLEMENT_RBAC.md, exercised through the API so
 * no path around them is left untested.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-role-guards;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RoleGuardsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theProtectedCustomerRoleCanNeverBeArmedRenamedOrDeleted() throws Exception {
        long portal = roleId("Policyholder / End User");
        MockHttpServletResponse grant = call(put("/roles/" + portal + "/permissions"), "admin",
                "{\"permissions\":{\"READ_CLAIM\":true}}");
        assertThat(grant.getStatus()).isEqualTo(422);
        assertThat(grant.getContentAsString()).contains("cannot be granted permissions — every portal customer holds it");
        assertThat(call(put("/roles/" + portal), "admin", "{\"deskAccess\":true}").getStatus()).isEqualTo(422);
        assertThat(call(put("/roles/" + portal), "admin", "{\"name\":\"Customers\"}").getStatus()).isEqualTo(422);
        assertThat(call(delete("/roles/" + portal), "admin", null).getStatus()).isEqualTo(422);
        assertThat(call(put("/admin/approval-authorities/" + portal), "admin", "{\"maxPayoutAmount\":1000}").getStatus())
                .isEqualTo(422);
    }

    @Test
    void aRoleHeldByAPortalCustomerCannotBeArmedLater() throws Exception {
        // A-19: create an empty role, give it to a customer, arm it later — closed at the last step.
        long club = createRole("Loyalty club", false);
        long customer = userId("customer");
        long portal = roleId("Policyholder / End User");
        approved(call(put("/users/" + customer), "admin", "{\"roleIds\":[" + portal + "," + club + "]}"));
        MockHttpServletResponse arm = call(put("/roles/" + club + "/permissions"), "admin",
                "{\"permissions\":{\"READ_CLAIM\":true}}");
        assertThat(arm.getStatus()).isEqualTo(422);
        assertThat(arm.getContentAsString()).contains("is held by 1 portal customer(s)");
        assertThat(call(put("/roles/" + club), "admin", "{\"deskAccess\":true}").getStatus()).isEqualTo(422);

        // …and an armed role cannot be handed to a customer login either.
        MockHttpServletResponse armed = call(put("/users/" + customer), "admin",
                "{\"roleIds\":[" + portal + "," + roleId("Claims Assessor") + "]}");
        assertThat(armed.getStatus()).isEqualTo(422);
        assertThat(armed.getContentAsString()).contains("is linked to a customer record");
        approved(call(put("/users/" + customer), "admin", "{\"roleIds\":[" + portal + "]}"));
    }

    @Test
    void aDisabledRoleGrantsNothing() throws Exception {
        long readers = createRole("Temporary claim readers", true);
        grant(readers, Map.of("READ_CLAIM", true));
        createUser("tempreader", readers);
        assertThat(statusOf("tempreader", "/portfolio/claims")).isEqualTo(200);
        approved(call(put("/roles/" + readers), "admin", "{\"disabled\":true}"));
        assertThat(statusOf("tempreader", "/portfolio/claims")).isEqualTo(403);
    }

    @Test
    void blanketCodesNoLongerExistAndTheAdminRoleKeepsItsLifeline() throws Exception {
        // Nsure Life NS-SH11-002: deny by default with explicit grants only, so the old wildcards are unknown codes.
        long role = createRole("Would read everything", true);
        for (String wildcard : List.of("ALL_FUNCTIONS", "ALL_FUNCTIONS_READ", "CHECKER_SUPER_USER")) {
            MockHttpServletResponse refused = call(put("/roles/" + role + "/permissions"), "admin",
                    "{\"permissions\":{\"" + wildcard + "\":true}}");
            assertThat(refused.getStatus()).isEqualTo(422);
            assertThat(refused.getContentAsString()).contains("Unknown permission " + wildcard);
        }
        // The administrator role cannot drop the codes needed to restore grants, so nobody is locked out.
        MockHttpServletResponse lifeline = call(put("/roles/" + roleId("Super Admin") + "/permissions"), "admin",
                "{\"permissions\":{\"PERMISSIONS_ROLE\":false}}");
        assertThat(lifeline.getStatus()).isEqualTo(422);
        assertThat(lifeline.getContentAsString()).contains("must keep PERMISSIONS_ROLE");
    }

    @Test
    void aDelegatedAdministratorCannotGrantWhatTheyDoNotHold() throws Exception {
        long delegates = createRole("Access delegates", true);
        grant(delegates, Map.of("READ_ROLE", true, "PERMISSIONS_ROLE", true, "READ_CLAIM", true));
        createUser("delegate", delegates);
        long target = createRole("Delegated target", true);

        assertThat(statusOf("delegate", "/roles")).isEqualTo(200);
        // PERMISSIONS_ROLE is a maker-checker code: the delegate's grant waits for a checker (202).
        assertThat(call(put("/roles/" + target + "/permissions"), "delegate",
                "{\"permissions\":{\"READ_CLAIM\":true}}").getStatus()).isEqualTo(202);
        MockHttpServletResponse escalate = call(put("/roles/" + target + "/permissions"), "delegate",
                "{\"permissions\":{\"APPROVE_CLAIM\":true}}");
        assertThat(escalate.getStatus()).isEqualTo(403);
        assertThat(escalate.getContentAsString()).contains("you do not hold it yourself");
        // Users and the Super Admin role are out of reach without their own codes.
        assertThat(statusOf("delegate", "/users")).isEqualTo(403);
        assertThat(call(put("/roles/" + roleId("Super Admin") + "/permissions"), "delegate",
                "{\"permissions\":{\"READ_CLAIM\":true}}").getStatus()).isEqualTo(403);
    }

    @Test
    void thePermissionVocabularyIsClosed() throws Exception {
        assertThat(call(post("/permissions"), "admin", "{\"code\":\"MAKE_COFFEE\",\"grouping\":\"x\"}").getStatus())
                .isEqualTo(403);
        assertThat(call(delete("/permissions/1"), "admin", null).getStatus()).isEqualTo(403);
        MockHttpServletResponse unknown = call(put("/roles/" + roleId("Actuary") + "/permissions"), "admin",
                "{\"permissions\":{\"MAKE_COFFEE\":true}}");
        assertThat(unknown.getStatus()).isEqualTo(422);
        // Relabelling is allowed; renaming the code is not.
        String permissions = mockMvc.perform(get("/permissions").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer readClaim = JsonPath.<List<Integer>>read(permissions, "$[?(@.code == 'READ_CLAIM')].id").get(0);
        assertThat(call(put("/permissions/" + readClaim), "admin", "{\"code\":\"READ_CLAIMS\"}").getStatus())
                .isEqualTo(422);
        assertThat(call(put("/permissions/" + readClaim), "admin", "{\"functionName\":\"View claims\"}").getStatus())
                .isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(permissions, "$[?(@.code == 'APPROVE_CLAIM')].capability"))
                .containsExactly("claim.approve");
    }

    @Test
    void approvalLimitsMustBePositive() throws Exception {
        long assessors = roleId("Claims Assessor");
        MockHttpServletResponse zero = call(put("/admin/approval-authorities/" + assessors), "admin",
                "{\"maxPayoutAmount\":0}");
        assertThat(zero.getStatus()).isEqualTo(422);
        assertThat(zero.getContentAsString()).contains("Delete the row to remove the limit.");
        String list = mockMvc.perform(get("/admin/approval-authorities").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Double>>read(list, "$[?(@.roleName == 'Claims Assessor')].maxPayoutAmount"))
                .containsExactly(50000.0);
        assertThat(list).doesNotContain("Policyholder / End User");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private long createRole(String name, boolean deskAccess) throws Exception {
        MockHttpServletResponse created = call(post("/roles"), "admin",
                "{\"name\":\"" + name + "\",\"description\":\"Test role\",\"deskAccess\":" + deskAccess + "}");
        assertThat(created.getStatus()).as(created.getContentAsString()).isEqualTo(201);
        return ((Number) JsonPath.read(created.getContentAsString(), "$.resourceId")).longValue();
    }

    private void grant(long roleId, Map<String, Boolean> codes) throws Exception {
        StringBuilder body = new StringBuilder("{\"permissions\":{");
        codes.forEach((code, on) -> body.append('"').append(code).append("\":").append(on).append(','));
        body.setLength(body.length() - 1);
        body.append("}}");
        approved(call(put("/roles/" + roleId + "/permissions"), "admin", body.toString()));
    }

    private void createUser(String username, long roleId) throws Exception {
        MockHttpServletResponse response = call(post("/users"), "admin", "{\"username\":\"" + username
                + "\",\"password\":\"password\",\"roleIds\":[" + roleId + "]}");
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
    }

    private long roleId(String name) throws Exception {
        String roles = mockMvc.perform(get("/roles").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<List<Object>>read(roles, "$[?(@.name == '" + name + "')].id").get(0)).longValue();
    }

    private long userId(String username) throws Exception {
        String users = mockMvc.perform(get("/users").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.<List<Object>>read(users, "$[?(@.username == '" + username + "')].id").get(0))
                .longValue();
    }

    /**
     * Identity changes are maker-checker for everyone (no self-approval, NS-UX-004): the admin's change waits (202) and
     * a second administrator applies it.
     */
    private void approved(MockHttpServletResponse pending) throws Exception {
        assertThat(pending.getStatus()).as(pending.getContentAsString()).isEqualTo(202);
        Number commandId = JsonPath.read(pending.getContentAsString(), "$.commandId");
        MockHttpServletResponse approval = call(post("/audits/" + commandId.longValue() + "/approve"), "secadmin", null);
        assertThat(approval.getStatus()).as(approval.getContentAsString()).isEqualTo(200);
    }

    private int statusOf(String user, String path) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                .with(signedIn(user, "password"))).andReturn().getResponse().getStatus();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String user, String json)
            throws Exception {
        request.with(signedIn(user, "password"));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
