package com.nsure.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The API access matrix in ApiAccessRules, exercised through the real security filter chain. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-api-access;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ApiAccessIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Test
    void superUserRoleAloneOpensEveryApi() throws Exception {
        // No capability roles and no other app roles: only the "Super user" role.
        String username = createUser("super" + suffix(), roleId("Super user"));
        for (String path : new String[] {
                "/portfolio/claims", "/portfolio/customers", "/portfolio/policies", "/portfolio/applications",
                "/portfolio/product-configs", "/admin/rate-tables", "/admin/controls", "/audits",
                "/users", "/roles", "/permissions", "/offices"}) {
            send(username, get(path), 200);
        }
    }

    @Test
    void superAdminRoleAloneOpensEveryApi() throws Exception {
        String username = createUser("superadmin" + suffix(), roleId("Super Admin"));
        send(username, get("/admin/rate-tables"), 200);
        send(username, get("/admin/controls"), 200);
        send(username, get("/portfolio/claims"), 200);
        send(username, get("/users"), 200);
    }

    @Test
    void claimsAssessorReachesClaimsButNotAdministration() throws Exception {
        send("assessor", get("/portfolio/claims"), 200);
        send("assessor", get("/portfolio/policies"), 200);
        send("assessor", get("/portfolio/customers"), 200);
        send("assessor", get("/admin/controls"), 200);
        send("assessor", get("/admin/rate-tables"), 403);
        send("assessor", get("/portfolio/product-configs"), 403);
        send("assessor", get("/audits"), 403);
        send("assessor", get("/users"), 403);
        send("assessor", get("/roles"), 403);
    }

    @Test
    void onboardingSpecialistReachesCustomersOnly() throws Exception {
        send("onboarding", get("/portfolio/customers"), 200);
        send("onboarding", get("/portfolio/claims"), 403);
        send("onboarding", get("/portfolio/policies"), 403);
        send("onboarding", post("/portfolio/policies").content("{}"), 403);
        send("onboarding", post("/portfolio/claims/CLM-1/approve").content("{}"), 403);
        send("onboarding", get("/admin/rate-tables"), 403);
    }

    @Test
    void customerReachesThePortalOnly() throws Exception {
        send("customer", get("/me"), 200);
        send("customer", get("/portfolio/shop"), 200);
        send("customer", get("/portfolio/claims"), 403);
        send("customer", get("/portfolio/products"), 403);
    }

    @Test
    void anyRoleGrantedAPermissionGetsItsEndpoints() throws Exception {
        long roleId = send("admin", post("/roles").content(roleBody("Claims Viewer " + suffix())), 201)
                .get("resourceId").asLong();
        String username = createUser("viewer" + suffix(), roleId);
        send(username, get("/portfolio/claims"), 403);

        send("admin", put("/roles/" + roleId + "/permissions").content("{\"permissions\":{\"READ_CLAIM\":true}}"), 200);
        send(username, get("/portfolio/claims"), 200);
        send(username, get("/portfolio/history/claim/CLM-1"), 200);
        send(username, get("/portfolio/policies"), 403);
        send(username, post("/portfolio/claims/CLM-1/assess").content("{}"), 403);
    }

    @Test
    void anEndpointWithNoRuleIsDenied() throws Exception {
        send("admin", get("/no-such-endpoint"), 403);
        mockMvc.perform(get("/no-such-endpoint")).andExpect(status().isUnauthorized());
    }

    private String createUser(String username, long roleId) throws Exception {
        long officeId = send("admin", get("/offices"), 200).get(0).get("id").asLong();
        ObjectNode body = json.createObjectNode();
        body.put("username", username);
        body.put("password", "password");
        body.put("officeId", officeId);
        body.put("enabled", true);
        body.putArray("roleIds").add(roleId);
        send("admin", post("/users").content(body.toString()), 201);
        return username;
    }

    private long roleId(String name) throws Exception {
        for (JsonNode role : send("admin", get("/roles"), 200)) {
            if (name.equals(role.get("name").asText())) {
                return role.get("id").asLong();
            }
        }
        throw new AssertionError("Missing role " + name);
    }

    private String roleBody(String name) {
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        body.put("description", "Created by the API access test");
        return body.toString();
    }

    private JsonNode send(String username, MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request
                        .with(httpBasic(username, "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
