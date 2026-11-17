package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
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

    @Autowired
    private ClaimDocumentService documents;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    private com.nsure.security.session.SessionTokenService sessions;

    @Autowired
    private org.springframework.security.core.userdetails.UserDetailsService users;

    @Test
    void customerAndStaffShareTheEvidenceOnAClaim() throws Exception {
        String claim = customerClaim("Lerato Dube", "lerato");

        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "lerato", "password1",
                        file("certificate.pdf", PDF)).param("type", "Death certificate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].documents[0].fileName").value("certificate.pdf"))
                .andExpect(jsonPath("$.claims[0].documents[0].type").value("Death certificate"))
                .andExpect(jsonPath("$.claims[0].documents[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$.claims[0].documents[0].uploadedBy").value("lerato"))
                .andExpect(jsonPath("$.claims[0].documents[0].status").value("QUARANTINED"));
        release(claim, "certificate.pdf", "assessor");

        String list = mockMvc.perform(get("/portfolio/claims/" + claim + "/documents").with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("CURRENT"))
                .andExpect(jsonPath("$[0].scanStatus").value("RELEASED_UNSCANNED"))
                .andExpect(jsonPath("$[0].source").value("Uploaded in the customer portal"))
                .andExpect(jsonPath("$[0].classification").value("Sensitive: health"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$[0].id");

        // Both sides open the same bytes, shown inline with the original file name.
        byte[] staffCopy = mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/" + id)
                        .with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", Matchers.containsString("certificate.pdf")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(staffCopy).isEqualTo(PDF);
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + id).with(signedIn("lerato", "password1")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF));

        mockMvc.perform(upload("/portfolio/claims/" + claim + "/documents", "assessor", "password", file("bill.png", PNG))
                        .param("type", "Hospital bill"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].contentType").value("image/png"))
                .andExpect(jsonPath("$[1].uploadedBy").value("assessor"))
                .andExpect(jsonPath("$[1].status").value("QUARANTINED"));
        // The uploader cannot release their own file; a second assessor can.
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + pendingUpload(claim, "bill.png") + "/release"),
                        "assessor", "{\"note\":\"Checked the bill\"}"))
                .andExpect(status().isForbidden());
        release(claim, "bill.png", "assessor2");

        mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + claim + "')].documents.length()").value(2));
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Document Received')].details").value(Matchers.hasItems(
                        "Death certificate: certificate.pdf · held in quarantine until released",
                        "Hospital bill: bill.png · held in quarantine until released")))
                .andExpect(jsonPath("$[?(@.action == 'Document Added')].details").value(Matchers.hasItems(
                        "Death certificate: certificate.pdf · released from quarantine by assessor",
                        "Hospital bill: bill.png · released from quarantine by assessor2")));
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
        assertThat((Object) JsonPath.read(shop, "$.claims[0].documents[0].id")).isNull();
        Integer id = release(claim, "id.pdf", "assessor");

        // Another customer: answered as if the claim did not exist.
        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "onalenna", "password1",
                        file("id.pdf", PDF)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + id).with(signedIn("onalenna", "password1")))
                .andExpect(status().isNotFound());
        // A customer never reaches the staff endpoints.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/" + id).with(signedIn("kabelo", "password1")))
                .andExpect(status().isForbidden());
        // Staff without claim permissions.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(upload("/portfolio/claims/" + claim + "/documents", "onboarding", "password", file("id.pdf", PDF)))
                .andExpect(status().isForbidden());
        // A document id from another claim is not found on this one.
        String other = customerClaim("Masego Tau", "masego");
        mockMvc.perform(get("/portfolio/claims/" + other + "/documents/" + id).with(signedIn("admin", "password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyRealPdfJpgOrPngUpTo10MbIsAccepted() throws Exception {
        String claim = customerClaim("Tumelo Kgari", "tumelo");
        String path = "/portfolio/claims/" + claim + "/documents";

        mockMvc.perform(upload(path, "admin", "password", file("notes.txt", "hello".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Only PDF, JPG and PNG files can be uploaded."));
        // The extension says PDF, the bytes say PNG.
        mockMvc.perform(upload(path, "admin", "password", file("renamed.pdf", PNG)))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(upload(path, "admin", "password", file("empty.pdf", new byte[0])))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Choose a file to upload."));
        byte[] big = Arrays.copyOf(PDF, (int) ClaimDocumentService.MAX_BYTES + 1);
        mockMvc.perform(upload(path, "admin", "password", file("big.pdf", big)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Files can be at most 10 MB."));
        mockMvc.perform(upload(path, "admin", "password", file("id.pdf", PDF)).param("type", "Selfie"))
                .andExpect(status().isUnprocessableEntity());
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
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Documents can only be added while the claim is open."));
    }

    @Test
    void staffGenerateAClaimSummaryPdfEvenOnAClosedClaim() throws Exception {
        String claim = customerClaim("Refilwe Moagi", "refilwe");
        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "refilwe", "password1",
                        file("certificate.pdf", PDF)).param("type", "Death certificate"))
                .andExpect(status().isOk());
        release(claim, "certificate.pdf", "assessor");
        // While the claim is open it is an interim summary; issuing again supersedes it (Blueprint §8: kept, not deleted).
        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        String interim = mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary")
                        .with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[1].supersededReason").value("Replaced by a newer interim summary"))
                .andExpect(jsonPath("$[2].status").value("CURRENT"))
                .andExpect(jsonPath("$[2].sha256").value(Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(jsonPath("$[2].type").value("Interim claim summary"))
                .andExpect(jsonPath("$[2].fileName").value(Matchers.startsWith(claim + "-interim-summary-")))
                .andReturn().getResponse().getContentAsString();
        byte[] interimPdf = mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/"
                        + JsonPath.read(interim, "$[2].id")).with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(interimPdf)) {
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(doc))
                    .contains("Interim claim summary", "Not yet decided", "This is an interim summary");
        }
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor",
                        "{\"reason\":\"Event before cover → not covered\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(signedIn("refilwe", "password1")))
                .andExpect(status().isForbidden());

        String list = mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary")
                        .with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[2].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[2].supersededReason").value("Replaced by the final claim summary"))
                .andExpect(jsonPath("$[3].status").value("CURRENT"))
                .andExpect(jsonPath("$[3].type").value("Claim summary"))
                .andExpect(jsonPath("$[3].contentType").value("application/pdf"))
                .andExpect(jsonPath("$[3].fileName").value(Matchers.startsWith(claim + "-summary-")))
                .andExpect(jsonPath("$[3].uploadedBy").value("assessor"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$[3].id");
        assertThat((Integer) JsonPath.read(list, "$[2].supersededBy")).isEqualTo(id);

        // The customer sees the summary on their own claim too.
        byte[] pdf = mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + id)
                        .with(signedIn("refilwe", "password1")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (org.apache.pdfbox.pdmodel.PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
            assertThat(text).contains("Claim summary", claim, "Refilwe Moagi", "Rejected",
                    "Event before cover -> not covered", "Death certificate", "certificate.pdf", "1. The claim",
                    "3. Checks", "The policy was active", "No payment was made, because the claim was rejected",
                    "7. Timeline", "If you disagree with this decision", "Page 1 of");
        }
        mockMvc.perform(get("/portfolio/history/claim/" + claim).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Summary Generated')].performedBy").value(Matchers.hasItem("assessor")));

        // Once per claim.
        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(signedIn("assessor", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("already has its claim summary")));
    }

    @Test
    void oldLayoutSummariesOnAClosedClaimAreReplacedByOneCurrentSummary() throws Exception {
        String claim = customerClaim("Tumelo Ditshwanelo", "tumelod");
        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "tumelod", "password1",
                        file("bill.pdf", PDF)).param("type", "Hospital bill"))
                .andExpect(status().isOk());
        release(claim, "bill.pdf", "assessor");
        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "assessor", "{\"reason\":\"Not covered\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        // As the old button left it: two summaries in the old layout, next to the uploaded bill.
        Long claimId = jdbc.queryForObject("select claim_id from ins_claim_document where document_type = 'Claim summary'"
                + " and id = (select max(id) from ins_claim_document)", Long.class);
        jdbc.update("update ins_claim_document set layout_version = 1 where claim_id = ? and document_type = 'Claim summary'",
                claimId);
        jdbc.update("insert into ins_claim_document (claim_id, document_type, file_name, content_type, size_bytes,"
                + " storage_path, uploaded_by, uploaded_at, layout_version) select claim_id, document_type, file_name,"
                + " content_type, size_bytes, 'gone/old.pdf', uploaded_by, uploaded_at, 1 from ins_claim_document"
                + " where claim_id = ? and document_type = 'Claim summary'", claimId);
        assertThat(documents.closedClaimsWithOldSummaries()).contains(claimId);

        assertThat(documents.replaceOldSummaries(claimId)).isEqualTo(2);

        assertThat(documents.closedClaimsWithOldSummaries()).doesNotContain(claimId);
        assertThat(documents.replaceOldSummaries(claimId)).isZero();
        // The bill is untouched; the old summaries are kept as superseded (Blueprint §8), and one current summary is
        // credited to whoever issued the first.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].type").value("Hospital bill"))
                .andExpect(jsonPath("$[0].status").value("CURRENT"))
                .andExpect(jsonPath("$[1].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[2].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[3].type").value("Claim summary"))
                .andExpect(jsonPath("$[3].status").value("CURRENT"))
                .andExpect(jsonPath("$[3].uploadedBy").value("assessor"));
        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(signedIn("assessor", "password")))
                .andExpect(status().isConflict());
    }

    /**
     * AT-SH09A-02: an upload waits in quarantine. It cannot be downloaded through the normal endpoints, by staff or
     * the customer who sent it, and it does not satisfy the claim's mandatory evidence until a reviewer other than the
     * uploader releases it; the release is recorded as not scanned, never as clean. A rejected upload never counts.
     */
    @Test
    void aQuarantinedUploadNeitherCountsAsEvidenceNorDownloadsUntilReleased() throws Exception {
        String claim = customerClaim("Gaone Mmusi", "gaone");
        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "gaone", "password1",
                        file("death.pdf", PDF)).param("type", "Death certificate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.claims[0].documents[0].status").value("QUARANTINED"))
                .andExpect(jsonPath("$.claims[0].documents[0].scanStatus").value("QUARANTINED"))
                .andExpect(jsonPath("$.claims[0].documents[0].note").value(Matchers.containsString("Being checked")));
        assertThat(evidenceReceived(claim, "Death certificate")).isFalse();
        Integer uploadId = pendingUpload(claim, "death.pdf");

        // The queue shows it as not scanned; nobody calls it clean.
        mockMvc.perform(get("/portfolio/document-quarantine").with(signedIn("support", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + uploadId + ")].scanResult").value("NOT_SCANNED"))
                .andExpect(jsonPath("$[?(@.id == " + uploadId + ")].scanDetail").value(
                        Matchers.hasItem(Matchers.containsString("No malware scanner is configured"))))
                .andExpect(jsonPath("$[?(@.id == " + uploadId + ")].channel").value("PORTAL"));
        mockMvc.perform(get("/ops/documents?q=death.pdf").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.documents[0].scanStatus").value("QUARANTINED"))
                .andExpect(jsonPath("$.documents[*].scanStatus").value(Matchers.not(Matchers.hasItem("Clean"))));

        // No normal download reaches the held file: it has no document id, and its upload id is no document.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/" + uploadId).with(signedIn("assessor", "password")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + uploadId).with(signedIn("gaone", "password1")))
                .andExpect(status().isNotFound());
        // Only a reviewer may open it to decide, and that is logged; claim staff without the code and the customer may not.
        mockMvc.perform(get("/portfolio/document-quarantine/" + uploadId + "/file").with(signedIn("support", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portfolio/document-quarantine/" + uploadId + "/file").with(signedIn("gaone", "password1")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portfolio/document-quarantine/" + uploadId + "/file").with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(content().bytes(PDF));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/document-access").with(signedIn("compliance", "password")))
                .andExpect(jsonPath("$[0].kind").value("UPLOAD"))
                .andExpect(jsonPath("$[0].username").value("assessor"));

        // A release needs a note and the code; a support user lacks it.
        mockMvc.perform(post("/portfolio/document-quarantine/" + uploadId + "/release").with(signedIn("support", "password"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"note\":\"Looks fine\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + uploadId + "/release"), "assessor", "{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + uploadId + "/release"), "assessor",
                        "{\"note\":\"Opened it: an original death certificate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.scanResult").value("NOT_SCANNED"))
                .andExpect(jsonPath("$.decidedBy").value("assessor"));
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + uploadId + "/reject"), "assessor2",
                        "{\"reason\":\"Too late\"}"))
                .andExpect(status().isConflict());
        assertThat(evidenceReceived(claim, "Death certificate")).isTrue();
        mockMvc.perform(get("/ops/documents?q=death.pdf").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.documents[0].scanStatus").value("RELEASED_UNSCANNED"))
                .andExpect(jsonPath("$.documents[0].scanLabel").value("Not scanned: released by a person"));
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where action = ? and entity_ref = ?",
                Long.class, "Upload released without a malware scan", claim)).isEqualTo(1L);

        // A rejected upload never counts and the customer sees why.
        mockMvc.perform(upload("/portfolio/shop/claims/" + claim + "/documents", "gaone", "password1",
                        file("omang.pdf", PDF)).param("type", "Identity document"))
                .andExpect(status().isOk());
        Integer second = pendingUpload(claim, "omang.pdf");
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + second + "/reject"), "assessor",
                        "{\"reason\":\"The page is unreadable; please upload a clear copy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(evidenceReceived(claim, "Identity document")).isFalse();
        mockMvc.perform(get("/portfolio/shop").with(signedIn("gaone", "password1")))
                .andExpect(jsonPath("$.claims[0].documents[?(@.fileName == 'omang.pdf')].status").value("REJECTED"))
                .andExpect(jsonPath("$.claims[0].documents[?(@.fileName == 'omang.pdf')].note").value(
                        "Not accepted: The page is unreadable; please upload a clear copy"));
        mockMvc.perform(json(post("/portfolio/document-quarantine/" + second + "/release"), "assessor2",
                        "{\"note\":\"Changed my mind\"}"))
                .andExpect(status().isConflict());
    }

    /**
     * NS-SH09A-005: the register shows a document only to someone who may read its owning record (claim evidence
     * needs READ_CLAIM), filters by customer, record, class and date, searches the text of generated documents, and
     * logs every search as sensitive access.
     */
    @Test
    void theRegisterAppliesTheOwningRecordsPermissionAndSearchesDocumentText() throws Exception {
        com.nsure.support.ModuleTestUsers.provision(jdbc);
        String claim = customerClaim("Tebogo Phiri", "tebogop");
        mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        String customerRef = jdbc.queryForObject("select customer_ref from ins_claim where record_ref = ?", String.class, claim);

        // The text of the generated summary is searchable; details alone do not contain this phrase.
        String found = mockMvc.perform(get("/ops/documents").param("q", "This is an interim summary")
                        .with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(found, "$.documents[*].ownerRef")).contains(claim);
        assertThat(JsonPath.<java.util.List<Boolean>>read(found, "$.documents[?(@.ownerRef == '" + claim
                + "')].contentMatch")).containsOnly(true);

        // Indexed by party, record, class and date.
        mockMvc.perform(get("/ops/documents").param("customerRef", customerRef).param("documentClass", "Claim evidence")
                        .param("from", LocalDate.now().toString()).with(signedIn("assessor", "password")))
                .andExpect(jsonPath("$.documents.length()").value(1))
                .andExpect(jsonPath("$.documents[0].ownerRef").value(claim))
                .andExpect(jsonPath("$.documents[0].customerRef").value(customerRef))
                .andExpect(jsonPath("$.documents[0].classification").value("Confidential"))
                .andExpect(jsonPath("$.documents[0].retentionPolicy").value("Claim evidence: 7 years from storage"));
        mockMvc.perform(get("/ops/documents").param("ownerRef", claim).param("to", LocalDate.now().minusDays(1).toString())
                        .with(signedIn("assessor", "password")))
                .andExpect(jsonPath("$.documents.length()").value(0));

        // A policy-only reader never sees claim evidence, whatever they search for.
        mockMvc.perform(get("/ops/documents").param("q", claim).with(signedIn("distribution", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classes").value(Matchers.contains("Policy document")))
                .andExpect(jsonPath("$.documents.length()").value(0));
        mockMvc.perform(get("/ops/documents").param("q", "This is an interim summary")
                        .with(signedIn("distribution", "password")))
                .andExpect(jsonPath("$.documents.length()").value(0));
        // Nor may they put claim evidence on hold.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/ops/documents/hold")
                        .param("docKey", "C:1").with(signedIn("distribution", "password"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"hold\":true,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where action = 'Document register searched'"
                + " and actor = 'distribution'", Long.class)).isGreaterThanOrEqualTo(2L);
    }

    /**
     * AT-SH09A-03: a broker (partner portal login) finds no documents through the register's search, the quarantine
     * queue or a copied download link; a link also stops working for anyone once their session ends.
     */
    @Test
    void aBrokerFindsNoDocumentsThroughSearchOrACopiedLink() throws Exception {
        String claim = customerClaim("Mothusi Kgang", "mothusi");
        String list = mockMvc.perform(post("/portfolio/claims/" + claim + "/documents/summary")
                        .with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$[0].id");
        String link = "/portfolio/claims/" + claim + "/documents/" + id;

        mockMvc.perform(get("/ops/documents").param("q", claim).with(signedIn("agent", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/ops/documents").param("q", "This is an interim summary").with(signedIn("agent", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/portfolio/document-quarantine").with(signedIn("agent", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(link).with(signedIn("agent", "password"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + id).with(signedIn("agent", "password")))
                .andExpect(status().isForbidden());

        // A copied link is not a credential: it needs a live session, and an ended one no longer opens it.
        String token = signInToken("assessor");
        mockMvc.perform(get(link).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/auth/session")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(get(link).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(link)).andExpect(status().isUnauthorized());
    }

    /** A session token for a staff user, as the sign-in code step would issue it. */
    private String signInToken(String username) {
        return sessions.issue((com.nsure.security.domain.AppUser) users.loadUserByUsername(username)).token();
    }

    /** Whether the claim's evidence checklist marks the document type received. */
    private boolean evidenceReceived(String claim, String documentType) throws Exception {
        String claims = mockMvc.perform(get("/portfolio/claims").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<Boolean> received = JsonPath.read(claims, "$[?(@.id == '" + claim + "')].evidence[?(@.documentType == '"
                + documentType + "')].received");
        assertThat(received).as("evidence item " + documentType).isNotEmpty();
        return received.get(0);
    }

    /** The quarantine id of the claim's pending upload with this file name. */
    private Integer pendingUpload(String claim, String fileName) throws Exception {
        String queue = mockMvc.perform(get("/portfolio/document-quarantine").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.List<Integer> ids = JsonPath.read(queue, "$[?(@.ownerRef == '" + claim + "' && @.fileName == '"
                + fileName + "')].id");
        assertThat(ids).as("pending upload " + fileName).hasSize(1);
        return ids.get(0);
    }

    /** Releases the claim's pending upload as the reviewer; returns the claim document id it became. */
    private Integer release(String claim, String fileName, String reviewer) throws Exception {
        String released = mockMvc.perform(json(post("/portfolio/document-quarantine/" + pendingUpload(claim, fileName)
                        + "/release"), reviewer, "{\"note\":\"Opened and checked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(released, "$.documentId");
    }

    /** A customer with a verified login, a paid-up policy over the STP ceiling, and a claim they lodged on it. */
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

    private MockMultipartHttpServletRequestBuilder upload(String path, String username, String password,
            MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder request = multipart(path).file(file);
        request.with(signedIn(username, password));
        return request;
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }
}
