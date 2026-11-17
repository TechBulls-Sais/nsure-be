package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The pluggable malware scanner hook (AT-SH09A-02): with a scanner configured, a clean verdict releases an upload at
 * once (recorded as scanned, by the scanner), an infected one is rejected with the finding, and a scanner failure
 * leaves the file in quarantine for a person. Without one (every other test) nothing is ever called clean.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-upload-scanner;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(UploadScannerHookTest.Scanner.class)
class UploadScannerHookTest {

    @TestConfiguration
    static class Scanner {
        @Bean
        UploadScanner testScanner() {
            return new UploadScanner() {
                @Override
                public String name() {
                    return "test-scanner";
                }

                @Override
                public Verdict scan(String fileName, byte[] bytes) {
                    String text = new String(bytes, StandardCharsets.US_ASCII);
                    if (text.contains("EICAR")) {
                        return new Verdict(INFECTED, "EICAR test signature");
                    }
                    if (text.contains("boom")) {
                        throw new IllegalStateException("scanner offline");
                    }
                    return new Verdict(CLEAN, "No threat found");
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aConfiguredScannerReleasesCleanFilesRejectsInfectedOnesAndHoldsOnFailure() throws Exception {
        String claim = customerClaim("Seabo Ntsima", "seabo");
        String path = "/portfolio/shop/claims/" + claim + "/documents";

        mockMvc.perform(multipart(path).file(file("death.pdf", "%PDF-1.4 clean")).param("type", "Death certificate")
                        .with(signedIn("seabo", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].documents[0].status").value("CURRENT"))
                .andExpect(jsonPath("$.claims[0].documents[0].scanStatus").value("CLEAN"))
                .andExpect(jsonPath("$.claims[0].documents[0].note").value(Matchers.containsString("scanner:test-scanner")));
        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].evidence[?(@.documentType == 'Death certificate')].received")
                        .value(Matchers.contains(true)));

        mockMvc.perform(multipart(path).file(file("virus.pdf", "%PDF-1.4 EICAR")).param("type", "Identity document")
                        .with(signedIn("seabo", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].documents[?(@.fileName == 'virus.pdf')].status").value(Matchers.contains("REJECTED")))
                .andExpect(jsonPath("$.claims[0].documents[?(@.fileName == 'virus.pdf')].note")
                        .value(Matchers.contains("Not accepted: Malware found: EICAR test signature")));

        mockMvc.perform(multipart(path).file(file("later.pdf", "%PDF-1.4 boom")).param("type", "Identity document")
                        .with(signedIn("seabo", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].documents[?(@.fileName == 'later.pdf')].status").value(Matchers.contains("QUARANTINED")));
        mockMvc.perform(get("/portfolio/document-quarantine").with(signedIn("assessor", "password")))
                .andExpect(jsonPath("$[?(@.fileName == 'later.pdf')].scanDetail")
                        .value(Matchers.contains(Matchers.containsString("test-scanner failed: scanner offline"))));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content.getBytes(StandardCharsets.US_ASCII));
    }

    private String customerClaim(String name, String username) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":50000,\"premium\":50}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/policies/" + JsonPath.read(policy, "$.id") + "/payments"), "admin",
                        "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(json(post("/portfolio/shop/claims"), username,
                        "{\"policy\":\"" + JsonPath.read(policy, "$.id") + "\",\"claimantName\":\"Next Of Kin\","
                                + "\"claimantMobile\":\"+267 72 000 000\",\"claimantRelationship\":\"Spouse\","
                                + "\"eventDate\":\"" + LocalDate.now().minusDays(1) + "\",\"description\":\"Passed away.\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(shop, "$.claims[0].id");
    }
}
