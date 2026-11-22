package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NS-SH09A-002 (work package e): a generation template is validated (merge fields, required fields, balanced clauses,
 * page limit), previewed as a sample, published only by a second person with its sample's hash, and a document
 * generated from it records the template version and checksum.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-document-templates;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DocumentTemplateApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aTemplateIsValidatedPublishedBySomeoneElseAndGeneratesADocumentWithItsVersion() throws Exception {
        mockMvc.perform(get("/portfolio/document-templates").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templates[?(@.code == 'POLICY_CONFIRMATION')].status").value(hasItem("APPROVED")));
        // Validation: unknown field, unbalanced clause, required field missing.
        mockMvc.perform(json(post("/portfolio/document-templates"), "admin", "{\"code\":\"WELCOME_LETTER\","
                        + "\"kind\":\"LETTER\",\"title\":\"Welcome\",\"body\":\"Dear {{customerName}}, {{#if premium}}pay"
                        + " {{premium}} {{bankAccount}}\",\"requiredFields\":\"policyRef\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("bankAccount")))
                .andExpect(jsonPath("$.message").value(containsString("{{/if}}")))
                .andExpect(jsonPath("$.message").value(containsString("policyRef does not appear")));
        String body = "{\"code\":\"WELCOME_LETTER\",\"kind\":\"LETTER\",\"title\":\"Welcome to Nsure\","
                + "\"body\":\"Dear {{customerName}},\\nWelcome. Your policy {{policyRef}} is {{status}}.\\n"
                + "{{#if premium}}Your premium is {{premium}}.{{/if}}\",\"requiredFields\":\"customerName,policyRef\","
                + "\"maxPages\":1}";
        mockMvc.perform(json(post("/portfolio/document-templates/preview"), "admin", body))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF));
        mockMvc.perform(json(post("/portfolio/document-templates"), "admin", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.versionNo").value(1));
        mockMvc.perform(post("/portfolio/document-templates/WELCOME_LETTER/versions/1/approve")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/document-templates/WELCOME_LETTER/versions/1/approve")
                        .with(signedIn("secadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.sampleSha256").isNotEmpty());

        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"Tumelo"
                        + " Letter\",\"email\":\"tumelo.l@example.com\",\"phone\":\"+267 71 700 000\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + customer + "/kyc"), "admin", "{}")).andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + customer + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/documents/generate"), "admin",
                        "{\"templateCode\":\"WELCOME_LETTER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'Welcome to Nsure')].fileName").value(hasItem(
                        "welcome_letter-" + policy + ".pdf")));
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/documents/generate"), "onboarding",
                        "{\"templateCode\":\"WELCOME_LETTER\"}"))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select wording_versions from"
                + " ins_policy_document where file_name = ?", String.class, "welcome_letter-" + policy + ".pdf"))
                .isEqualTo("Template WELCOME_LETTER v1");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
