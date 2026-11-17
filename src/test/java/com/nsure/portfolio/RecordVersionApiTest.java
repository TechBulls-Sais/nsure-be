package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.common.ExpectedVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NS-ARC-005 / NS-TXN-001: customers and applications carry a row version, returned with the record. A command sent
 * with the version the screen showed (If-Match) is refused with 409 STALE_VERSION once someone else changed it.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-record-version;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class RecordVersionApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aKycDecisionOnAStaleCustomerIsRefused() throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "{\"name\":\"Dikeledi Version\","
                        + "\"email\":\"dikeledi@example.com\",\"phone\":\"+267 71 444 555\",\"dateOfBirth\":\"1990-02-02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        // Someone else rejects KYC meanwhile; the version moves on.
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "{\"decision\":\"FAILED\",\"reason\":\"Blurred\"}")
                        .header(ExpectedVersion.HEADER, "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "{\"decision\":\"VERIFIED\"}")
                        .header(ExpectedVersion.HEADER, "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "{\"decision\":\"VERIFIED\"}")
                        .header(ExpectedVersion.HEADER, "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
    }

    @Test
    void aStaleDraftIsNotOverwrittenOrSubmittedAndAStaleApplicationIsNotIssued() throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "{\"name\":\"Goitse Version\","
                        + "\"email\":\"goitse@example.com\",\"phone\":\"+267 71 444 556\",\"dateOfBirth\":\"1990-02-02\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String draft = mockMvc.perform(json(post("/portfolio/applications"), "{\"status\":\"Draft\",\"customerRef\":\""
                        + customer + "\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String app = JsonPath.read(draft, "$.id");
        int seen = JsonPath.read(draft, "$.version");

        // A colleague completes the draft; the version this screen holds is now stale.
        String saved = mockMvc.perform(json(put("/portfolio/applications/" + app + "/details"), "{\"payerName\":\"\","
                        + "\"channel\":\"DIRECT\",\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"},"
                        + "\"declarationAccepted\":true}").header(ExpectedVersion.HEADER, String.valueOf(seen)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int current = JsonPath.read(saved, "$.version");
        mockMvc.perform(json(put("/portfolio/applications/" + app + "/details"), "{\"channel\":\"BRANCH\"}")
                        .header(ExpectedVersion.HEADER, String.valueOf(seen)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mockMvc.perform(post("/portfolio/applications/" + app + "/submit").with(signedIn("admin", "password"))
                        .header(ExpectedVersion.HEADER, String.valueOf(seen)))
                .andExpect(status().isConflict());
        String submitted = mockMvc.perform(post("/portfolio/applications/" + app + "/submit")
                        .with(signedIn("admin", "password")).header(ExpectedVersion.HEADER, String.valueOf(current)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Ready to issue"))
                .andReturn().getResponse().getContentAsString();
        int ready = JsonPath.read(submitted, "$.version");

        String issue = "{\"applicationRef\":\"" + app + "\",\"product\":\"Term Life\",\"tier\":\"Silver\"}";
        mockMvc.perform(json(post("/portfolio/policies"), issue).header(ExpectedVersion.HEADER, String.valueOf(seen)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        mockMvc.perform(json(post("/portfolio/policies"), issue).header(ExpectedVersion.HEADER, String.valueOf(ready)))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.with(signedIn("admin", "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
