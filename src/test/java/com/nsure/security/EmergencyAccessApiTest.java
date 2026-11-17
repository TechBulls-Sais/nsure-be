package com.nsure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Emergency access (Nsure Life NS-SH11-003): "time-limited, justified, alerted and reviewed". Break-glass codes are held
 * by no role, the administrator included; they come only through a grant from someone else, for at most 8 hours, with
 * a reason, raising an alert to auditors, and reviewed afterwards by a third person.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-emergency-access;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class EmergencyAccessApiTest {

    private static final String CONTROL = "/admin/controls/claim.four_eyes";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void breakGlassAccessIsTimeLimitedJustifiedAlertedAndReviewed() throws Exception {
        // Nobody holds the break-glass code by role.
        assertThat(call(put(CONTROL), "actuary", "{\"enabled\":false}").getStatus()).isEqualTo(403);
        assertThat(call(put(CONTROL), "admin", "{\"enabled\":false}").getStatus()).isEqualTo(403);

        // Guard rails on the grant itself.
        assertThat(grant("admin", "admin", "UPDATE_CONTROL", 2, "Self grant during an incident").getStatus())
                .isEqualTo(403);
        assertThat(grant("admin", "actuary", "READ_CLAIM", 2, "Not a break-glass code at all").getStatus())
                .isEqualTo(422);
        assertThat(grant("admin", "actuary", "UPDATE_CONTROL", 9, "Longer than a working day").getStatus())
                .isEqualTo(422);
        assertThat(grant("admin", "actuary", "UPDATE_CONTROL", 2, "short").getStatus()).isEqualTo(422);
        assertThat(grant("admin", "customer", "UPDATE_CONTROL", 2, "Customers never get staff powers").getStatus())
                .isEqualTo(422);
        assertThat(grant("actuary", "assessor", "UPDATE_CONTROL", 2, "Actuary is not an identity admin").getStatus())
                .isEqualTo(403);

        MockHttpServletResponse granted = grant("admin", "actuary", "UPDATE_CONTROL", 2,
                "Incident 117: STP must be paused tonight");
        assertThat(granted.getStatus()).as(granted.getContentAsString()).isEqualTo(201);
        Number id = JsonPath.read(granted.getContentAsString(), "$.id");
        assertThat((String) JsonPath.read(granted.getContentAsString(), "$.status")).isEqualTo("ACTIVE");
        assertThat(grant("admin", "actuary", "UPDATE_CONTROL", 1, "A second overlapping grant").getStatus())
                .isEqualTo(409);

        // While active it counts as a held permission: the gate opens, the control's own rules still apply.
        String me = body(get("/me"), "actuary");
        assertThat(me).contains("\"UPDATE_CONTROL\"", "\"emergencyAccess\":[{\"permission\":\"UPDATE_CONTROL\"");
        assertThat(call(put(CONTROL), "actuary", "{\"enabled\":false}").getStatus()).isEqualTo(422);

        // Alerted: auditors (READ_AUDIT) see it; a user without READ_AUDIT does not.
        assertThat(body(get("/me/alerts"), "compliance")).contains("Emergency access granted: UPDATE_CONTROL");
        assertThat(body(get("/me/alerts"), "assessor")).doesNotContain("Emergency access granted");

        // Reviewed only once it has ended, and never by the grantee or the granter.
        assertThat(call(post("/admin/emergency-access/" + id + "/review"), "compliance",
                "{\"note\":\"Checked the control history\"}").getStatus()).isEqualTo(409);
        assertThat(call(post("/admin/emergency-access/" + id + "/revoke"), "actuary", null).getStatus())
                .isEqualTo(403);
        MockHttpServletResponse revoked = call(post("/admin/emergency-access/" + id + "/revoke"), "secadmin", null);
        assertThat(revoked.getStatus()).as(revoked.getContentAsString()).isEqualTo(200);
        assertThat((String) JsonPath.read(revoked.getContentAsString(), "$.revokedBy")).isEqualTo("secadmin");
        assertThat(call(put(CONTROL), "actuary", "{\"enabled\":false}").getStatus()).isEqualTo(403);
        assertThat(call(post("/admin/emergency-access/" + id + "/revoke"), "admin", null).getStatus())
                .isEqualTo(409);
        assertThat(body(get("/me/alerts"), "compliance")).contains("Emergency access revoked: UPDATE_CONTROL");

        // The granter's role does not hold the review code; Compliance reviews.
        assertThat(call(post("/admin/emergency-access/" + id + "/review"), "admin",
                "{\"note\":\"Looks fine to me\"}").getStatus()).isEqualTo(403);
        MockHttpServletResponse reviewed = call(post("/admin/emergency-access/" + id + "/review"), "compliance",
                "{\"note\":\"Checked the control history: STP paused and restored\"}");
        assertThat(reviewed.getStatus()).as(reviewed.getContentAsString()).isEqualTo(200);
        assertThat((String) JsonPath.read(reviewed.getContentAsString(), "$.reviewedBy")).isEqualTo("compliance");
        assertThat(call(post("/admin/emergency-access/" + id + "/review"), "compliance",
                "{\"note\":\"Second review\"}").getStatus()).isEqualTo(409);

        // The register: identity admins and auditors read it; others do not.
        List<Integer> ids = JsonPath.read(body(get("/admin/emergency-access"), "compliance"), "$[*].id");
        assertThat(ids).contains(id.intValue());
        assertThat(call(get("/admin/emergency-access"), "actuary", null).getStatus()).isEqualTo(403);
    }

    private MockHttpServletResponse grant(String granter, String username, String permission, int hours, String reason)
            throws Exception {
        return call(post("/admin/emergency-access"), granter, "{\"username\":\"" + username + "\",\"permission\":\""
                + permission + "\",\"hours\":" + hours + ",\"reason\":\"" + reason + "\"}");
    }

    private String body(MockHttpServletRequestBuilder request, String user) throws Exception {
        MockHttpServletResponse response = call(request, user, null);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
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
