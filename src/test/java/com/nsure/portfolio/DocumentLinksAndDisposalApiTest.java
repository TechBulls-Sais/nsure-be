package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NS-SH09A-006 / AT-SH09A-03 (work package e): a retrieval link works once, for the person it was made for, until it
 * expires, and only while they may still open the document; a document past retention and not on hold is disposed of
 * only once a second person approves, and is then gone from storage, search and links.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-document-links;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DocumentLinksAndDisposalApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aLinkWorksOnceForItsOwnerUntilItExpires() throws Exception {
        long doc = schedule("Lebo Linked", "lebolinked");
        String token = link("admin", "POLICY", doc);
        mockMvc.perform(get("/portfolio/document-links/" + token).with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        // Used once: a second use, or a copy in someone else's hands, finds nothing.
        mockMvc.perform(get("/portfolio/document-links/" + token).with(signedIn("admin", "password")))
                .andExpect(status().isNotFound());
        String copied = link("admin", "POLICY", doc);
        mockMvc.perform(get("/portfolio/document-links/" + copied).with(signedIn("support", "password")))
                .andExpect(status().isNotFound());
        String expired = link("admin", "POLICY", doc);
        jdbc.update("update doc_link set expires_at = ? where used_at is null",
                Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
        mockMvc.perform(get("/portfolio/document-links/" + expired).with(signedIn("admin", "password")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("expired")));
        // The customer gets a link to their own document, never to another customer's.
        String own = link("lebolinked", "POLICY", doc);
        mockMvc.perform(get("/portfolio/document-links/" + own).with(signedIn("lebolinked", "password1")))
                .andExpect(status().isOk());
        long other = schedule("Kago Other", "kagoother");
        mockMvc.perform(json(post("/portfolio/document-links"), "lebolinked",
                        "{\"kind\":\"POLICY\",\"documentId\":" + other + "}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(post("/portfolio/document-links"), "onboarding",
                        "{\"kind\":\"POLICY\",\"documentId\":" + doc + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDocumentPastRetentionIsDisposedOfOnlyWithASecondPerson() throws Exception {
        long doc = schedule("Neo Disposed", "neodisposed");
        String key = "P:" + doc;
        mockMvc.perform(json(post("/ops/documents/disposals"), "admin", "{\"docKey\":\"" + key
                        + "\",\"reason\":\"Retention period has passed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("must be kept until")));
        jdbc.update("update ins_policy_document set uploaded_at = ? where id = ?",
                Timestamp.valueOf(LocalDateTime.now().minusYears(12)), doc);
        // A legal hold stops it.
        mockMvc.perform(json(put("/ops/documents/hold"), "admin", "{\"docKey\":\"" + key
                        + "\",\"hold\":true,\"reason\":\"Litigation 2026/14\"}").param("docKey", key))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/ops/documents/disposals"), "admin", "{\"docKey\":\"" + key
                        + "\",\"reason\":\"Retention period has passed\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("legal hold")));
        mockMvc.perform(json(put("/ops/documents/hold"), "admin", "{\"docKey\":\"" + key + "\",\"hold\":false}")
                        .param("docKey", key))
                .andExpect(status().isOk());
        Integer id = JsonPath.read(mockMvc.perform(json(post("/ops/documents/disposals"), "admin", "{\"docKey\":\""
                        + key + "\",\"reason\":\"Retention period has passed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/ops/documents/disposals/" + id + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/ops/documents/disposals/" + id + "/approve").with(signedIn("secadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPOSED"));
        mockMvc.perform(get("/ops/documents").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.documents[?(@.docKey == '" + key + "')].status").value(hasItem("DISPOSED")));
        mockMvc.perform(json(post("/portfolio/document-links"), "admin", "{\"kind\":\"POLICY\",\"documentId\":" + doc + "}"))
                .andExpect(status().isNotFound());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    /** A customer with an issued policy and its generated schedule; returns the schedule's document id. */
    private long schedule(String name, String username) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 600 000\",\"username\":\""
                        + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "admin", "{}")).andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + ref + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        Long existing = jdbc.queryForObject("select count(*) from ins_policy_document d join ins_policy p on p.id ="
                + " d.policy_id where p.record_ref = ?", Long.class, policy);
        if (existing == null || existing == 0) {
            mockMvc.perform(json(post("/portfolio/policies/" + policy + "/documents/schedule"), "admin", "{}"))
                    .andExpect(status().isOk());
        }
        return jdbc.queryForObject("select max(d.id) from ins_policy_document d join ins_policy p on p.id = d.policy_id"
                + " where p.record_ref = ?", Long.class, policy);
    }

    private String link(String user, String kind, long id) throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/document-links"), user, "{\"kind\":\"" + kind
                        + "\",\"documentId\":" + id + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = user.endsWith("linked") || user.endsWith("other") || user.endsWith("disposed")
                ? "password1" : "password";
        return request.with(signedIn(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
