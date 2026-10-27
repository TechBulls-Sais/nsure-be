package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/** Claim evidence: customers on their own claims, staff with claim permissions, PDF/JPG/PNG up to 10 MB. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-claim-documents;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ClaimDocumentApiTest {

    private static final byte[] PDF = "%PDF-1.4\n% sample death certificate\n%%EOF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    private MockMvc mockMvc;

    @Test
    void customerAndStaffShareTheEvidenceOnAClaim() throws Exception {
        String claim = customerClaim("Lerato Dube", "lerato");

        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "lerato", "password1",
                        file("certificate.pdf", PDF)).param("type", "Death certificate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].documents[0].fileName").value("certificate.pdf"))
                .andExpect(jsonPath("$.claims[0].documents[0].type").value("Death certificate"))
                .andExpect(jsonPath("$.claims[0].documents[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.claims[0].documents[0].uploadedBy").value("lerato"));

        String list = mockMvc.perform(get("/portfolio/claims/" + claim + "/documents").with(httpBasic("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$[0].id");

        // Both sides open the same bytes, shown inline with the original file name.
        byte[] staffCopy = mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/" + id)
                        .with(httpBasic("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("certificate.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(staffCopy).isEqualTo(PDF);
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + id).with(httpBasic("lerato", "password1")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF));

        mockMvc.perform(upload("/portfolio/claims/" + claim + "/documents", "assessor", "password", file("bill.png", PNG))
                        .param("type", "Hospital bill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].contentType").value("image/png"))
                .andExpect(jsonPath("$[1].uploadedBy").value("assessor"));

        mockMvc.perform(get("/portfolio/claims").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].documents.length()").value(2));
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Document Added')].details").value(Matchers.hasItems(
                        "Death certificate: certificate.pdf", "Hospital bill: bill.png")));
    }

    @Test
    void onlyTheOwnerAndClaimStaffReachTheFiles() throws Exception {
        String claim = customerClaim("Kabelo Sebina", "kabelo");
        customerClaim("Onalenna Pule", "onalenna");
        String shop = mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "kabelo", "password1",
                        file("id.pdf", PDF)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].documents[0].type").value("Supporting evidence"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(shop, "$.claims[0].documents[0].id");

        // Another customer: answered as if the claim did not exist.
        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "onalenna", "password1",
                        file("id.pdf", PDF)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + id).with(httpBasic("onalenna", "password1")))
                .andExpect(status().isNotFound());
        // A customer never reaches the staff endpoints.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/" + id).with(httpBasic("kabelo", "password1")))
                .andExpect(status().isForbidden());
        // Staff without claim permissions.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents").with(httpBasic("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(upload("/portfolio/claims/" + claim + "/documents", "onboarding", "password", file("id.pdf", PDF)))
                .andExpect(status().isForbidden());
        // A document id from another claim is not found on this one.
        String other = customerClaim("Masego Tau", "masego");
        mockMvc.perform(get("/portfolio/claims/" + other + "/documents/" + id).with(httpBasic("admin", "password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyRealPdfJpgOrPngUpTo10MbIsAccepted() throws Exception {
        String claim = customerClaim("Tumelo Kgari", "tumelo");
        String path = "/portfolio/claims/" + claim + "/documents";

        mockMvc.perform(upload(path, "admin", "password", file("notes.txt", "hello".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only PDF, JPG and PNG files can be uploaded."));
        // The extension says PDF, the bytes say PNG.
        mockMvc.perform(upload(path, "admin", "password", file("renamed.pdf", PNG)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(upload(path, "admin", "password", file("empty.pdf", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Choose a file to upload."));
        byte[] big = Arrays.copyOf(PDF, (int) ClaimDocumentService.MAX_BYTES + 1);
        mockMvc.perform(upload(path, "admin", "password", file("big.pdf", big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Files can be at most 10 MB."));
        mockMvc.perform(upload(path, "admin", "password", file("id.pdf", PDF)).param("type", "Selfie"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(upload(path, "admin", "password", file("scan.JPEG", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].contentType").value("image/jpeg"));
    }

    @Test
    void aClosedClaimTakesNoNewDocuments() throws Exception {
        String claim = customerClaim("Boipelo Nkwe", "boipelo");
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{\"reason\":\"Not covered\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(upload("/portfolio/claims/" + claim + "/documents", "admin", "password", file("late.pdf", PDF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Documents can only be added while the claim is open."));
    }

    @Test
    void staffGenerateAClaimSummaryPdfEvenOnAClosedClaim() throws Exception {
        String claim = customerClaim("Refilwe Moagi", "refilwe");
        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "refilwe", "password1",
                        file("certificate.pdf", PDF)).param("type", "Death certificate"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor",
                        "{\"reason\":\"Event before cover → not covered\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(httpBasic("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(httpBasic("refilwe", "password1")))
                .andExpect(status().isForbidden());

        String list = mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary")
                        .with(httpBasic("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].type").value("Claim summary"))
                .andExpect(jsonPath("$[1].contentType").value("application/pdf"))
                .andExpect(jsonPath("$[1].fileName").value(Matchers.startsWith(claim + "-summary-")))
                .andExpect(jsonPath("$[1].uploadedBy").value("assessor"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$[1].id");

        // The customer sees the summary on their own claim too.
        byte[] pdf = mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + id)
                        .with(httpBasic("refilwe", "password1")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains("Claim summary", claim, "Refilwe Moagi", "Rejected",
                    "Event before cover -> not covered", "Death certificate", "certificate.pdf", "Rejected by assessor");
        }
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Summary Generated')].performedBy").value(Matchers.hasItem("assessor")));
    }

    /** A customer with a verified login, a paid-up policy over the STP ceiling, and a claim they lodged on it. */
    private String customerClaim(String name, String username) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        String policy = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":50000,\"premium\":50}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String shop = mockMvc.perform(json(post("/portfolio/shop/claims"), username,
                        "{\"policy\":\"" + JsonPath.read(policy, "$.id") + "\",\"claimantName\":\"Next Of Kin\","
                                + "\"claimantMobile\":\"+267 72 000 000\",\"claimantRelationship\":\"Spouse\","
                                + "\"eventDate\":\"" + LocalDate.now().minusDays(1) + "\",\"description\":\"Passed away.\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(shop, "$.claims[0].id");
    }

    private MockMultipartHttpServletRequestBuilder upload(String path, String username, String password,
            MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder request = multipart(path).file(file);
        request.with(httpBasic(username, password));
        return request;
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }
}
