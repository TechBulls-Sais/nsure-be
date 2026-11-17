package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** A-27: a customer awaiting KYC uploads identity evidence in the portal; KYC reviewers open it on the record. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:nsure-identity-documents;DB_CLOSE_DELAY=-1",
        "nsure.documents.dir=target/test-documents/identity"})
@AutoConfigureMockMvc
class IdentityDocumentApiTest {

    private static final byte[] PDF = "%PDF-1.4\n% omang copy\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aCustomerAwaitingKycUploadsEvidenceThatReviewersCanOpen() throws Exception {
        String ref = customer("Lorato Upload", "lorato.upload", "916000001");

        upload("lorato.upload", "omang.txt", "plain text".getBytes(StandardCharsets.US_ASCII), "Omang")
                .andExpect(status().isUnprocessableEntity());
        upload("lorato.upload", "omang.pdf", PDF, "Selfie").andExpect(status().isUnprocessableEntity());
        String body = upload("lorato.upload", "omang.pdf", PDF, "Omang")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].documentType").value("Omang"))
                .andExpect(jsonPath("$[0].fileName").value("omang.pdf"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(body, "$[0].id");
        mockMvc.perform(get("/portfolio/shop/identity-documents").with(signedIn("lorato.upload", "password123")))
                .andExpect(jsonPath("$.length()").value(1));

        // The reviewer sees it on the customer record, opens it, and the access is logged.
        mockMvc.perform(get("/portfolio/customers/" + ref + "/identity-documents").with(signedIn("onboarding", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
        mockMvc.perform(get("/portfolio/customers/" + ref + "/identity-documents/" + id)
                        .with(signedIn("onboarding", "password")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(content().bytes(PDF));
        assertThat(jdbc.queryForObject("select count(*) from document_access_log where document_kind = 'IDENTITY'"
                + " and owner_ref = ?", Long.class, ref)).isEqualTo(1);
        // Another record's document is not found under this customer; a customer cannot use the staff route.
        mockMvc.perform(get("/portfolio/customers/CUS-1001/identity-documents/" + id)
                        .with(signedIn("onboarding", "password")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/portfolio/customers/" + ref + "/identity-documents")
                        .with(signedIn("lorato.upload", "password123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void newEvidenceAfterARejectionReopensTheReviewAndAVerifiedCustomerNeedsNone() throws Exception {
        String ref = customer("Kitso Again", "kitso.again", "916000002");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("onboarding", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"FAILED\",\"reason\":\"Copy unreadable\"}"))
                .andExpect(status().isOk());
        upload("kitso.again", "passport.pdf", PDF, "Omang").andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/shop").with(signedIn("kitso.again", "password123")))
                .andExpect(jsonPath("$.customer.kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.customer.kycRejectionReason").value(""));
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where event_type = 'KycStatusChanged'"
                + " and aggregate_ref = ? and payload like '%\"status\":\"PENDING\"%'", Long.class, ref)).isEqualTo(1);

        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("onboarding", "password")))
                .andExpect(status().isOk());
        upload("kitso.again", "proof.pdf", PDF, "Proof of address").andExpect(status().isConflict());
    }

    private ResultActions upload(String user, String name, byte[] bytes, String type) throws Exception {
        return mockMvc.perform(multipart("/portfolio/shop/identity-documents")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes))
                .param("type", type)
                .with(signedIn(user, "password123")));
    }

    private String customer(String name, String username, String omang) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/portfolio/customers").with(signedIn("onboarding", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\","
                                + "\"phone\":\"+267 71 333 444\",\"poiType\":\"Omang\",\"poiNumber\":\"" + omang + "\","
                                + "\"dateOfBirth\":\"1991-03-03\",\"username\":\"" + username
                                + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }
}
