package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The group acceptance pack (specification section 08) for the scenarios the system supports. Scenarios covered
 * elsewhere: excess over free cover, partial accepted cover, duplicate census and partial-batch failure
 * ({@code GroupAdministrationApiTest#partialCensusAppliesValidRowsOnceAndAboveLimitCoverWaitsForUnderwriting},
 * {@code #atomicBatchIsRefusedWhileAnyRowIsInvalidAndLeaveNeedsAnActiveMember}), salary increase ({@code
 * #categoryChangesAreProspective}), mid-period proration and sponsor short payment ({@code
 * #invoicesProRateMidMonthJoinersAndLeaversAndShortPaymentsLeaveABalance}), scheme override above the product maximum
 * and loan early-settlement refund ({@code GroupLifeApiTest}). Rules the insurer has not supplied (late-entry window,
 * income-benefit review interval) are tested as mechanisms with the values set in the test. CI survival boundary and
 * group treaty change have no mechanism yet.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupAcceptancePackTest {

    private static final String HEADER = SchemeLifecycleApiTest.HEADER;
    private static final AtomicInteger SCHEMES = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void categoryOverridesStayWithinBoundsAndProduceDistinctCertificates() throws Exception {
        // Scheme/category overrides (AT-GL01-02): each category's own terms decide its members' cover.
        String scheme = scheme();
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin",
                        category("STAFF", "SALARY_MULTIPLE", "60", null, "500000", null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("at most 50")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin",
                        category("STAFF", "SALARY_MULTIPLE", "3", null, "500000", "-1")))
                .andExpect(status().isUnprocessableEntity());
        ok(post("/group/schemes/" + scheme + "/categories"), category("STAFF", "SALARY_MULTIPLE", "3", null, "500000", null));
        ok(post("/group/schemes/" + scheme + "/categories"), category("MGMT", "FIXED", null, "1000000", "750000", null));
        commit(upload(scheme, "OV-1", HEADER, "C1,Cat,Staff,1985-01-01,STAFF,100000,JOIN,2026-01-01\n"
                + "C2,Cat,Manager,1980-01-01,MGMT,,JOIN,2026-01-01"));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.memberKey == 'C1')].category").value("STAFF category"))
                .andExpect(jsonPath("$[?(@.memberKey == 'C1')].acceptedCover").value(300000.0))
                .andExpect(jsonPath("$[?(@.memberKey == 'C2')].category").value("MGMT category"))
                .andExpect(jsonPath("$[?(@.memberKey == 'C2')].acceptedCover").value(750000.0))
                .andExpect(jsonPath("$[?(@.memberKey == 'C2')].pendingCover").value(250000.0));
    }

    @Test
    void notActivelyAtWorkReferAllCoverAndAnyIncreaseToUnderwriting() throws Exception {
        // Actively-at-work failure (AT-GL03-02, NS-GL03-001): the declaration is kept; no free cover is presumed.
        String scheme = scheme();
        ok(post("/group/schemes/" + scheme + "/categories"), category("STAFF", "SALARY_MULTIPLE", "2", null, "500000", null));
        String batch = upload(scheme, "AW-1", HEADER + ",activelyAtWork",
                "W1,Will,Working,1985-01-01,STAFF,100000,JOIN,2026-01-01,Y\n"
                        + "W2,Wes,Absent,1985-01-01,STAFF,100000,JOIN,2026-01-01,N\n"
                        + "W3,Wim,Unsure,1985-01-01,STAFF,100000,JOIN,2026-01-01,maybe");
        mockMvc.perform(get("/group/census-batches/" + batch).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.validRows").value(2))
                .andExpect(jsonPath("$.rows[2].error").value(containsString("activelyAtWork must be Y or N")));
        commit(batch);
        String detail = schemeDetail(scheme);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'W1')].status")).containsExactly("ACTIVE");
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'W2')].status"))
                .containsExactly("UNDERWRITING_PENDING");
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'W2')].acceptedCover")).containsExactly(0.0);
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'W2')].pendingCover")).containsExactly(200000.0);
        assertThat((List<Boolean>) JsonPath.read(detail, "$.members[?(@.memberKey == 'W2')].activelyAtWork")).containsExactly(false);
        // Nothing is certified or billed for W2 until an underwriter accepts it.
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/certificates").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[*].memberKey").value(org.hamcrest.Matchers.contains("W1")));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin", "{\"period\":\"2026-02\"}"))
                .andExpect(jsonPath("$.memberCount").value(1));
        // A salary increase declared while not at work keeps the cover in force and refers the increase.
        commit(upload(scheme, "AW-2", HEADER + ",activelyAtWork", "W1,,,,,150000,SALARY,2026-03-01,N"));
        detail = schemeDetail(scheme);
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'W1')].acceptedCover")).containsExactly(200000.0);
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'W1')].pendingCover")).containsExactly(100000.0);
    }

    @Test
    void aLateEntrantIsReferredOnlyWhereTheCategorySetsALateEntryWindow() throws Exception {
        // Late entrant (NS-GL03-003): the window is the insurer's rule; with none configured nothing changes.
        String scheme = scheme();
        ok(post("/group/schemes/" + scheme + "/categories"), category("STAFF", "FIXED", null, "100000", "500000", null));
        Map<String, Object> windowed = categoryMap("LATE", "FIXED", null, "100000", "500000", null);
        windowed.put("lateEntryDays", 31);
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/categories"), "admin", body(windowed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lateEntryDays").value(31));
        commit(upload(scheme, "LE-1", HEADER + ",eligibleDate",
                "L1,Lee,Nowindow,1985-01-01,STAFF,,JOIN,2026-03-01,2026-01-01\n"
                        + "L2,Lea,Late,1985-01-01,LATE,,JOIN,2026-03-01,2026-01-01\n"
                        + "L3,Leo,Intime,1985-01-01,LATE,,JOIN,2026-03-01,2026-02-15"));
        String detail = schemeDetail(scheme);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'L1')].status")).containsExactly("ACTIVE");
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'L2')].status"))
                .containsExactly("UNDERWRITING_PENDING");
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'L2')].pendingCover")).containsExactly(100000.0);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'L3')].status")).containsExactly("ACTIVE");
    }

    @Test
    void aRetrospectiveLeaverBehindAnAdmittedClaimIsHeldForAuthorisedReview() throws Exception {
        // Retrospective leaver (AT-GL04-03).
        String scheme = scheme();
        ok(post("/group/schemes/" + scheme + "/categories"), category("STAFF", "FIXED", null, "100000", "500000", null));
        commit(upload(scheme, "RL-1", HEADER, "R1,Rex,Leaver,1985-01-01,STAFF,,JOIN,2026-01-01"));
        String claim = notifyClaim(scheme, "R1", "GLA", "2026-03-10", "100000");
        decide(claim, "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Death certificate verified\"}").andExpect(status().isOk());
        decide(claim, "admin", "{\"action\":\"PAY\",\"paymentReference\":\"EFT-CLM-1\"}")
                .andExpect(jsonPath("$.status").value("PAID"));
        String held = upload(scheme, "RL-2", HEADER, "R1,,,,,,LEAVE,2026-03-01");
        mockMvc.perform(get("/group/census-batches/" + held).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.rows[0].status").value("INVALID"))
                .andExpect(jsonPath("$.rows[0].error").value(containsString("held for authorised review: claim " + claim)));
        String later = upload(scheme, "RL-3", HEADER, "R1,,,,,,LEAVE,2026-03-31");
        mockMvc.perform(get("/group/census-batches/" + later).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.rows[0].status").value("VALID"));
    }

    @Test
    void aDisputedMemberContributionIsTrackedWithoutChangingCover() throws Exception {
        // Disputed member contribution (NS-GL05-003).
        String scheme = scheme();
        ok(post("/group/schemes/" + scheme + "/categories"), category("STAFF", "FIXED", null, "100000", "500000", null));
        commit(upload(scheme, "DS-1", HEADER, "D1,Dan,One,1985-01-01,STAFF,,JOIN,2026-01-01\n"
                + "D2,Dee,Two,1985-01-01,STAFF,,JOIN,2026-01-01"));
        String invoice = JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/invoices"), "admin",
                        "{\"period\":\"2026-02\"}")).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/disputes"), "admin",
                        "{\"memberKey\":\"D2\",\"action\":\"RAISE\",\"note\":\"Sponsor says D2 left in January\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disputedAmount").value(100.0))
                .andExpect(jsonPath("$.outstanding").value(200.0))
                .andExpect(jsonPath("$.lines[1].disputeStatus").value("OPEN"));
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/disputes"), "admin",
                        "{\"memberKey\":\"D2\",\"action\":\"RAISE\",\"note\":\"again\"}"))
                .andExpect(status().isConflict());
        // The undisputed part can be paid; the disputed member stays on cover until a census says otherwise.
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/receipts"), "admin", "{\"amount\":100,\"reference\":\"EFT-D\"}"))
                .andExpect(jsonPath("$.invoice.status").value("PARTIALLY_PAID"))
                .andExpect(jsonPath("$.invoice.disputedAmount").value(100.0));
        assertThat((List<String>) JsonPath.read(schemeDetail(scheme), "$.members[?(@.memberKey == 'D2')].status"))
                .containsExactly("ACTIVE");
        mockMvc.perform(json(post("/group/invoices/" + invoice + "/disputes"), "admin",
                        "{\"memberKey\":\"D2\",\"action\":\"RESOLVE\",\"note\":\"Payroll confirms D2 was employed\"}"))
                .andExpect(jsonPath("$.disputedAmount").value(0.0))
                .andExpect(jsonPath("$.lines[1].disputeStatus").value("RESOLVED"));
    }

    @Test
    void renewalWithOpenClaimsKeepsThemAndIncomeBenefitsInPaymentAreReviewedOrCeased() throws Exception {
        // Renewal with open claims (NS-GL05-006, AT-GL05-03) and income benefit review/cessation (NS-GL02-004).
        String scheme = scheme();
        ok(post("/group/schemes/" + scheme + "/categories"), category("STAFF", "FIXED", null, "100000", "500000", null));
        ok(put("/group-life/schemes/" + scheme + "/benefits"), "{\"benefitType\":\"GLA\",\"basis\":\"FIXED\",\"fixedAmount\":100000}");
        ok(put("/group-life/schemes/" + scheme + "/benefits"),
                "{\"benefitType\":\"INCOME_PROTECTION\",\"basis\":\"MONTHLY_INCOME_PERCENT\",\"multiple\":75,\"waitingMonths\":3}");
        commit(upload(scheme, "RN-1", HEADER, "I1,Ina,Income,1985-01-01,STAFF,,JOIN,2026-01-01\n"
                + "I2,Ian,Open,1985-01-01,STAFF,,JOIN,2026-01-01"));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        "{\"memberKey\":\"I1\",\"benefitType\":\"CRITICAL_ILLNESS\",\"eventDate\":\"2026-02-01\",\"amount\":1000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("does not provide")));
        String income = notifyClaim(scheme, "I1", "INCOME_PROTECTION", "2026-02-01", "6000");
        String open = notifyClaim(scheme, "I2", "GLA", "2026-03-01", "100000");
        // Four eyes: the person who notified the claim does not approve it.
        decide(income, "admin", "{\"action\":\"APPROVE\",\"note\":\"self\"}").andExpect(status().isForbidden());
        decide(income, "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Disability confirmed\"}")
                .andExpect(jsonPath("$.status").value("IN_PAYMENT"))
                .andExpect(jsonPath("$.nextReviewDate").value(LocalDate.now().plusMonths(12).toString()));

        mockMvc.perform(get("/group-life/schemes/" + scheme + "/impact").param("eventType", "RENEWAL")
                        .with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.pendingClaims").value(1))
                .andExpect(jsonPath("$.claims[0].ref").value(open))
                .andExpect(jsonPath("$.claimsInPayment").value(1))
                .andExpect(jsonPath("$.notes").value(hasItem(containsString("income-protection claims in payment continue"))));
        String renewal = JsonPath.read(mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"RENEWAL\",\"reason\":\"Annual renewal\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/events/" + renewal + "/decision"), "secadmin", "{\"approve\":true,\"note\":\"Renewed\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/group/schemes/" + scheme + "/claims").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + income + "')].status").value("IN_PAYMENT"))
                .andExpect(jsonPath("$[?(@.ref == '" + open + "')].status").value("NOTIFIED"));

        // The review keeps the benefit in payment to the next review; cessation ends it and nothing more is done.
        String next = LocalDate.now().plusMonths(6).toString();
        decide(income, "admin", "{\"action\":\"REVIEW\"}").andExpect(status().isUnprocessableEntity());
        decide(income, "admin", "{\"action\":\"REVIEW\",\"nextReviewDate\":\"" + next + "\",\"note\":\"Still unable to work\"}")
                .andExpect(jsonPath("$.status").value("IN_PAYMENT"))
                .andExpect(jsonPath("$.nextReviewDate").value(next));
        decide(income, "admin", "{\"action\":\"CEASE\",\"note\":\"Returned to work\"}")
                .andExpect(jsonPath("$.status").value("CEASED"))
                .andExpect(jsonPath("$.nextReviewDate").value(org.hamcrest.Matchers.nullValue()));
        decide(income, "admin", "{\"action\":\"REVIEW\",\"note\":\"late\"}").andExpect(status().isConflict());
        decide(open, "admin", "{\"action\":\"DECLINE\",\"note\":\"Not a covered event\"}")
                .andExpect(jsonPath("$.status").value("DECLINED"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String scheme() throws Exception {
        String name = "Acceptance Scheme " + SCHEMES.incrementAndGet() + " " + System.nanoTime();
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes"), "admin", body(Map.of("name", name, "sector",
                        "Retail", "inceptionDate", "2026-01-01", "renewalDate", "2027-01-01"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.scheme.ref");
    }

    private String schemeDetail(String scheme) throws Exception {
        return mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void ok(MockHttpServletRequestBuilder request, String body) throws Exception {
        mockMvc.perform(json(request, "admin", body)).andExpect(status().isOk());
    }

    private String category(String code, String basis, String multiple, String fixed, String freeCover, String minAge)
            throws Exception {
        return body(categoryMap(code, basis, multiple, fixed, freeCover, minAge));
    }

    private Map<String, Object> categoryMap(String code, String basis, String multiple, String fixed, String freeCover,
            String minAge) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", code + " category");
        body.put("benefitBasis", basis);
        body.put("salaryMultiple", multiple);
        body.put("fixedCover", fixed);
        body.put("freeCoverLimit", freeCover);
        body.put("ratePerMille", "1");
        body.put("minEntryAge", minAge == null ? "18" : minAge);
        body.put("maxEntryAge", 64);
        return body;
    }

    private String upload(String scheme, String externalId, String header, String rows) throws Exception {
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme);
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "admin",
                        body(Map.of("externalBatchId", externalId, "mode", "PARTIAL", "csv", header + "\n" + rows))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private void commit(String batch) throws Exception {
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    private String notifyClaim(String scheme, String member, String benefit, String eventDate, String amount)
            throws Exception {
        return JsonPath.read(mockMvc.perform(json(post("/group/schemes/" + scheme + "/claims"), "admin",
                        body(Map.of("memberKey", member, "benefitType", benefit, "eventDate", eventDate, "amount", amount))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private org.springframework.test.web.servlet.ResultActions decide(String claim, String user, String body)
            throws Exception {
        return mockMvc.perform(json(post("/group/claims/" + claim + "/decision"), user, body));
    }

    private String body(Map<String, ?> value) throws Exception {
        return mapper.writeValueAsString(value);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
