package com.nsure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every seeded role, signed in over HTTP, against the screens the spec gives it (spec 04 §4 and §5.1). A role either
 * reaches an endpoint (2xx) or is refused with 403; nothing in between. The super admin reaches every screen through explicit grants.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-role-access;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RoleAccessMatrixTest {

    private static final List<String> STAFF = List.of(
            "configurator", "actuary", "onboarding", "assessor", "support", "compliance");

    /** Endpoint → the staff logins that may read it. */
    private static final Map<String, Set<String>> READS = new LinkedHashMap<>();

    static {
        READS.put("/portfolio/claims", Set.of("actuary", "assessor", "support", "compliance"));
        READS.put("/portfolio/policies", Set.of("actuary", "assessor", "support", "compliance"));
        READS.put("/portfolio/applications", Set.of("actuary", "assessor", "support", "compliance"));
        READS.put("/portfolio/receipts", Set.of("actuary", "assessor", "support", "compliance"));
        READS.put("/portfolio/customers", Set.of("onboarding", "support", "compliance"));
        READS.put("/admin/rate-tables", Set.of("configurator", "actuary", "compliance"));
        READS.put("/portfolio/product-configs", Set.of("configurator", "actuary"));
        READS.put("/admin/controls", Set.of("actuary", "assessor", "compliance"));
        READS.put("/admin/controls/stp-dashboard", Set.of("actuary", "assessor", "compliance"));
        READS.put("/audits", Set.of("compliance"));
        READS.put("/reports/management", Set.of("compliance"));
        READS.put("/service/cases", Set.of("support"));
        READS.put("/roles", Set.of());
        READS.put("/users", Set.of());
        READS.put("/permissions", Set.of());
        READS.put("/admin/approval-authorities", Set.of());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void eachRoleReachesExactlyTheScreensTheSpecGivesIt() throws Exception {
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : READS.entrySet()) {
            for (String user : STAFF) {
                int status = status(user, entry.getKey());
                boolean allowed = entry.getValue().contains(user);
                if (allowed && (status < 200 || status >= 300) || !allowed && status != 403) {
                    wrong.add(user + " GET " + entry.getKey() + " → " + status + " (expected "
                            + (allowed ? "2xx" : "403") + ")");
                }
            }
            int admin = status("admin", entry.getKey());
            if (admin < 200 || admin >= 300) {
                wrong.add("admin GET " + entry.getKey() + " → " + admin);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    void aPortalCustomerReachesOnlyThePortal() throws Exception {
        assertThat(status("customer", "/portfolio/shop")).isEqualTo(200);
        assertThat(status("customer", "/me")).isEqualTo(200);
        for (String path : READS.keySet()) {
            assertThat(status("customer", path)).as("customer GET " + path).isEqualTo(403);
        }
        for (String user : STAFF) {
            assertThat(status(user, "/portfolio/shop")).as(user + " GET /portfolio/shop").isEqualTo(403);
        }
    }

    @Test
    void notSignedInIs401NotSignedInWithoutThePermissionIs403() throws Exception {
        assertThat(mockMvc.perform(get("/portfolio/claims")).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(status("onboarding", "/portfolio/claims")).isEqualTo(403);
    }

    @Test
    void controlsWriteIsHeldByNoRoleNotEvenTheAdministrator() throws Exception {
        for (String user : STAFF) {
            int status = mockMvc.perform(put("/admin/controls/claim.four_eyes").with(signedIn(user, "password"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                    .andReturn().getResponse().getStatus();
            assertThat(status).as(user + " PUT /admin/controls/claim.four_eyes").isEqualTo(403);
        }
        // Deny by default (Nsure Life NS-SH11-002): the administrator does not pass by role name. Break-glass codes
        // come only through time-limited emergency access (EmergencyAccessApiTest).
        int admin = mockMvc.perform(put("/admin/controls/claim.four_eyes").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andReturn().getResponse().getStatus();
        assertThat(admin).isEqualTo(403);
    }

    @Test
    void meListsExactlyTheEffectivePermissions() throws Exception {
        String actuary = mockMvc.perform(get("/me").with(signedIn("actuary", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat(actuary).contains("\"PUBLISH_RATE_TABLE\"", "\"READ_CLAIM\"", "\"READ_CONTROL\"")
                .doesNotContain("\"APPROVE_CLAIM\"", "\"UPDATE_CONTROL\"", "\"READ_CUSTOMER\"");
        String customer = mockMvc.perform(get("/me").with(signedIn("customer", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat(customer).contains("\"customerPortal\":true", "\"permissions\":[]");
    }

    private int status(String user, String path) throws Exception {
        return mockMvc.perform(get(path).with(signedIn(user, "password"))).andReturn().getResponse().getStatus();
    }
}
