package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The generated policy schedule: staff with policy permissions, and customers on their own policies. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-policy-documents;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PolicyDocumentApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PolicyDocumentService documents;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void staffGenerateAPolicyScheduleWithTheCoverAndBeneficiaries() throws Exception {
        String policy = customerPolicy("Gosego Ramotswa", "gosego");

        String list = mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("Policy schedule"))
                .andExpect(jsonPath("$[0].contentType").value("application/pdf"))
                .andExpect(jsonPath("$[0].fileName").value(Matchers.startsWith(policy + "-schedule-")))
                .andExpect(jsonPath("$[0].uploadedBy").value("admin"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(list, "$[0].id");

        byte[] pdf = mockMvc.perform(get("/portfolio/policies/" + policy + "/documents/" + id)
                        .with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Policy schedule", policy, "Gosego Ramotswa", "Term Life", "Active",
                    "BWP 250,000", "BWP 180 a month", "gosego@example.com", "Kopano Ramotswa", "Spouse",
                    "Bank Account ending 4567", "1. Your policy", "2. Your premium", "5. Beneficiaries",
                    "Important terms", "Page 1 of");
            // Full account numbers are never printed, and a schedule is a contract, not an audit trail.
            assertThat(text).doesNotContain("62004567", "Created by admin");
        }

        mockMvc.perform(get("/portfolio/policies").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + policy + "')].documents.length()").value(1));
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Schedule Generated')].performedBy").value(Matchers.hasItem("admin")));
    }

    @Test
    void aPolicyHasOneScheduleOnly() throws Exception {
        String policy = customerPolicy("Mpho Dintwe", "mpho");

        mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule").with(signedIn("admin", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("already has its policy schedule")));
        // Not from the customer portal either.
        mockMvc.perform(post("/portfolio/shop/policies/" + policy + "/documents/schedule")
                        .with(signedIn("mpho", "password1")))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/portfolio/policies/" + policy + "/documents").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void oldLayoutSchedulesAreReplacedByOneCurrentSchedule() throws Exception {
        String policy = customerPolicy("Kagiso Moremi", "kagiso");
        mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        // As the old button left it: three schedules in the old layout.
        Long policyId = jdbc.queryForObject("select max(policy_id) from ins_policy_document", Long.class);
        jdbc.update("update ins_policy_document set layout_version = 1 where policy_id = ?", policyId);
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert into ins_policy_document (policy_id, document_type, file_name, content_type, size_bytes,"
                    + " storage_path, uploaded_by, uploaded_at, layout_version) select policy_id, document_type,"
                    + " file_name, content_type, size_bytes, 'gone/old.pdf', uploaded_by, uploaded_at, 1"
                    + " from ins_policy_document where policy_id = ? and storage_path <> 'gone/old.pdf'", policyId);
        }
        assertThat(documents.policiesWithOldSchedules()).contains(policyId);

        assertThat(documents.replaceOldSchedules(policyId)).isEqualTo(3);

        assertThat(documents.policiesWithOldSchedules()).doesNotContain(policyId);
        assertThat(documents.replaceOldSchedules(policyId)).isZero();
        // Blueprint §8: the old-layout schedules are kept as superseded, linked to the one current schedule.
        String list = mockMvc.perform(get("/portfolio/policies/" + policy + "/documents").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[?(@.status == 'CURRENT')]", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[0].supersededReason").value(org.hamcrest.Matchers.startsWith("Reissued in the current")))
                .andExpect(jsonPath("$[3].status").value("CURRENT"))
                .andExpect(jsonPath("$[3].uploadedBy").value("admin"))
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(list, "$[0].supersededBy")).isEqualTo((Integer) JsonPath.read(list, "$[3].id"));
        byte[] pdf = mockMvc.perform(get("/portfolio/policies/" + policy + "/documents/" + JsonPath.read(list, "$[3].id"))
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("1. Your policy", "Important terms");
        }
        // Still one per policy afterwards.
        mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule").with(signedIn("admin", "password")))
                .andExpect(status().isConflict());
    }

    @Test
    void aCorrectionIsAControlledReplacementAndEveryDownloadIsCheckedAndLogged() throws Exception {
        // Solution Blueprint §8: a correction creates a replacement with a reason that links to the superseded record,
        // issued files are checked against their hash, and access/downloads are audited.
        String policy = customerPolicy("Neo Sebina", "neosebina");
        String first = mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CURRENT"))
                .andExpect(jsonPath("$[0].sha256").value(Matchers.matchesPattern("[0-9a-f]{64}")))
                .andReturn().getResponse().getContentAsString();
        Integer original = JsonPath.read(first, "$[0].id");

        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/documents/" + original + "/reissue"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/documents/" + original + "/reissue"), "onboarding",
                        "{\"reason\":\"Not my job\"}"))
                .andExpect(status().isForbidden());
        String reissued = mockMvc.perform(json(post("/portfolio/policies/" + policy + "/documents/" + original
                        + "/reissue"), "admin", "{\"reason\":\"Beneficiary name corrected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[0].supersededReason").value("Beneficiary name corrected"))
                .andExpect(jsonPath("$[1].status").value("CURRENT"))
                .andReturn().getResponse().getContentAsString();
        Integer current = JsonPath.read(reissued, "$[1].id");
        assertThat((Integer) JsonPath.read(reissued, "$[0].supersededBy")).isEqualTo(current);
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Schedule Generated')].details").value(Matchers.hasItem(
                        Matchers.containsString("Beneficiary name corrected"))));
        // Only the current schedule can be reissued; the superseded file is kept and still opens.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/documents/" + original + "/reissue"), "admin",
                        "{\"reason\":\"Again\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/portfolio/policies/" + policy + "/documents/" + original).with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/shop/policies/" + policy + "/documents/" + current)
                        .with(signedIn("neosebina", "password1")))
                .andExpect(status().isOk());

        // The download log: who, which channel, which file. Auditors read it; others do not.
        String log = mockMvc.perform(get("/portfolio/policies/" + policy + "/document-access")
                        .with(signedIn("compliance", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<String>>read(log, "$[*].channel")).containsExactly("PORTAL", "STAFF");
        assertThat(JsonPath.<java.util.List<String>>read(log, "$[*].username")).containsExactly("neosebina", "admin");
        mockMvc.perform(get("/portfolio/policies/" + policy + "/document-access").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());

        // A stored file that no longer matches its recorded hash is refused, never served.
        jdbc.update("update ins_policy_document set sha256 = ? where id = ?", "0".repeat(64), current);
        mockMvc.perform(get("/portfolio/policies/" + policy + "/documents/" + current).with(signedIn("admin", "password")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("failed its integrity check")));
    }

    @Test
    void customersGenerateAndOpenSchedulesOnTheirOwnPoliciesOnly() throws Exception {
        String policy = customerPolicy("Thabo Kebonang", "thabo");
        customerPolicy("Neo Seleka", "neoseleka");

        String shop = mockMvc.perform(post("/portfolio/shop/policies/" + policy + "/documents/schedule")
                        .with(signedIn("thabo", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies[0].documents[0].type").value("Policy schedule"))
                .andExpect(jsonPath("$.policies[0].documents[0].uploadedBy").value("thabo"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(shop, "$.policies[0].documents[0].id");

        mockMvc.perform(get("/portfolio/shop/policies/" + policy + "/documents/" + id).with(signedIn("thabo", "password1")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"));
        // Another customer: answered as if the policy did not exist.
        mockMvc.perform(get("/portfolio/shop/policies/" + policy + "/documents/" + id)
                        .with(signedIn("neoseleka", "password1")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/portfolio/shop/policies/" + policy + "/documents/schedule")
                        .with(signedIn("neoseleka", "password1")))
                .andExpect(status().isNotFound());
        // A customer never reaches the staff endpoints.
        mockMvc.perform(get("/portfolio/policies/" + policy + "/documents/" + id).with(signedIn("thabo", "password1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingNeedsReadPolicyAndGeneratingNeedsAPolicyWritePermission() throws Exception {
        String policy = customerPolicy("Lorato Kgosi", "lorato");

        mockMvc.perform(get("/portfolio/policies/" + policy + "/documents").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule").with(signedIn("onboarding", "password")))
                .andExpect(status().isForbidden());
        // The assessor reads policies but does not issue or service them.
        mockMvc.perform(get("/portfolio/policies/" + policy + "/documents").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/portfolio/policies/" + policy + "/documents/schedule").with(signedIn("assessor", "password")))
                .andExpect(status().isForbidden());
    }

    /** A verified customer with a login and an Active policy with one beneficiary, issued by admin. */
    private String customerPolicy(String name, String username) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = mockMvc.perform(json(post("/portfolio/policies"), "admin",
                        "{\"name\":\"" + name + "\",\"product\":\"Term Life\",\"amount\":250000,\"premium\":180}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(policy, "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + ref + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        String surname = name.substring(name.indexOf(' ') + 1);
        mockMvc.perform(json(post("/portfolio/policies/" + ref + "/beneficiaries"), "admin",
                        "{\"fullName\":\"Kopano " + surname + "\",\"relationship\":\"Spouse\",\"mobile\":\"+267 72 000 000\","
                                + "\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62004567\"}"))
                .andExpect(status().isOk());
        return ref;
    }
}
