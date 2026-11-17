package com.nsure.security;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Access recertification (NS-SH11-007): a reviewer keeps or flags every login with access, never their own; the
 * review closes only when complete, and flagged access is alerted to the identity administrators.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AccessReviewApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyLoginWithAccessIsKeptOrFlaggedBySomeoneElse() throws Exception {
        String started = ok(post("/admin/access-reviews"), "compliance", null);
        Number id = JsonPath.read(started, "$.id");
        String ref = JsonPath.read(started, "$.recordRef");
        List<String> usernames = JsonPath.read(started, "$.items[*].username");
        assertThat(usernames).contains("admin", "compliance", "assessor").doesNotContain("system");
        assertThat(call(post("/admin/access-reviews"), "compliance", null).getStatus()).isEqualTo(409);
        assertThat(call(post("/admin/access-reviews"), "onboarding", null).getStatus()).isEqualTo(403);
        assertThat(call(get("/admin/access-reviews/" + id), "onboarding", null).getStatus()).isEqualTo(403);

        List<Map<String, Object>> items = JsonPath.read(started, "$.items");
        String base = "/admin/access-reviews/" + id + "/items/";
        for (Map<String, Object> item : items) {
            String user = (String) item.get("username");
            String path = base + item.get("id") + "/decision";
            if ("compliance".equals(user)) {
                // Nobody recertifies their own access.
                assertThat(call(post(path), "compliance", "{\"decision\":\"KEEP\"}").getStatus()).isEqualTo(403);
                ok(post(path), "secadmin", "{\"decision\":\"KEEP\"}");
            } else if ("onboarding".equals(user)) {
                assertThat(call(post(path), "compliance", "{\"decision\":\"REVOKE\"}").getStatus()).isEqualTo(422);
                assertThat(call(post("/admin/access-reviews/" + id + "/close"), "compliance", null).getStatus())
                        .isEqualTo(422);
                ok(post(path), "compliance", "{\"decision\":\"REVOKE\",\"note\":\"Left the onboarding team\"}");
            } else {
                ok(post(path), "compliance", "{\"decision\":\"KEEP\"}");
            }
        }
        String closed = ok(post("/admin/access-reviews/" + id + "/close"), "compliance", null);
        assertThat((String) JsonPath.read(closed, "$.status")).isEqualTo("CLOSED");
        assertThat((String) JsonPath.read(closed, "$.summary")).endsWith("1 to revoke");

        // Identity administrators are told; the reviewer changed nobody's access.
        assertThat(ok(get("/me/alerts"), "admin", null)).contains("Access review " + ref + ": remove onboarding");
        assertThat(ok(get("/me/alerts"), "compliance", null)).doesNotContain("Access review " + ref);
        assertThat(call(get("/portfolio/customers"), "onboarding", null).getStatus()).isEqualTo(200);
        assertThat(ok(get("/admin/access-reviews"), "compliance", null)).contains(ref);
        assertThat(ok(get("/audits/evidence").param("entityRef", ref), "compliance", null))
                .contains("Access review started", "Access review closed");
    }

    private String ok(MockHttpServletRequestBuilder request, String user, String body) throws Exception {
        MockHttpServletResponse response = call(request, user, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String user, String body)
            throws Exception {
        request.with(signedIn(user, "password"));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
