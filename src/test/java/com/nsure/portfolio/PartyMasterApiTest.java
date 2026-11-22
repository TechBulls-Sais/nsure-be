package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SH01 party master (work package e): fuzzy duplicate detection and a steward-approved merge that moves every
 * transaction reference (NS-SH01-002, AT-SH01-02); KYC screening where a hit needs an authorised review and a
 * provider failure is never clearance, with the verification source and expiry (NS-SH01-004); contact changes proved
 * on the new contact and approved by a second person (NS-SH01-005, PartyUpdated, ContactVerified); dated roles and a
 * delegation that stops working when it expires (NS-SH01-003, AT-SH01-03); the permission-filtered customer view
 * (NS-SH01-006).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-party-master;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PartyMasterApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aFuzzyDuplicateIsMergedOnlyOnceADataStewardApprovesAndEveryReferenceMoves() throws Exception {
        String keep = customer("Boitumelo Kgari", "boitumelo.k@example.com", "+267 71 300 001", "1991-03-04", null);
        String gone = customer("Boitumelo Kgare", "b.kgare@example.com", "+267 71 300 002", "1991-03-04", null);
        String namesake = customer("Boitumelo Kgari", "other.bk@example.com", "+267 71 300 003", "1980-01-01", null);
        verify(gone);
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + gone + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String caseRef = JsonPath.read(mockMvc.perform(json(post("/service/cases"), "support", "{\"customerRef\":\""
                        + gone + "\",\"caseType\":\"SERVICE_REQUEST\",\"category\":\"Policy change\","
                        + "\"subject\":\"Update my details\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.case.ref");

        // A similar name with the same date of birth is likely; the same name alone is only possible (AT-SH01-01).
        String duplicates = mockMvc.perform(get("/portfolio/parties/duplicates").with(signedIn("onboarding", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> likely = JsonPath.read(duplicates, "$[?(@.first.ref == '" + keep + "' && @.second.ref == '" + gone
                + "')].confidence");
        assertThat(likely).containsExactly("LIKELY");
        List<List<String>> reasons = JsonPath.read(duplicates, "$[?(@.first.ref == '" + keep + "' && @.second.ref == '"
                + gone + "')].reasons");
        assertThat(reasons.get(0)).anyMatch(r -> r.startsWith("Similar name")).contains("Same date of birth");
        List<String> possible = JsonPath.read(duplicates, "$[?(@.first.ref == '" + keep + "' && @.second.ref == '"
                + namesake + "')].confidence");
        assertThat(possible).containsExactly("POSSIBLE");

        String request = JsonPath.read(mockMvc.perform(json(post("/portfolio/parties/merge-requests"), "onboarding",
                        "{\"survivingRef\":\"" + keep + "\",\"mergedRef\":\"" + gone + "\",\"reason\":\"Same Omang seen"
                                + " at the branch; misspelt surname\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.matchDetail").value(containsString("LIKELY")))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        // Nothing moved yet.
        assertThat(jdbc.queryForObject("select customer_ref from ins_policy where record_ref = ?", String.class, policy))
                .isEqualTo(gone);
        mockMvc.perform(json(post("/portfolio/parties/merge-requests"), "onboarding", "{\"survivingRef\":\"" + keep
                        + "\",\"mergedRef\":\"" + gone + "\",\"reason\":\"Asked twice by mistake\"}"))
                .andExpect(status().isConflict());
        // The requester is no data steward; a data steward who asked cannot approve their own request either.
        mockMvc.perform(json(post("/portfolio/parties/merge-requests/" + request + "/approve"), "onboarding", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/parties/merge-requests/" + request + "/approve"), "compliance",
                        "{\"note\":\"Checked both records\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("compliance"))
                .andExpect(jsonPath("$.movedDetail").value(containsString("1 service cases")));
        assertThat(jdbc.queryForObject("select customer_ref from ins_policy where record_ref = ?", String.class, policy))
                .isEqualTo(keep);
        assertThat(jdbc.queryForObject("select customer_ref from svc_case where record_ref = ?", String.class, caseRef))
                .isEqualTo(keep);
        // The merged reference still resolves, to the survivor (both references are kept).
        mockMvc.perform(get("/portfolio/parties/resolve/" + gone).with(signedIn("support", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(keep))
                .andExpect(jsonPath("$.merged").value(true));
        mockMvc.perform(get("/platform/parties/merges").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.mergedRef == '" + gone + "')].survivingRef").value(hasItem(keep)));
        mockMvc.perform(json(post("/portfolio/parties/merge-requests/" + request + "/approve"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where event_type = 'PartyMerged' and"
                + " aggregate_ref = ?", Integer.class, keep)).isEqualTo(1);

        // A steward may also reject, with a note; nothing moves.
        String other = customer("Kelebogile Moeng", "kmoeng@example.com", "+267 71 300 004", "1970-05-05", null);
        String rejected = JsonPath.read(mockMvc.perform(json(post("/portfolio/parties/merge-requests"), "admin",
                        "{\"survivingRef\":\"" + keep + "\",\"mergedRef\":\"" + other + "\",\"reason\":\"Looks related"
                                + " to me somehow\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/portfolio/parties/merge-requests/" + rejected + "/approve"), "admin", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/parties/merge-requests/" + rejected + "/reject"), "compliance",
                        "{\"note\":\"Different people\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void aScreeningHitNeedsAnAuthorisedReviewAndAProviderFailureIsNeverClearance() throws Exception {
        // The simulated watchlist (demo seed) has a PEP entry for this fictional name.
        String pep = customer("Kgosi Rantao", "k.rantao2@example.com", "+267 71 300 010", "1970-01-01", null);
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/kyc"), "admin", "{\"decision\":\"VERIFIED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("authorised reviewer")));
        mockMvc.perform(get("/portfolio/customers/" + pep + "/kyc-record").with(signedIn("support", "password")))
                .andExpect(jsonPath("$.screeningStatus").value("HIT"))
                .andExpect(jsonPath("$.screeningDetail").value(containsString("PEP")))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"));
        // The hit is in the authorised reviewers' queue, never the screener's.
        mockMvc.perform(get("/ops/queue").with(signedIn("onboarding", "password")))
                .andExpect(jsonPath("$[?(@.key == 'SCREENING_HIT:" + pep + "')].canAct").value(hasItem(true)));
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/screening/review"), "admin",
                        "{\"decision\":\"CLEARED\",\"reason\":\"Different person: date of birth differs\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/screening/review"), "onboarding",
                        "{\"decision\":\"CLEARED\",\"reason\":\"Different person: the listed councillor is older\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("HIT_CLEARED"));
        mockMvc.perform(json(post("/portfolio/customers/" + pep + "/kyc"), "admin",
                        "{\"decision\":\"VERIFIED\",\"source\":\"DOCUMENT_REVIEW\",\"sourceReference\":\"Omang seen in"
                                + " branch\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/customers/" + pep + "/kyc-record").with(signedIn("support", "password")))
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.verificationSource").value("DOCUMENT_REVIEW"))
                .andExpect(jsonPath("$.verificationReference").value("Omang seen in branch"))
                .andExpect(jsonPath("$.verifiedBy").value("admin"))
                .andExpect(jsonPath("$.expiresOn").value(LocalDate.now().plusYears(5).toString()));

        // The simulated provider cannot answer for this name: that is not a clearance.
        String down = customer("Kelesitse Ramotswa", "k.ramotswa2@example.com", "+267 71 300 011", "1988-02-02", null);
        mockMvc.perform(json(post("/portfolio/customers/" + down + "/kyc"), "admin", "{\"decision\":\"VERIFIED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("not a clearance")));
        mockMvc.perform(post("/portfolio/customers/" + down + "/screening").with(signedIn("onboarding", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screeningStatus").value("PROVIDER_ERROR"));
        // A clear customer is screened as part of the verification, with a default source.
        String clear = customer("Onkemetse Clear", "o.clear@example.com", "+267 71 300 012", "1990-01-01", null);
        verify(clear);
        mockMvc.perform(get("/portfolio/customers/" + clear + "/kyc-record").with(signedIn("support", "password")))
                .andExpect(jsonPath("$.screeningStatus").value("CLEAR"))
                .andExpect(jsonPath("$.verificationSource").value("STAFF_REVIEW"));
        // A verification falling due for its periodic review is work in the queue.
        jdbc.update("update pty_kyc set review_due_at = ? where customer_ref = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(1)), clear);
        mockMvc.perform(get("/ops/queue").with(signedIn("onboarding", "password")))
                .andExpect(jsonPath("$[?(@.key == 'KYC_RENEWAL:" + clear + "')].workflowName")
                        .value(hasItem("KYC periodic review")));
    }

    @Test
    void aContactChangeIsProvedOnTheNewContactAndApprovedBySomeoneElse() throws Exception {
        String ref = customer("Refilwe Contact", "refilwe@example.com", "+267 71 300 020", "1985-08-08", "refilwec");
        String body = mockMvc.perform(json(post("/portfolio/customers/" + ref + "/contact-changes"), "onboarding",
                        "{\"field\":\"EMAIL\",\"value\":\"refilwe.new@example.com\",\"reason\":\"Customer called\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_CODE"))
                .andExpect(jsonPath("$.newValue").value("re***@example.com"))
                .andExpect(jsonPath("$.demoCode").value("123456"))
                .andReturn().getResponse().getContentAsString();
        String change = JsonPath.read(body, "$.recordRef");
        String challenge = JsonPath.read(body, "$.challenge");
        mockMvc.perform(json(post("/portfolio/contact-changes/" + change + "/approve"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/contact-changes/" + change + "/verify"), "onboarding",
                        "{\"challenge\":\"" + challenge + "\",\"code\":\"000000\"}"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(json(post("/portfolio/contact-changes/" + change + "/verify"), "onboarding",
                        "{\"challenge\":\"" + challenge + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        assertThat(events("ContactVerified", ref)).isEqualTo(1);
        mockMvc.perform(get("/ops/queue").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.key == 'CONTACT_CHANGE:" + change + "')].canAct").value(hasItem(true)));
        mockMvc.perform(json(post("/portfolio/contact-changes/" + change + "/approve"), "onboarding", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/contact-changes/" + change + "/approve"), "admin", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(jdbc.queryForObject("select email from ins_customer where record_ref = ?", String.class, ref))
                .isEqualTo("refilwe.new@example.com");
        assertThat(events("PartyUpdated", ref)).isEqualTo(1);

        // The customer asks from the portal; the code goes to the new phone, then staff approve.
        String own = mockMvc.perform(json(post("/portfolio/shop/contact-changes"), "refilwec",
                        "{\"field\":\"PHONE\",\"value\":\"+267 72 300 021\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channel").value("PORTAL"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/shop/contact-changes/" + JsonPath.read(own, "$.recordRef") + "/verify"),
                        "refilwec", "{\"challenge\":\"" + JsonPath.read(own, "$.challenge") + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"));
        mockMvc.perform(json(post("/portfolio/contact-changes/" + JsonPath.read(own, "$.recordRef") + "/reject"), "admin",
                        "{\"note\":\"Customer withdrew the request\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // Addresses keep their history: a new residential address ends the previous one.
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/addresses"), "onboarding",
                        "{\"kind\":\"RESIDENTIAL\",\"line1\":\"Plot 10\",\"city\":\"Maun\",\"validFrom\":\"2025-01-01\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/addresses"), "onboarding",
                        "{\"kind\":\"RESIDENTIAL\",\"line1\":\"Plot 99\",\"city\":\"Gaborone\",\"validFrom\":\"2026-06-01\","
                                + "\"verification\":\"DOCUMENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addresses[0].line1").value("Plot 99"))
                .andExpect(jsonPath("$.addresses[1].validTo").value("2026-05-31"));
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/identifiers"), "onboarding",
                        "{\"type\":\"Passport\",\"number\":\"BW1234567\",\"country\":\"BW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identifiers[?(@.type == 'Passport')].number").value(hasItem("******567")));
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/identifiers"), "onboarding",
                        "{\"type\":\"Omang\",\"number\":\"123456789\",\"country\":\"ZA\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/addresses"), "support",
                        "{\"kind\":\"RESIDENTIAL\",\"line1\":\"x\",\"city\":\"y\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aDelegationGivesPortalAccessOnlyUntilItExpires() throws Exception {
        String owner = customer("Masego Owner", "masego.o@example.com", "+267 71 300 030", "1960-02-02", null);
        String delegate = customer("Lorato Delegate", "lorato.d@example.com", "+267 71 300 031", "1990-02-02",
                "loratod");
        verify(owner);
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + owner + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/customers/" + delegate + "/relationships"), "onboarding",
                        "{\"role\":\"DELEGATE\",\"relatedKind\":\"CUSTOMER\",\"relatedRef\":\"" + owner + "\","
                                + "\"authority\":\"VIEW\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("end date")));
        String rel = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers/" + delegate + "/relationships"),
                        "onboarding", "{\"role\":\"DELEGATE\",\"relatedKind\":\"CUSTOMER\",\"relatedRef\":\"" + owner
                                + "\",\"authority\":\"VIEW\",\"validTo\":\"" + LocalDate.now().plusDays(30) + "\","
                                + "\"note\":\"Daughter helps with the policy\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.recorded[0].ref");
        mockMvc.perform(get("/portfolio/customers/" + owner + "/relationships").with(signedIn("support", "password")))
                .andExpect(jsonPath("$.derived[?(@.role == 'OWNER')].relatedRef").value(hasItem(policy)))
                .andExpect(jsonPath("$.recorded[0].current").value(true));
        mockMvc.perform(get("/portfolio/shop/delegated").with(signedIn("loratod", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].recordRef").value(policy))
                .andExpect(jsonPath("$[0].authority").value("VIEW"));
        mockMvc.perform(get("/portfolio/shop/delegated/" + policy).with(signedIn("loratod", "password1")))
                .andExpect(status().isOk());

        // AT-SH01-03: once the delegation has expired the relationship stays in the history but gives no access.
        jdbc.update("update pty_relationship set valid_to = ? where record_ref = ?",
                java.sql.Date.valueOf(LocalDate.now().minusDays(1)), rel);
        mockMvc.perform(get("/portfolio/shop/delegated").with(signedIn("loratod", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/portfolio/shop/delegated/" + policy).with(signedIn("loratod", "password1")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/portfolio/customers/" + owner + "/relationships").with(signedIn("support", "password")))
                .andExpect(jsonPath("$.recorded[0].current").value(false));
    }

    @Test
    void theCustomerViewShowsOnlyWhatTheReaderMaySee() throws Exception {
        String ref = customer("Tebogo Viewed", "tebogo.v@example.com", "+267 71 300 040", "1975-07-07", null);
        verify(ref);
        mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + ref
                + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/portfolio/customers/" + ref + "/view").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible.policies").value(true))
                .andExpect(jsonPath("$.policies.length()").value(1))
                .andExpect(jsonPath("$.premiums").exists())
                .andExpect(jsonPath("$.kyc.kycStatus").value("VERIFIED"));
        // Onboarding reads customers but not policies, claims, payments or messages.
        mockMvc.perform(get("/portfolio/customers/" + ref + "/view").with(signedIn("onboarding", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visible.policies").value(false))
                .andExpect(jsonPath("$.policies").doesNotExist())
                .andExpect(jsonPath("$.claims").doesNotExist())
                .andExpect(jsonPath("$.messages").doesNotExist());
        mockMvc.perform(get("/portfolio/customers/" + ref + "/view").with(signedIn("customer", "password")))
                .andExpect(status().isForbidden());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private int events(String type, String ref) {
        Integer n = jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Integer.class, type, ref);
        return n == null ? 0 : n;
    }

    private void verify(String ref) throws Exception {
        mockMvc.perform(json(post("/portfolio/customers/" + ref + "/kyc"), "admin", "{}")).andExpect(status().isOk());
    }

    private String customer(String name, String email, String phone, String dob, String username) throws Exception {
        String login = username == null ? "" : ",\"username\":\"" + username + "\",\"password\":\"password1\"";
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + email + "\",\"phone\":\"" + phone + "\",\"dateOfBirth\":\"" + dob + "\""
                        + login + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = List.of("refilwec", "loratod").contains(user) ? "password1" : "password";
        return request.with(signedIn(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
