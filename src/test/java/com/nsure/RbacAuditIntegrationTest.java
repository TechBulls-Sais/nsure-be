package com.nsure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nsure.audit.CommandSource;
import com.nsure.audit.CommandSourceRepository;
import com.nsure.audit.CommandStatus;
import com.nsure.security.domain.AppUser;
import com.nsure.security.domain.Office;
import com.nsure.security.domain.Permission;
import com.nsure.security.repo.AppUserRepository;
import com.nsure.security.repo.OfficeRepository;
import com.nsure.security.repo.PermissionRepository;
import com.nsure.security.repo.RoleRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class RbacAuditIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PermissionRepository permissions;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private CommandSourceRepository commands;

    @Test
    void healthIsPublicAndAnonymousApiIsUnauthorized() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/roles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void seedProvidesSystemUserAndPermissionCatalog() {
        AppUser system = users.findByUsername("system").orElseThrow();
        assertThat(system.getId()).isEqualTo(1L);
        assertThat(system.isEnabled()).isFalse();

        Permission updateUser = permissions.findByCodeIgnoreCase("UPDATE_USER").orElseThrow();
        assertThat(updateUser.isCanMakerChecker()).isTrue();
        assertThat(permissions.findByCodeIgnoreCase("ALL_FUNCTIONS")).isPresent();
        assertThat(permissions.findByCodeIgnoreCase("ALL_FUNCTIONS_READ")).isPresent();
        assertThat(permissions.findByCodeIgnoreCase("CHECKER_SUPER_USER")).isPresent();
        assertThat(permissions.findByCodeIgnoreCase("READ_AUDIT")).isPresent();
        assertThat(permissions.findByCodeIgnoreCase("CREATE_CLIENT")).isPresent();
    }

    @Test
    void adminWriteIsAuditedAndStampsCreatedBy() throws Exception {
        String suffix = suffix();
        long roleId = createRole("Ops " + suffix, "Operations");

        var role = roles.findById(roleId).orElseThrow();
        AppUser admin = users.findByUsername("admin").orElseThrow();
        assertThat(role.getCreatedBy()).isEqualTo(admin.getId());
        assertThat(role.getCreatedDate()).isNotNull();
        assertThat(role.getLastModifiedBy()).isEqualTo(admin.getId());

        CommandSource audit = commands.findAll().stream()
                .filter(command -> roleId == command.getResourceId()
                        && "ROLE".equals(command.getEntityName())
                        && "CREATE".equals(command.getActionName()))
                .findFirst()
                .orElseThrow();
        assertThat(audit.getStatus()).isEqualTo(CommandStatus.PROCESSED);
        assertThat(audit.getMakerId()).isEqualTo(admin.getId());
        assertThat(audit.getResultStatus()).isEqualTo(200);
    }

    @Test
    void passwordIsRedactedInTheCommandLog() throws Exception {
        long roleId = createRole("Empty " + suffix(), "No permissions");
        String password = "password1";
        long userId = createUser("redact" + suffix(), password, headOfficeId(), roleId);

        CommandSource audit = command("USER", "CREATE", userId);
        assertThat(audit.getCommandAsJson()).doesNotContain(password);
        assertThat(audit.getCommandAsJson()).contains("\"password\":\"***\"");
        assertThat(audit.getStatus()).isEqualTo(CommandStatus.PROCESSED);
    }

    @Test
    void missingPermissionIsForbiddenAndDoesNotWriteACommand() throws Exception {
        long roleId = createRole("No rights " + suffix(), "Cannot write");
        String username = "norights" + suffix();
        createUser(username, "password1", headOfficeId(), roleId);
        long before = commands.count();

        mvc.perform(post("/roles")
                        .with(httpBasic(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Should not exist\",\"description\":\"nope\"}"))
                .andExpect(status().isForbidden());

        assertThat(commands.count()).isEqualTo(before);
        assertThat(roles.findByName("Should not exist")).isEmpty();
    }

    @Test
    void disabledRoleStopsGrantingItsPermissions() throws Exception {
        String suffix = suffix();
        long roleId = createRole("Grant " + suffix, "Can create roles");
        assign(roleId, "CREATE_ROLE", true);
        String username = "granter" + suffix;
        createUser(username, "password1", headOfficeId(), roleId);

        mvc.perform(post("/roles")
                        .with(httpBasic(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Created " + suffix + "\",\"description\":\"ok\"}"))
                .andExpect(status().isOk());

        mvc.perform(put("/roles/" + roleId)
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"disabled\":true}"))
                .andExpect(status().isOk());

        long before = commands.count();
        mvc.perform(post("/roles")
                        .with(httpBasic(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Blocked " + suffix + "\",\"description\":\"nope\"}"))
                .andExpect(status().isForbidden());
        assertThat(commands.count()).isEqualTo(before);
    }

    @Test
    void idempotencyKeyReplaysTheFirstResult() throws Exception {
        String suffix = suffix();
        String key = "role-" + suffix;
        long first = createRole("First " + suffix, "First role", key);
        long second = createRole("Second " + suffix, "Second role", key);

        assertThat(second).isEqualTo(first);
        assertThat(roles.findByName("Second " + suffix)).isEmpty();
        assertThat(roles.findByName("First " + suffix)).isPresent();
    }

    @Test
    void failedCommandCanBeRetriedExplicitly() throws Exception {
        String suffix = suffix();
        String key = "user-" + suffix;
        mvc.perform(json(post("/users"), "admin", "{\"username\":\"retry" + suffix + "\"}")
                        .header("Idempotency-Key", key))
                .andExpect(status().isBadRequest());

        mvc.perform(json(post("/users"), "admin",
                        "{\"username\":\"retry" + suffix + "\",\"password\":\"password1\",\"officeId\":" + headOfficeId() + "}")
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict());

        mvc.perform(json(post("/users"), "admin",
                        "{\"username\":\"retry" + suffix + "\",\"password\":\"password1\",\"officeId\":" + headOfficeId() + "}")
                        .header("Idempotency-Key", key)
                        .header("Idempotency-Retry", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").isNumber());

        assertThat(users.findByUsername("retry" + suffix)).isPresent();
    }

    @Test
    void makerCheckerRollsBackUntilADifferentCheckerApproves() throws Exception {
        String suffix = suffix();
        long makerRoleId = createRole("Maker " + suffix, "Updates users");
        assign(makerRoleId, "UPDATE_USER", true);
        assign(makerRoleId, "CHECKER_UPDATE_USER", true);
        String maker = "maker" + suffix;
        createUser(maker, "password1", headOfficeId(), makerRoleId);
        long targetId = createUser("target" + suffix, "password1", headOfficeId(), makerRoleId);

        String awaiting = mvc.perform(json(put("/users/" + targetId), maker, "{\"enabled\":false}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        long commandId = objectMapper.readTree(awaiting).get("commandId").asLong();

        assertThat(users.findById(targetId).orElseThrow().isEnabled()).isTrue();
        assertThat(commands.findById(commandId).orElseThrow().getStatus()).isEqualTo(CommandStatus.AWAITING_APPROVAL);

        mvc.perform(post("/audits/" + commandId + "/approve").with(httpBasic(maker, "password1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Maker cannot check their own command"));

        mvc.perform(post("/audits/" + commandId + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resourceId").value(targetId));

        assertThat(users.findById(targetId).orElseThrow().isEnabled()).isFalse();
        CommandSource approved = commands.findById(commandId).orElseThrow();
        assertThat(approved.getStatus()).isEqualTo(CommandStatus.PROCESSED);
        assertThat(approved.getCheckerId()).isEqualTo(users.findByUsername("admin").orElseThrow().getId());
        assertThat(approved.getCheckedOn()).isNotNull();
    }

    @Test
    void branchUserCannotSeeHeadOfficeAudits() throws Exception {
        String suffix = suffix();
        long auditedRoleId = createRole("Visible " + suffix, "Created at head office");
        CommandSource headOfficeCommand = command("ROLE", "CREATE", auditedRoleId);

        long auditorRoleId = createRole("Auditor " + suffix, "Branch audit reader");
        assign(auditorRoleId, "READ_AUDIT", true);
        String auditor = "auditor" + suffix;
        createUser(auditor, "password1", branchOfficeId(), auditorRoleId);

        mvc.perform(get("/audits/" + headOfficeCommand.getId()).with(httpBasic(auditor, "password1")))
                .andExpect(status().isForbidden());

        String page = mvc.perform(get("/audits")
                        .param("entityName", "ROLE")
                        .param("actionName", "CREATE")
                        .param("resourceId", String.valueOf(auditedRoleId))
                        .with(httpBasic(auditor, "password1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(page).get("content")).isEmpty();

        mvc.perform(get("/audits/" + headOfficeCommand.getId()).with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(headOfficeCommand.getId()))
                .andExpect(jsonPath("$.status").value("PROCESSED"));
    }

    private long createRole(String name, String description) throws Exception {
        return createRole(name, description, null);
    }

    private long createRole(String name, String description, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = json(post("/roles"), "admin",
                "{\"name\":\"" + name + "\",\"description\":\"" + description + "\"}");
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("resourceId").asLong();
    }

    private void assign(long roleId, String permission, boolean selected) throws Exception {
        mvc.perform(json(put("/roles/" + roleId + "/permissions"), "admin",
                        "{\"permissions\":{\"" + permission + "\":" + selected + "}}"))
                .andExpect(status().isOk());
    }

    private long createUser(String username, String password, long officeId, long roleId) throws Exception {
        String body = mvc.perform(json(post("/users"), "admin", """
                        {"username":"%s","password":"%s","officeId":%d,"roleIds":[%d]}
                        """.formatted(username, password, officeId, roleId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("resourceId").asLong();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String username, String json) {
        String password = "admin".equals(username) ? "password" : "password1";
        return request.with(httpBasic(username, password))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private CommandSource command(String entity, String action, long resourceId) {
        return commands.findAll().stream()
                .filter(command -> entity.equals(command.getEntityName())
                        && action.equals(command.getActionName())
                        && command.getResourceId() != null
                        && command.getResourceId() == resourceId)
                .findFirst()
                .orElseThrow();
    }

    private long headOfficeId() {
        return officeId("Head Office");
    }

    private long branchOfficeId() {
        return officeId("Gaborone Branch");
    }

    private long officeId(String name) {
        return offices.findAll().stream()
                .filter(office -> name.equals(office.getName()))
                .map(Office::getId)
                .findFirst()
                .orElseThrow();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
