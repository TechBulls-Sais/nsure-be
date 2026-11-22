package com.nsure.security;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.security.session.SessionTokenService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SH11 scoping (work package e): a suspended intermediary's portal closes at once, also for a session already open,
 * as does an ended or removed portal link (AT-SH11-02, AT-SH01-03); staff without a KYC role see identity numbers
 * masked (spec 04 operations); a customer recovers a forgotten password with a code to the registered mobile, which
 * ends the old sessions, and the answer never says whether an account exists (NS-SH11-001).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-access-scoping;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class AccessScopingApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionTokenService sessions;

    @Test
    void suspendingAnIntermediaryClosesItsPortalForAnOpenSession() throws Exception {
        String agent = jdbc.queryForObject("select scope_ref from portal_account where username = 'agent'", String.class);
        String token = sessions.issue(user("agent")).token();
        mockMvc.perform(get("/ops/portal").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        jdbc.update("update dist_intermediary set status = 'SUSPENDED' where record_ref = ?", agent);
        // The same session, on its next request: closed.
        mockMvc.perform(get("/ops/portal").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("SUSPENDED")));
        mockMvc.perform(post("/ops/portal/requests").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"requestType\":\"EVIDENCE\",\"detail\":\"x\"}"))
                .andExpect(status().isForbidden());
        jdbc.update("update dist_intermediary set status = 'ACTIVE' where record_ref = ?", agent);
        mockMvc.perform(get("/ops/portal").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // An end date on the portal link: once passed, no access.
        mockMvc.perform(put("/ops/portal-accounts").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"lender\",\"portalType\":\"LENDER\","
                                + "\"scopeRef\":\"LND-0001\",\"validUntil\":\"" + LocalDate.now().minusDays(1) + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/ops/portal").with(signedIn("lender", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("ended")));
        mockMvc.perform(put("/ops/portal-accounts").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"lender\",\"portalType\":\"LENDER\","
                                + "\"scopeRef\":\"LND-0001\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/ops/portal").with(signedIn("lender", "password"))).andExpect(status().isOk());
        // Removing the link takes effect on the next request.
        mockMvc.perform(delete("/ops/portal-accounts/employer").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Broker relationship ended\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/ops/portal").with(signedIn("employer", "password"))).andExpect(status().isForbidden());
        mockMvc.perform(delete("/ops/portal-accounts/agent").with(signedIn("support", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void identityNumbersAreMaskedForStaffWithoutAKycRole() throws Exception {
        mockMvc.perform(get("/portfolio/customers").param("search", "CUS-1001").with(signedIn("support", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].poiNumber").value("******404"));
        mockMvc.perform(get("/portfolio/customers").param("search", "CUS-1001").with(signedIn("onboarding", "password")))
                .andExpect(jsonPath("$[0].poiNumber").value("880618404"));
    }

    @Test
    void aCustomerResetsAForgottenPasswordWithACodeToTheRegisteredMobile() throws Exception {
        mockMvc.perform(post("/portfolio/customers").with(signedIn("admin", "password")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kefilwe Forgot\",\"email\":\"kefilwe@example.com\",\"phone\":\"+267 71 444 555\","
                                + "\"username\":\"kefilwe\",\"password\":\"password1\"}"))
                .andExpect(status().isOk());
        String old = sessions.issue(user("kefilwe")).token();
        String body = mockMvc.perform(post("/public/password-reset/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"kefilwe\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // An unknown login gets the same shape of answer, and its challenge resets nothing.
        String unknown = mockMvc.perform(post("/public/password-reset/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody-here\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").exists())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/public/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge\":\"" + JsonPath.read(unknown, "$.challenge") + "\",\"code\":\"123456\","
                                + "\"password\":\"another-pass1\"}"))
                .andExpect(status().isUnprocessableEntity());
        // Staff are not recovered here.
        String staff = mockMvc.perform(post("/public/password-reset/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/public/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge\":\"" + JsonPath.read(staff, "$.challenge") + "\",\"code\":\"123456\","
                                + "\"password\":\"another-pass1\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/public/password-reset").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge\":\"" + JsonPath.read(body, "$.challenge") + "\",\"code\":\""
                                + JsonPath.read(body, "$.demoCode") + "\",\"password\":\"new-password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASSWORD_RESET"));
        // The old session ended; the new password works.
        mockMvc.perform(get("/portfolio/shop").header(HttpHeaders.AUTHORIZATION, "Bearer " + old))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/portfolio/shop").with(signedIn("kefilwe", "new-password1"))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where action = ?", Integer.class,
                "Password reset with a recovery code")).isEqualTo(1);
    }

    @Test
    void healthEvidenceNeedsItsOwnPermissionBesideTheClaims() throws Exception {
        String customer = JsonPath.read(mockMvc.perform(post("/portfolio/customers").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bonolo Health\",\"email\":"
                                + "\"bonolo.h@example.com\",\"phone\":\"+267 71 444 666\",\"username\":\"bonoloh\","
                                + "\"password\":\"password1\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(post("/portfolio/policies").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Bonolo Health\",\"product\":"
                                + "\"Term Life\",\"amount\":50000,\"premium\":50}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/policies/" + policy + "/payments").with(signedIn("admin", "password"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"method\":\"Bank EFT\"}")).andExpect(status().isOk());
        String claim = JsonPath.read(mockMvc.perform(post("/portfolio/shop/claims").with(signedIn("bonoloh", "password1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"policy\":\"" + policy + "\",\"claimantName\":"
                                + "\"Next Of Kin\",\"claimantMobile\":\"+267 72 000 000\",\"claimantRelationship\":\"Spouse\","
                                + "\"eventDate\":\"" + LocalDate.now().minusDays(1) + "\",\"description\":\"In hospital.\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.claims[0].id");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                        "/portfolio/claims/" + claim + "/documents")
                        .file(new org.springframework.mock.web.MockMultipartFile("file", "report.pdf", "application/pdf",
                                "%PDF-1.4\n% report\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                        .param("type", "Medical report").with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        Integer upload = jdbc.queryForObject("select max(id) from doc_quarantine where owner_ref = ?", Integer.class, claim);
        mockMvc.perform(post("/portfolio/document-quarantine/" + upload + "/release").with(signedIn("assessor2", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Readable medical report\"}"))
                .andExpect(status().isOk());
        Long document = jdbc.queryForObject("select max(id) from ins_claim_document where file_name = 'report.pdf'",
                Long.class);
        // Customer Support reads the claim but not its health evidence; the assessor and the customer do.
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/" + document).with(signedIn("support", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("health evidence")));
        mockMvc.perform(get("/portfolio/claims/" + claim + "/documents/" + document).with(signedIn("assessor", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/shop/claims/" + claim + "/documents/" + document)
                        .with(signedIn("bonoloh", "password1")))
                .andExpect(status().isOk());
    }

    @Test
    void approvalAuthorityIsDelegatedForATimeAndNeverAboveTheDelegatorsOwn() throws Exception {
        String until = LocalDate.now().plusDays(10).toString();
        mockMvc.perform(get("/admin/approval-delegations").with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.myLimit").value("50000.00"));
        // An assessor limited to 50,000 cannot lend more than that.
        mockMvc.perform(post("/admin/approval-delegations").with(signedIn("assessor2", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delegate\":\"assessor\",\"maxAmount\":80000,"
                                + "\"validTo\":\"" + until + "\",\"reason\":\"Cover my leave\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/approval-delegations").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delegate\":\"assessor\",\"maxAmount\":120000,"
                                + "\"validTo\":\"" + LocalDate.now().plusDays(60) + "\",\"reason\":\"Cover my leave\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/approval-delegations").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delegate\":\"support\",\"maxAmount\":120000,"
                                + "\"validTo\":\"" + until + "\",\"reason\":\"Cover my leave\"}"))
                .andExpect(status().isUnprocessableEntity());
        String body = mockMvc.perform(post("/admin/approval-delegations").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delegate\":\"assessor\",\"maxAmount\":120000,"
                                + "\"validTo\":\"" + until + "\",\"reason\":\"Head of claims on leave\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/admin/approval-delegations").with(signedIn("assessor", "password")))
                .andExpect(jsonPath("$.myLimit").value("120000.00"));
        Integer id = JsonPath.read(body, "$.delegations[0].id");
        mockMvc.perform(post("/admin/approval-delegations/" + id + "/revoke").with(signedIn("assessor2", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/approval-delegations/" + id + "/revoke").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/approval-delegations").with(signedIn("assessor", "password")))
                .andExpect(jsonPath("$.myLimit").value("50000.00"));
    }

    private com.nsure.security.domain.AppUser user(String username) {
        return (com.nsure.security.domain.AppUser) userDetails.loadUserByUsername(username);
    }

    @Autowired
    private org.springframework.security.core.userdetails.UserDetailsService userDetails;
}
