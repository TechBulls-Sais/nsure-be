package com.nsure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nsure.audit.repository.CommandSourceRepository;
import com.nsure.security.domain.AppUser;
import com.nsure.security.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RbacAndAuditIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CommandSourceRepository commands;

    @Test
    void healthIsPublicAndApiRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/roles")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/roles").with(httpBasic("system", "nope"))).andExpect(status().isUnauthorized());

        AppUser system = users.findByUsername("system").orElseThrow();
        assertThat(system.getId()).isEqualTo(1L);
        assertThat(system.isEnabled()).isFalse();
    }

    @Test
    void createRoleStampsTheSignedInUser() throws Exception {
        String name = "Officer " + suffix();
        JsonNode created = send("admin", post("/roles").content(roleBody(name)), 201);
        JsonNode role = send("admin", get("/roles/" + created.get("resourceId").asLong()), 200);

        assertThat(role.get("name").asText()).isEqualTo(name);
        assertThat(role.get("createdBy").asLong()).isEqualTo(userId("admin"));
        assertThat(role.get("createdDate").isNull()).isFalse();
    }

    @Test
    void duplicateRoleIsAuditedAsAnError() throws Exception {
        String name = "Duplicate " + suffix();
        send("admin", post("/roles").content(roleBody(name)), 201);
        JsonNode failed = send("admin", post("/roles").content(roleBody(name)), 409);

        assertThat(failed.get("commandId").isNull()).isFalse();
        JsonNode audit = send("admin", get("/audits/" + failed.get("commandId").asLong()), 200);
        assertThat(audit.get("status").asText()).isEqualTo("ERROR");
        assertThat(audit.get("resultStatus").asInt()).isEqualTo(409);
    }

    @Test
    void missingPermissionDoesNotWriteACommand() throws Exception {
        long officeId = officeId("Head Office");
        String username = "empty" + suffix();
        send("admin", post("/users").content(userBody(username, officeId, new long[0])), 201);

        long before = commands.count();
        send(username, post("/roles").content(roleBody("Denied " + suffix())), 403);
        assertThat(commands.count()).isEqualTo(before);
    }

    @Test
    void disabledRoleGrantsNothingOnTheNextRequest() throws Exception {
        long roleId = createRole("Reader " + suffix());
        send("admin", put("/roles/" + roleId + "/permissions").content("{\"permissions\":{\"READ_OFFICE\":true}}"), 200);
        long officeId = officeId("Head Office");
        String username = "reader" + suffix();
        send("admin", post("/users").content(userBody(username, officeId, new long[] {roleId})), 201);

        send(username, get("/offices"), 200);
        send("admin", put("/roles/" + roleId).content("{\"disabled\":true}"), 200);
        send(username, get("/offices"), 403);
    }

    @Test
    void makerCheckerRollsBackUntilADifferentUserApproves() throws Exception {
        long roleId = createRole("Clerk " + suffix());
        send("admin", put("/roles/" + roleId + "/permissions").content("{\"permissions\":{\"UPDATE_USER\":true}}"), 200);
        long officeId = officeId("Branch Office");
        String clerk = "clerk" + suffix();
        String target = "target" + suffix();
        send("admin", post("/users").content(userBody(clerk, officeId, new long[] {roleId})), 201);
        JsonNode createdTarget = send("admin", post("/users").content(userBody(target, officeId, new long[0])), 201);
        long targetId = createdTarget.get("resourceId").asLong();

        String renamed = "renamed" + suffix();
        send(clerk, put("/users/" + targetId).content("{\"username\":\"" + renamed + "\"}"), 403);
        assertThat(send("admin", get("/users/" + targetId), 200).get("username").asText()).isEqualTo(target);
    }

    @Test
    void selfUpdateSkipsMakerChecker() throws Exception {
        long roleId = createRole("Self " + suffix());
        send("admin", put("/roles/" + roleId + "/permissions").content("{\"permissions\":{\"UPDATE_USER\":true}}"), 200);
        long officeId = officeId("Branch Office");
        String clerk = "self" + suffix();
        JsonNode created = send("admin", post("/users").content(userBody(clerk, officeId, new long[] {roleId})), 201);
        String renamed = "selfrenamed" + suffix();

        send(
                clerk,
                put("/users/" + created.get("resourceId").asLong()).content("{\"username\":\"" + renamed + "\"}"),
                403);

        assertThat(users.findById(created.get("resourceId").asLong()).orElseThrow().getUsername()).isEqualTo(clerk);
    }

    @Test
    void idempotencyReplaysSuccessAndRetriesErrors() throws Exception {
        String name = "Idem " + suffix();
        String key = "idem-" + suffix();
        JsonNode first = send("admin", post("/roles").header("Idempotency-Key", key).content(roleBody(name)), 201);
        JsonNode replay = send("admin", post("/roles").header("Idempotency-Key", key).content(roleBody(name)), 200);
        assertThat(replay.get("resourceId").asLong()).isEqualTo(first.get("resourceId").asLong());
        assertThat(replay.get("commandId").asLong()).isEqualTo(first.get("commandId").asLong());

        String retryKey = "retry-" + suffix();
        JsonNode failed = send("admin", post("/roles").header("Idempotency-Key", retryKey).content(roleBody(name)), 409);
        String replacement = "Idem2 " + suffix();
        JsonNode retried = send(
                "admin",
                post("/roles")
                        .header("Idempotency-Key", retryKey)
                        .header("Idempotency-Retry", "true")
                        .content(roleBody(replacement)),
                201);
        assertThat(retried.get("commandId").asLong()).isEqualTo(failed.get("commandId").asLong());
        assertThat(retried.get("status").asText()).isEqualTo("PROCESSED");
    }

    @Test
    void commandLogStoresPasswordsRedacted() throws Exception {
        long officeId = officeId("Head Office");
        String username = "secret" + suffix();
        JsonNode created = send("admin", post("/users").content(userBody(username, officeId, new long[0])), 201);

        JsonNode audit = send("admin", get("/audits/" + created.get("commandId").asLong()), 200);
        assertThat(audit.get("command").get("password").asText()).isEqualTo("***");
        assertThat(audit.get("command").get("username").asText()).isEqualTo(username);
        assertThat(audit.get("status").asText()).isEqualTo("PROCESSED");
    }

    @Test
    void branchUserSeesOnlyAuditsInTheOfficeHierarchy() throws Exception {
        long auditRole = createRole("Branch audit " + suffix());
        send("admin", put("/roles/" + auditRole + "/permissions").content("{\"permissions\":{\"READ_AUDIT\":true}}"), 200);
        long branchId = officeId("Branch Office");
        String auditor = "branch" + suffix();
        send("admin", post("/users").content(userBody(auditor, branchId, new long[] {auditRole})), 201);

        JsonNode roleCommand = send("admin", post("/roles").content(roleBody("Global " + suffix())), 201);
        JsonNode userCommand = send(
                "admin",
                post("/users").content(userBody("branchuser" + suffix(), branchId, new long[0])),
                201);

        send(auditor, get("/audits/" + roleCommand.get("commandId").asLong()), 403);
        JsonNode visible = send(auditor, get("/audits/" + userCommand.get("commandId").asLong()), 200);
        assertThat(visible.get("officeId").asLong()).isEqualTo(branchId);

        JsonNode page = send(auditor, get("/audits?size=200"), 200);
        for (JsonNode item : page.get("items")) {
            assertThat(item.get("officeId").isNull()).isFalse();
        }
    }

    @Test
    void allFunctionsReadCanSearchAuditsButCannotWrite() throws Exception {
        long roleId = createRole("Read all " + suffix());
        send("admin", put("/roles/" + roleId + "/permissions")
                .content("{\"permissions\":{\"ALL_FUNCTIONS_READ\":true}}"), 200);
        String username = "readonly" + suffix();
        send("admin", post("/users").content(userBody(username, officeId("Head Office"), new long[] {roleId})), 201);

        send(username, get("/audits"), 200);
        send(username, get("/roles"), 403);
        send(username, post("/roles").content(roleBody("Blocked " + suffix())), 403);
    }

    @Test
    void superAdminManagesRolesPermissionsAndUsers() throws Exception {
        JsonNode me = send("admin", get("/me"), 200);
        assertThat(me.get("superAdmin").asBoolean()).isTrue();

        String code = "READ_DEMO_" + suffix();
        JsonNode createdPermission = send("admin", post("/permissions").content(
                "{\"grouping\":\"custom\",\"code\":\"" + code + "\",\"entityName\":\"DEMO\",\"actionName\":\"READ\"}"), 201);
        long permissionId = createdPermission.get("resourceId").asLong();
        JsonNode viewed = send("admin", get("/permissions/" + permissionId), 200);
        assertThat(viewed.get("code").asText()).isEqualTo(code);
        assertThat(viewed.get("entityName").asText()).isEqualTo("DEMO");
        assertThat(viewed.get("actionName").asText()).isEqualTo("READ");
        send("admin", put("/permissions/" + permissionId).content(
                "{\"grouping\":\"workspace\",\"entityName\":\"DEMO\",\"actionName\":\"READ\"}"), 200);
        assertThat(send("admin", get("/permissions/" + permissionId), 200).get("grouping").asText())
                .isEqualTo("workspace");
        send("admin", delete("/permissions/" + permissionId), 200);
        send("admin", get("/permissions/" + permissionId), 404);

        String roleName = "Desk " + suffix();
        long roleId = createRole(roleName);
        send("admin", put("/roles/" + roleId).content("{\"description\":\"Desk access\"}"), 200);
        send("admin", put("/roles/" + roleId + "/permissions").content("{\"permissions\":{\"READ_OFFICE\":true}}"), 200);
        assertThat(send("admin", get("/roles/" + roleId), 200).get("permissions").toString()).contains("READ_OFFICE");

        long officeId = officeId("Head Office");
        String username = "member" + suffix();
        JsonNode createdUser = send("admin", post("/users").content(userBody(username, officeId, new long[] {roleId})), 201);
        long userId = createdUser.get("resourceId").asLong();
        JsonNode stored = send("admin", get("/users/" + userId), 200);
        assertThat(stored.get("roles").toString()).contains(roleName);
        assertThat(stored.get("roleIds").toString()).contains(Long.toString(roleId));

        send(username, get("/me"), 200);
        assertThat(send(username, get("/me"), 200).get("superAdmin").asBoolean()).isFalse();
        send(username, get("/users"), 403);

        send("admin", delete("/users/" + userId), 200);
        send("admin", delete("/roles/" + roleId), 200);
        long superRoleId = 0;
        for (JsonNode role : send("admin", get("/roles"), 200)) {
            if ("Super user".equals(role.get("name").asText())) {
                superRoleId = role.get("id").asLong();
            }
        }
        send("admin", delete("/roles/" + superRoleId), 400);
    }

    private long createRole(String name) throws Exception {
        return send("admin", post("/roles").content(roleBody(name)), 201).get("resourceId").asLong();
    }

    private long officeId(String name) throws Exception {
        JsonNode offices = send("admin", get("/offices"), 200);
        for (JsonNode office : offices) {
            if (name.equals(office.get("name").asText())) {
                return office.get("id").asLong();
            }
        }
        throw new AssertionError("Missing office " + name);
    }

    private long userId(String username) throws Exception {
        for (JsonNode user : send("admin", get("/users"), 200)) {
            if (username.equals(user.get("username").asText())) {
                return user.get("id").asLong();
            }
        }
        throw new AssertionError("Missing user " + username);
    }

    private JsonNode send(String username, MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(request
                        .with(httpBasic(username, "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().is(expectedStatus))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        return json.readTree(body);
    }

    private String roleBody(String name) {
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        body.put("description", "Created by the RBAC test");
        return body.toString();
    }

    private String userBody(String username, long officeId, long[] roleIds) {
        ObjectNode body = json.createObjectNode();
        body.put("username", username);
        body.put("password", "password");
        body.put("officeId", officeId);
        body.put("enabled", true);
        body.putArray("roleIds");
        for (long roleId : roleIds) {
            body.withArray("roleIds").add(roleId);
        }
        return body.toString();
    }

    private static String suffix() {
        return Long.toString(System.nanoTime());
    }
}
