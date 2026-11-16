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
        String username = "empty" + suffix();
        send("admin", post("/users").content(userBody(username, new long[0])), 201);

        long before = commands.count();
        send(username, post("/roles").content(roleBody("Denied " + suffix())), 403);
        assertThat(commands.count()).isEqualTo(before);
    }

    @Test
    void disabledRoleGrantsNothingOnTheNextRequest() throws Exception {
        long roleId = createRole("Reader " + suffix());
        grantRole(roleId, "{\"permissions\":{\"READ_AUDIT\":true}}");
        String username = "reader" + suffix();
        send("admin", post("/users").content(userBody(username, new long[] {roleId})), 201);

        send(username, get("/audits"), 200);
        approve(send("admin", put("/roles/" + roleId).content("{\"disabled\":true}"), 202));
        send(username, get("/audits"), 403);
    }

    @Test
    void makerCheckerRollsBackUntilADifferentUserApproves() throws Exception {
        long roleId = createRole("Clerk " + suffix());
        grantRole(roleId, "{\"permissions\":{\"UPDATE_USER\":true}}");
        String clerk = "clerk" + suffix();
        String target = "target" + suffix();
        send("admin", post("/users").content(userBody(clerk, new long[] {roleId})), 201);
        JsonNode createdTarget = send("admin", post("/users").content(userBody(target, new long[0])), 201);
        long targetId = createdTarget.get("resourceId").asLong();

        // UPDATE_USER is a maker-checker code (docs/IMPLEMENT_AUDITING.md): the clerk's change is held, not applied.
        String renamed = "renamed" + suffix();
        JsonNode pending = send(clerk, put("/users/" + targetId).content("{\"username\":\"" + renamed + "\"}"), 202);
        assertThat(send("admin", get("/users/" + targetId), 200).get("username").asText()).isEqualTo(target);
        // The maker cannot check their own command; a different user holding CHECKER_UPDATE_USER applies it.
        send(clerk, post("/audits/" + pending.get("commandId").asLong() + "/approve"), 403);
        send("admin", post("/audits/" + pending.get("commandId").asLong() + "/approve"), 200);
        assertThat(send("admin", get("/users/" + targetId), 200).get("username").asText()).isEqualTo(renamed);
    }

    @Test
    void theAdministratorCannotApproveTheirOwnChange() throws Exception {
        // Nsure Life NS-UX-004 / AT-SH10-01: no self-approval for anyone, the administrator included.
        String target = "own" + suffix();
        long targetId = send("admin", post("/users").content(userBody(target, new long[0])), 201)
                .get("resourceId").asLong();
        String renamed = "ownrenamed" + suffix();
        JsonNode pending = send("admin", put("/users/" + targetId).content("{\"username\":\"" + renamed + "\"}"), 202);
        assertThat(send("admin", get("/users/" + targetId), 200).get("username").asText()).isEqualTo(target);

        JsonNode refused = send("admin", post("/audits/" + pending.get("commandId").asLong() + "/approve"), 403);
        assertThat(refused.toString()).contains("Maker cannot check their own command");
        send("admin", post("/audits/" + pending.get("commandId").asLong() + "/reject"), 403);
        send("secadmin", post("/audits/" + pending.get("commandId").asLong() + "/approve"), 200);
        assertThat(send("admin", get("/users/" + targetId), 200).get("username").asText()).isEqualTo(renamed);
    }

    @Test
    void selfUpdateSkipsMakerChecker() throws Exception {
        long roleId = createRole("Self " + suffix());
        grantRole(roleId, "{\"permissions\":{\"UPDATE_USER\":true}}");
        String clerk = "self" + suffix();
        JsonNode created = send("admin", post("/users").content(userBody(clerk, new long[] {roleId})), 201);
        String renamed = "selfrenamed" + suffix();

        // docs/IMPLEMENT_RBAC.md: a user changing their own profile skips maker-checker.
        send(
                clerk,
                put("/users/" + created.get("resourceId").asLong()).content("{\"username\":\"" + renamed + "\"}"),
                200);

        assertThat(users.findById(created.get("resourceId").asLong()).orElseThrow().getUsername()).isEqualTo(renamed);
    }

    @Test
    void idempotencyReplaysSuccessAndRetriesErrors() throws Exception {
        String name = "Idem " + suffix();
        String key = "idem-" + suffix();
        JsonNode first = send("admin", post("/roles").header("Idempotency-Key", key).content(roleBody(name)), 201);
        JsonNode replay = send("admin", post("/roles").header("Idempotency-Key", key).content(roleBody(name)), 200);
        assertThat(replay.get("resourceId").asLong()).isEqualTo(first.get("resourceId").asLong());
        assertThat(replay.get("commandId").asLong()).isEqualTo(first.get("commandId").asLong());
        // NS-API-002: the same key with a changed request is refused, never answered with the old result.
        JsonNode changed = send("admin", post("/roles").header("Idempotency-Key", key)
                .content(roleBody("Changed " + suffix())), 409);
        assertThat(changed.get("message").asText()).contains("different request");
        // A key belongs to its caller: another user's identical key is a separate command.
        String other = "Idem other " + suffix();
        JsonNode theirs = send("secadmin", post("/roles").header("Idempotency-Key", key).content(roleBody(other)), 201);
        assertThat(theirs.get("commandId").asLong()).isNotEqualTo(first.get("commandId").asLong());

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
        String username = "secret" + suffix();
        JsonNode created = send("admin", post("/users").content(userBody(username, new long[0])), 201);

        JsonNode audit = send("admin", get("/audits/" + created.get("commandId").asLong()), 200);
        assertThat(audit.get("command").get("password").asText()).isEqualTo("***");
        assertThat(audit.get("command").get("username").asText()).isEqualTo(username);
        assertThat(audit.get("status").asText()).isEqualTo("PROCESSED");
    }

    @Test
    void anAuditorSeesEveryCommandWithNoOfficeScope() throws Exception {
        // The office/branch tree is gone (Nsure Life NS-SH11-002): READ_AUDIT reads the whole command log.
        long auditRole = createRole("Audit " + suffix());
        grantRole(auditRole, "{\"permissions\":{\"READ_AUDIT\":true}}");
        String auditor = "auditor" + suffix();
        send("admin", post("/users").content(userBody(auditor, new long[] {auditRole})), 201);

        JsonNode roleCommand = send("admin", post("/roles").content(roleBody("Global " + suffix())), 201);
        JsonNode userCommand = send("admin", post("/users").content(userBody("plain" + suffix(), new long[0])), 201);

        send(auditor, get("/audits/" + roleCommand.get("commandId").asLong()), 200);
        JsonNode visible = send(auditor, get("/audits/" + userCommand.get("commandId").asLong()), 200);
        assertThat(visible.has("officeId")).isFalse();
        send("admin", get("/offices"), 403);
    }

    @Test
    void explicitReadGrantsOpenReadsButNoWritesAndWildcardsAreGone() throws Exception {
        long roleId = createRole("Read some " + suffix());
        // ALL_FUNCTIONS_READ is no longer a code (Nsure Life NS-SH11-002: explicit grants only).
        send("admin", put("/roles/" + roleId + "/permissions")
                .content("{\"permissions\":{\"ALL_FUNCTIONS_READ\":true}}"), 422);
        grantRole(roleId, "{\"permissions\":{\"READ_AUDIT\":true,\"READ_ROLE\":true}}");
        String username = "readonly" + suffix();
        send("admin", post("/users").content(userBody(username, new long[] {roleId})), 201);

        send(username, get("/audits"), 200);
        send(username, get("/roles"), 200);
        send(username, get("/users"), 403);
        send(username, post("/roles").content(roleBody("Blocked " + suffix())), 403);
        send(username, put("/admin/controls/claim.four_eyes").content("{\"enabled\":false,\"reason\":\"x\"}"), 403);
    }

    @Test
    void superAdminManagesRolesPermissionsAndUsers() throws Exception {
        JsonNode me = send("admin", get("/me"), 200);
        assertThat(me.get("superAdmin").asBoolean()).isTrue();
        assertThat(me.get("permissions").toString()).doesNotContain("ALL_FUNCTIONS");

        // The vocabulary is closed (spec 04 A-4): codes are relabelled, never created or deleted over the API.
        send("admin", post("/permissions").content(
                "{\"grouping\":\"custom\",\"code\":\"READ_DEMO\",\"entityName\":\"DEMO\",\"actionName\":\"READ\"}"), 403);
        long permissionId = 0;
        long updateRoleId = 0;
        for (JsonNode permission : send("admin", get("/permissions"), 200)) {
            if ("READ_PRODUCT".equals(permission.get("code").asText())) {
                permissionId = permission.get("id").asLong();
            }
            if ("UPDATE_ROLE".equals(permission.get("code").asText())) {
                updateRoleId = permission.get("id").asLong();
            }
        }
        JsonNode viewed = send("admin", get("/permissions/" + permissionId), 200);
        assertThat(viewed.get("entityName").asText()).isEqualTo("PRODUCT");
        assertThat(viewed.get("actionName").asText()).isEqualTo("READ");
        String grouping = viewed.get("grouping").asText();
        send("admin", put("/permissions/" + permissionId).content("{\"grouping\":\"workspace\"}"), 200);
        assertThat(send("admin", get("/permissions/" + permissionId), 200).get("grouping").asText())
                .isEqualTo("workspace");
        send("admin", put("/permissions/" + permissionId).content("{\"grouping\":\"" + grouping + "\"}"), 200);
        send("admin", delete("/permissions/" + permissionId), 403);
        // The checker on identity changes cannot be switched off, or one administrator could act alone.
        send("admin", put("/permissions/" + updateRoleId).content("{\"canMakerChecker\":false}"), 422);

        String roleName = "Desk " + suffix();
        long roleId = createRole(roleName);
        approve(send("admin", put("/roles/" + roleId).content("{\"description\":\"Desk access\"}"), 202));
        grantRole(roleId, "{\"permissions\":{\"READ_PRODUCT\":true}}");
        assertThat(send("admin", get("/roles/" + roleId), 200).get("permissions").toString()).contains("READ_PRODUCT");

        String username = "member" + suffix();
        JsonNode createdUser = send("admin", post("/users").content(userBody(username, new long[] {roleId})), 201);
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
            if ("Super Admin".equals(role.get("name").asText())) {
                superRoleId = role.get("id").asLong();
            }
        }
        send("admin", delete("/roles/" + superRoleId), 422);
    }

    private long createRole(String name) throws Exception {
        return send("admin", post("/roles").content(roleBody(name)), 201).get("resourceId").asLong();
    }

    /** Role permission changes are maker-checker: the admin makes them, a second administrator approves. */
    private void grantRole(long roleId, String body) throws Exception {
        approve(send("admin", put("/roles/" + roleId + "/permissions").content(body), 202));
    }

    private void approve(JsonNode pending) throws Exception {
        send("secadmin", post("/audits/" + pending.get("commandId").asLong() + "/approve"), 200);
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

    private String userBody(String username, long[] roleIds) {
        ObjectNode body = json.createObjectNode();
        body.put("username", username);
        body.put("password", "password");
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
