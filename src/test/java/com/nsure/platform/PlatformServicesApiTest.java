package com.nsure.platform;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Party merge (AT-SH01-01/02), wording library, extracts and governed assistants (NS-AIG-001/004/006). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-platform;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PlatformServicesApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void duplicatesAreFlaggedAndAMergeKeepsBothReferences() throws Exception {
        String a = customer("Sethunya Double", "double@example.com");
        String b = customer("Sethunya Double", "double@example.com");
        mockMvc.perform(json(post("/portfolio/customers/" + b + "/kyc"), "admin", "{}")).andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + b
                        + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(get("/platform/parties/duplicates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.first.ref == '" + a + "' && @.second.ref == '" + b + "')].confidence").value(hasItem("LIKELY")));
        mockMvc.perform(json(post("/platform/parties/merges"), "admin", "{\"survivingRef\":\"" + a + "\",\"mergedRef\":\"" + b + "\",\"reason\":\"ok\"}"))
                .andExpect(status().isUnprocessableEntity());
        // NS-SH01-002: the merge is a request; a data steward who is someone else approves it before anything moves.
        String request = JsonPath.read(mockMvc.perform(json(post("/platform/parties/merges"), "admin",
                        "{\"survivingRef\":\"" + a + "\",\"mergedRef\":\"" + b
                        + "\",\"reason\":\"Same person captured twice at the branch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/portfolio/parties/merge-requests/" + request + "/approve"), "admin", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/parties/merge-requests/" + request + "/approve"), "secadmin", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.movedDetail").value(org.hamcrest.Matchers.containsString(
                        "1 applications, policies and claims")));
        mockMvc.perform(get("/servicing/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policy.customerRef").value(a));
        mockMvc.perform(get("/platform/parties/merges").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].mergedRef").value(b));
    }

    @Test
    void wordingsExtractsAndAssistantsAreGoverned() throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/platform/wordings"), "admin",
                        "{\"product\":\"Term Life\",\"clauseType\":\"EXCLUSION\",\"title\":\"War risks\",\"body\":\"No benefit is payable for death caused by war.\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/platform/wordings/" + ref + "/versions/1/approval").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/platform/wordings/" + ref + "/versions/1/approval").with(signedIn("secadmin", "password")))
                .andExpect(status().isOk());

        mockMvc.perform(json(post("/platform/extracts"), "admin", "{\"extractType\":\"POLICIES\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checksum").isNotEmpty())
                .andExpect(jsonPath("$.recordCount").value(org.hamcrest.Matchers.greaterThan(0)));
        mockMvc.perform(get("/platform/metrics").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.name == 'Persistency (13-month)')]").isNotEmpty());

        String customer = customer("Bonolo Assist", "assist@example.com");
        Integer id = JsonPath.read(mockMvc.perform(json(post("/platform/assistants/draft-message"), "admin",
                        "{\"customerRef\":\"" + customer + "\",\"purpose\":\"PREMIUM_REMINDER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
                .andExpect(jsonPath("$.output[0]").value(containsString("Dear Bonolo")))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/platform/assistants/log/" + id + "/review"), "admin", "{\"accept\":true}"))
                .andExpect(jsonPath("$.reviewStatus").value("ACCEPTED"));
        // NS-AIG-006: switched off, the assistant says so and the manual route is unaffected.
        mockMvc.perform(json(put("/platform/assistants/DRAFT_MESSAGE"), "admin", "{\"enabled\":false}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/platform/assistants/draft-message"), "admin",
                        "{\"customerRef\":\"" + customer + "\",\"purpose\":\"PREMIUM_REMINDER\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/platform/assistants/DRAFT_MESSAGE"), "admin", "{\"enabled\":true}")).andExpect(status().isOk());
        mockMvc.perform(post("/platform/assistants/risk-indicators/" + customer).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.output[0]").value(containsString("Lapse indicator")));
    }

    private String customer(String name, String email) throws Exception {
        String ref = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"" + email + "\",\"phone\":\"+267 71 970 001\",\"dateOfBirth\":\"1988-04-12\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        return ref;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
