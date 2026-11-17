package com.nsure.audit.evidence;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** NS-SH11-007 / SH11 workspace: audit evidence searched by actor, record type and dates, and exported as CSV. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class EvidenceExportApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void evidenceIsSearchedByActorAndDatesAndExportedAsCsv() throws Exception {
        String name = "Export check " + System.nanoTime();
        assertThat(call(post("/roles").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":\"x\"}"), "admin").getStatus()).isEqualTo(201);
        String today = LocalDate.now().toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();

        List<String> actors = JsonPath.read(ok(get("/audits/evidence").param("actor", "admin")
                .param("from", today).param("to", today), "compliance"), "$[*].actor");
        assertThat(actors).isNotEmpty().containsOnly("admin");
        assertThat(JsonPath.<List<Object>>read(ok(get("/audits/evidence").param("actor", "admin")
                .param("from", tomorrow), "compliance"), "$")).isEmpty();

        MockHttpServletResponse csv = call(get("/audits/evidence/export").param("actor", "admin")
                .param("from", today), "compliance");
        assertThat(csv.getStatus()).isEqualTo(200);
        assertThat(csv.getContentType()).startsWith("text/csv");
        assertThat(csv.getHeader("Content-Disposition")).contains("attachment").contains(".csv");
        String body = csv.getContentAsString();
        assertThat(body).startsWith("sequence_no,occurred_at,actor,action,entity_type,entity_ref,reason,correlation_id,hash");
        assertThat(body).contains("\"admin\"").contains("CREATE_ROLE");

        // The export is itself evidence, and only auditors may export.
        assertThat(ok(get("/audits/evidence").param("entityType", "Audit evidence").param("actor", "compliance"),
                "compliance")).contains("Exported audit evidence");
        assertThat(call(get("/audits/evidence/export"), "onboarding").getStatus()).isEqualTo(403);

        assertThat(EvidenceController.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(EvidenceController.cell(null)).isEmpty();
    }

    private String ok(MockHttpServletRequestBuilder request, String user) throws Exception {
        MockHttpServletResponse response = call(request, user);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return response.getContentAsString();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String user) throws Exception {
        return mockMvc.perform(request.with(signedIn(user, "password"))).andReturn().getResponse();
    }
}
