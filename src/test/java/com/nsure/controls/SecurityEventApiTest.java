package com.nsure.controls;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** SH11: a security event gets an owner who resolves it, and is escalated once if nobody takes it in time. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SecurityEventApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StaffAlertService alerts;

    @Autowired
    private SecurityEventService events;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anEventIsOwnedResolvedAndEscalatedWhenNobodyTakesIt() throws Exception {
        String key = "SECURITY-TEST:" + System.nanoTime();
        alerts.raise("SOLE_CHECKER_BOOTSTRAP", "READ_AUDIT", "Test security event " + key, "Raised by the test", key,
                "system");
        Number id = jdbc.queryForObject("select id from staff_alert where dedup_key = ?", Long.class, key);
        String path = "/admin/security-events/" + id;

        assertThat(JsonPath.<List<String>>read(ok(get("/admin/security-events"), "compliance", null),
                "$[?(@.id == " + id + ")].status")).containsExactly("OPEN");
        assertThat(call(get("/admin/security-events"), "onboarding", null).getStatus()).isEqualTo(403);

        // Not taken in time: escalated once to the security administrators.
        jdbc.update("update staff_alert set created_at = ? where id = ?", LocalDateTime.now().minusHours(10), id);
        assertThat(events.escalate()).isPositive();
        events.escalate();
        assertThat(jdbc.queryForObject("select count(*) from staff_alert where dedup_key = ?", Integer.class,
                "SECURITY_EVENT_ESCALATED:" + id)).isEqualTo(1);
        assertThat(ok(get("/me/alerts"), "admin", null)).contains("Unhandled security event: Test security event");

        // One owner, who alone resolves it with a written outcome.
        assertThat((String) JsonPath.read(ok(post(path + "/own"), "compliance", null), "$.owner"))
                .isEqualTo("compliance");
        assertThat(call(post(path + "/own"), "secadmin", null).getStatus()).isEqualTo(409);
        assertThat(call(post(path + "/resolve"), "secadmin", "{\"resolution\":\"Looked at it\"}").getStatus())
                .isEqualTo(403);
        assertThat(call(post(path + "/resolve"), "compliance", "{\"resolution\":\"ok\"}").getStatus()).isEqualTo(422);
        String resolved = ok(post(path + "/resolve"), "compliance", "{\"resolution\":\"Second administrator confirmed\"}");
        assertThat((String) JsonPath.read(resolved, "$.status")).isEqualTo("RESOLVED");
        assertThat(ok(get("/audits/evidence").param("entityRef", String.valueOf(id)), "compliance", null))
                .contains("Security event taken", "Security event resolved");
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
