package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxEvent;
import com.nsure.testsupport.GroupSchemeSetup;
import jakarta.persistence.EntityManager;
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
 * Group member underwriting (NS-GL03-001..006, AT-GL03-02/03, GL03 events): evidence generated for referrals and
 * kept from the employer, per-benefit decisions with loading, exclusions and effective date, the second underwriter's
 * authority, the member's acceptance of modified terms and its expiry, the deferred-cover path, partial cover only
 * where the scheme allows it, category transfers and aggregate exposure.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-uw;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupMemberUnderwritingApiTest {

    private static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";
    private static final String CONDITIONS = "\"conditions\":[\"SIGNED_PROPOSAL\",\"SIGNED_CENSUS\",\"FIRST_PREMIUM_OR_DEFERRAL\"]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void evidenceIsGeneratedKeptFromTheEmployerAndNeededBeforeAcceptance() throws Exception {
        String scheme = scheme("{}");
        // 4 x 600,000 = 2,400,000 asked for, 500,000 free: 1,900,000 referred.
        commit(scheme, "UW-1", "U1,Una,Large,1980-01-01,STAFF,600000,JOIN,2026-01-01");
        Integer member = memberId(scheme, "U1");
        mockMvc.perform(get("/group/members/" + member + "/evidence").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[*].requirement").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "MEDICAL_QUESTIONNAIRE", "MEDICAL_EXAMINATION", "BLOOD_PROFILE", "FINANCIAL_EVIDENCE")))
                .andExpect(jsonPath("$[?(@.requirement == 'MEDICAL_EXAMINATION')].restricted").value(hasItem(true)))
                .andExpect(jsonPath("$[?(@.requirement == 'FINANCIAL_EVIDENCE')].restricted").value(hasItem(false)));
        // AT-GL03-03: the employer reaches neither the evidence nor the underwriting file.
        mockMvc.perform(get("/group/members/" + member + "/evidence").with(signedIn("employer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/group/members/" + member + "/underwriting-file").with(signedIn("employer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "admin", "{\"decision\":\"ACCEPT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Evidence is outstanding")));
        mockMvc.perform(get("/group/underwriting-queue").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.memberKey == 'U1')].evidenceOutstanding").value(hasItem(4)));
        String evidence = mockMvc.perform(get("/group/members/" + member + "/evidence").with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer financial = ((List<Integer>) JsonPath.read(evidence, "$[?(@.requirement == 'FINANCIAL_EVIDENCE')].id")).get(0);
        mockMvc.perform(json(post("/group/member-evidence/" + financial), "admin", "{\"status\":\"WAIVED\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/group/member-evidence/" + financial), "admin", "{\"status\":\"WAIVED\",\"note\":\"Salary verified from payroll\"}"))
                .andExpect(jsonPath("$.status").value("WAIVED"));
        GroupSchemeSetup.receiveEvidence(mockMvc, member);

        // Above the 2,000,000 authority: waits for a second underwriter; cover does not change meanwhile.
        int authorised = eventsFor(Events.MEMBER_COVER_AUTHORISED, scheme).size();
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting"), "admin",
                        "{\"decision\":\"ACCEPT\",\"reason\":\"Evidence clear\",\"effectiveDate\":\"2026-02-01\"}"))
                .andExpect(jsonPath("$.status").value("UNDERWRITING_PENDING"))
                .andExpect(jsonPath("$.acceptedCover").value(500000.0));
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting/authority"), "admin",
                        "{\"approve\":true,\"note\":\"mine\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group/members/" + member + "/underwriting/authority"), "secadmin",
                        "{\"approve\":true,\"note\":\"Within treaty capacity\"}"))
                .andExpect(jsonPath("$.member.status").value("ACTIVE"))
                .andExpect(jsonPath("$.member.acceptedCover").value(2400000.0))
                .andExpect(jsonPath("$.decisions[0].status").value("FINAL"))
                .andExpect(jsonPath("$.decisions[0].authorisedBy").value("secadmin"))
                .andExpect(jsonPath("$.decisions[0].effectiveDate").value("2026-02-01"));
        assertThat(eventsFor(Events.MEMBER_COVER_AUTHORISED, scheme)).hasSize(authorised + 1);
    }

    @Test
    void modifiedTermsAreAnOfferTheMemberAcceptsOrThatExpires() throws Exception {
        String scheme = scheme("{}");
        commit(scheme, "OF-1", "O1,Oli,Offer,1980-01-01,STAFF,200000,JOIN,2026-01-01\n"
                + "O2,Ola,Expiry,1981-01-01,STAFF,200000,JOIN,2026-01-01");
        Integer o1 = memberId(scheme, "O1");
        Integer o2 = memberId(scheme, "O2");
        GroupSchemeSetup.receiveEvidence(mockMvc, o1);
        GroupSchemeSetup.receiveEvidence(mockMvc, o2);
        // Per benefit: life cover accepted with a 25% loading and an exclusion.
        mockMvc.perform(json(post("/group/members/" + o1 + "/underwriting"), "admin",
                        "{\"decision\":\"ACCEPT\",\"loadingPercent\":25,\"exclusions\":\"Hazardous sports\",\"reason\":\"Raised BMI\"}"))
                .andExpect(jsonPath("$.status").value("UNDERWRITING_PENDING"));
        mockMvc.perform(json(post("/group/members/" + o1 + "/underwriting"), "admin", "{\"decision\":\"DECLINE\",\"reason\":\"x\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/group/members/" + o1 + "/underwriting/offer-response"), "admin",
                        "{\"accepted\":true,\"note\":\"Signed acceptance received from the member\"}"))
                .andExpect(jsonPath("$.member.status").value("ACTIVE"))
                .andExpect(jsonPath("$.member.acceptedCover").value(800000.0))
                .andExpect(jsonPath("$.member.loadingPercent").value(25.0))
                .andExpect(jsonPath("$.member.exclusions").value("Hazardous sports"))
                // 800,000 at 1 per mille plus 25%.
                .andExpect(jsonPath("$.member.monthlyPremium").value(1000.0))
                .andExpect(jsonPath("$.decisions[0].memberResponse").value("ACCEPTED"));

        // An offer left unanswered past its expiry lapses (MemberDecisionExpired); cover stays pending.
        mockMvc.perform(json(post("/group/members/" + o2 + "/underwriting"), "admin",
                        "{\"decision\":\"ACCEPT\",\"loadingPercent\":50,\"reason\":\"History\"}"))
                .andExpect(status().isOk());
        jdbc.update("update grp_member_decision set offer_expires_on = ? where member_id = ?",
                java.sql.Date.valueOf(LocalDate.now().minusDays(1)), o2);
        mockMvc.perform(post("/group/underwriting/expiry-run").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.expired").value(1));
        assertThat(events(Events.MEMBER_DECISION_EXPIRED, "O2")).isNotEmpty();
        mockMvc.perform(get("/group/members/" + o2 + "/underwriting-file").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.member.status").value("UNDERWRITING_PENDING"))
                .andExpect(jsonPath("$.decisions[0].status").value("EXPIRED"));
        // A new decision can then be made.
        mockMvc.perform(json(post("/group/members/" + o2 + "/underwriting"), "admin", "{\"decision\":\"DECLINE\",\"reason\":\"No answer\"}"))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ACCEPTED"));
    }

    @Test
    void notAtWorkDefersCoverWhereTheSchemeSaysSoAndPartialCoverNeedsTheTerm() throws Exception {
        // AT-GL03-02, deferred-cover path; and no partial cover on this scheme (NS-GL03-002).
        String scheme = scheme("{\"aawRule\":\"DEFER\",\"partialCoverAllowed\":false}");
        commit(scheme, "AW-1", HEADER + ",activelyAtWork", "D1,Dee,Away,1985-01-01,STAFF,100000,JOIN,2026-01-01,N\n"
                + "D2,Dan,Big,1985-01-01,STAFF,200000,JOIN,2026-01-01,Y\n"
                + "D3,Dot,Small,1985-01-01,STAFF,100000,JOIN,2026-01-01,Y");
        String detail = detail(scheme);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'D1')].status")).containsExactly("COVER_DEFERRED");
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'D1')].acceptedCover")).containsExactly(0.0);
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'D1')].pendingCover")).containsExactly(0.0);
        // 800,000 asked for above the 500,000 free cover and no partial cover: none is in force until underwritten.
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'D2')].acceptedCover")).containsExactly(0.0);
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'D2')].pendingCover")).containsExactly(800000.0);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'D3')].status")).containsExactly("ACTIVE");
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[*].memberKey").value(org.hamcrest.Matchers.contains("D3")));
        Integer d1 = memberId(scheme, "D1");
        mockMvc.perform(json(post("/group/members/" + d1 + "/actively-at-work"), "admin",
                        "{\"effectiveDate\":\"2026-03-02\",\"note\":\"Employer confirmed return from leave\"}"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.acceptedCover").value(400000.0))
                .andExpect(jsonPath("$.activelyAtWork").value(true));
        mockMvc.perform(json(post("/group/members/" + d1 + "/actively-at-work"), "admin", "{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aCategoryTransferKeepsAcceptedCoverReusesValidEvidenceAndShowsExposure() throws Exception {
        String scheme = scheme("{}");
        category(scheme, "MGMT", "6");
        commit(scheme, "CT-1", "T1,Tim,Mover,1980-01-01,STAFF,200000,JOIN,2026-01-01");
        Integer t1 = memberId(scheme, "T1");
        GroupSchemeSetup.receiveEvidence(mockMvc, t1);
        mockMvc.perform(json(post("/group/members/" + t1 + "/underwriting"), "admin", "{\"decision\":\"ACCEPT\",\"reason\":\"Clear\"}"))
                .andExpect(jsonPath("$.acceptedCover").value(800000.0));
        // To MGMT (6 x salary = 1,200,000): the 800,000 already accepted continues; 400,000 is referred.
        String moved = mockMvc.perform(json(post("/group/members/" + t1 + "/category-transfer"), "admin",
                        "{\"categoryCode\":\"MGMT\",\"effectiveDate\":\"2026-06-01\",\"reason\":\"Promoted\"}"))
                .andExpect(jsonPath("$.category").value("MGMT"))
                .andExpect(jsonPath("$.acceptedCover").value(800000.0))
                .andExpect(jsonPath("$.pendingCover").value(400000.0))
                .andReturn().getResponse().getContentAsString();
        Integer next = JsonPath.read(moved, "$.id");
        mockMvc.perform(get("/group/members/" + next + "/underwriting-file").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.episodes.length()").value(2))
                .andExpect(jsonPath("$.episodes[0].category").value("STAFF"))
                .andExpect(jsonPath("$.episodes[0].to").value("2026-05-31"))
                .andExpect(jsonPath("$.episodes[1].category").value("MGMT"))
                // NS-GL03-006: the questionnaire received in the STAFF episode is still valid and reused.
                .andExpect(jsonPath("$.evidence[?(@.requirement == 'MEDICAL_QUESTIONNAIRE')].status").value(hasItem("RECEIVED")))
                .andExpect(jsonPath("$.evidence[?(@.requirement == 'MEDICAL_QUESTIONNAIRE')].note").value(hasItem(containsString("Reused"))))
                .andExpect(jsonPath("$.outstanding.length()").value(0));

        // Aggregate exposure: the same person on another scheme counts too.
        String other = scheme("{}");
        commit(other, "CT-2", "X9,Tim,Mover,1980-01-01,STAFF,100000,JOIN,2026-01-01");
        mockMvc.perform(get("/group/members/" + next + "/underwriting-file").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.otherGroupCover[0].schemeRef").value(other))
                .andExpect(jsonPath("$.aggregateExposure").value(1600000.0));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** An active scheme with a STAFF category (4 x salary, 500,000 free cover, rate 1) and the given set-up terms. */
    private String scheme(String terms) throws Exception {
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin", "{\"name\":\"UW Works "
                        + System.nanoTime() + "\",\"sector\":\"Retail\",\"inceptionDate\":\"2026-01-01\",\"renewalDate\":\"2027-01-01\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.scheme.ref");
        category(scheme, "STAFF", "4");
        String quote = GroupSchemeSetup.acceptedQuote(mockMvc, "UW Terms " + System.nanoTime());
        String body = terms.replaceFirst("\\{", "{\"quoteRef\":\"" + quote + "\",\"wordingRef\":\"WRD-G001\"," + CONDITIONS
                + (terms.equals("{}") ? "" : ","));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/activation"), "admin", body)).andExpect(status().isOk());
        return scheme;
    }

    private void category(String scheme, String code, String multiple) throws Exception {
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", "{\"code\":\"" + code
                        + "\",\"name\":\"" + code + "\",\"benefitBasis\":\"SALARY_MULTIPLE\",\"salaryMultiple\":" + multiple
                        + ",\"freeCoverLimit\":500000,\"ratePerMille\":1,\"minEntryAge\":18,\"maxEntryAge\":64}"))
                .andExpect(status().isOk());
    }

    private void commit(String scheme, String id, String rows) throws Exception {
        commit(scheme, id, HEADER, rows);
    }

    private void commit(String scheme, String id, String header, String rows) throws Exception {
        String batch = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        "{\"externalBatchId\":\"" + id + "\",\"mode\":\"PARTIAL\",\"csv\":\"" + header + "\\n"
                                + rows.replace("\n", "\\n") + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.rejectedRows").value(0));
    }

    private String detail(String scheme) throws Exception {
        return mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
    }

    private Integer memberId(String scheme, String key) throws Exception {
        return ((List<Integer>) JsonPath.read(detail(scheme), "$.members[?(@.memberKey == '" + key + "')].id")).get(0);
    }

    private List<OutboxEvent> events(String type, String ref) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :t and e.aggregateRef = :r", OutboxEvent.class)
                .setParameter("t", type).setParameter("r", ref).getResultList();
    }

    private List<OutboxEvent> eventsFor(String type, String scheme) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :t and e.payload like :s", OutboxEvent.class)
                .setParameter("t", type).setParameter("s", "%\"schemeRef\":\"" + scheme + "\"%").getResultList();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
