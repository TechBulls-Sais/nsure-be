package com.nsure.security;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
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
 * D-03: creating a user with access needs a second person, but the first administrator of a fresh installation has
 * nobody to ask. While no other enabled user could approve, the creation is applied at once, recorded and alerted to
 * auditors; as soon as a second checker exists it waits for them like any other.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-user-bootstrap;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class UserCreationBootstrapTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theSoleAdministratorMayCreateTheSecondAndThenNeedsThem() throws Exception {
        // Leave admin as the only enabled Super Admin: secadmin switches itself off (its own profile, no checker).
        Number secadminId = JsonPath.<List<Number>>read(call(get("/users"), "admin", null).getContentAsString(),
                "$[?(@.username == 'secadmin')].id").get(0);
        assertThat(call(put("/users/" + secadminId), "secadmin", "{\"enabled\":false}").getStatus()).isEqualTo(200);
        Number superRole = JsonPath.<List<Number>>read(call(get("/roles"), "admin", null).getContentAsString(),
                "$[?(@.name == 'Super Admin')].id").get(0);

        MockHttpServletResponse first = call(post("/users"), "admin",
                "{\"username\":\"second-admin\",\"password\":\"password\",\"roleIds\":[" + superRole + "]}");
        assertThat(first.getStatus()).as(first.getContentAsString()).isEqualTo(201);
        assertThat((String) JsonPath.read(first.getContentAsString(), "$.status")).isEqualTo("PROCESSED");
        assertThat(call(get("/me/alerts"), "admin", null).getContentAsString()).contains("SOLE_CHECKER_BOOTSTRAP");

        // Now someone else can approve, so the next one waits for them.
        MockHttpServletResponse next = call(post("/users"), "admin",
                "{\"username\":\"third-admin\",\"password\":\"password\",\"roleIds\":[" + superRole + "]}");
        assertThat(next.getStatus()).as(next.getContentAsString()).isEqualTo(202);
        Number commandId = JsonPath.read(next.getContentAsString(), "$.commandId");
        assertThat(call(post("/audits/" + commandId + "/approve"), "second-admin", null).getStatus()).isEqualTo(200);
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
