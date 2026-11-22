package com.nsure.group;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxEvent;
import com.nsure.support.ModuleTestUsers;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * GL04 member movements as effective-dated cover (NS-GL04-004): category transfers, cover periods, held rows and their
 * authorised review (AT-GL04-03), and backdated changes billed as supplementary adjustments on invoiced months
 * (NS-GL05-002).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-movements;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupMovementsApiTest {

    static final String HEADER = "memberKey,firstName,lastName,dateOfBirth,category,salary,action,effectiveDate";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aCategoryTransferOpensANewCoverPeriodAndKeepsTheEarlierOne() throws Exception {
        String scheme = scheme("2026-01-01");
        category(scheme, "STAFF", "2", "500000", "0.50");
        category(scheme, "MGMT", "4", "500000", "0.60");
        // NS-GL04-003: a national id that one customer holds resolves the member to that party (CUS-1003 is seeded).
        commit(upload(scheme, "MV-1", HEADER + ",nationalId\nT1,Tumi,Mover,1985-01-01,STAFF,100000,JOIN,"
                + LocalDate.now().minusDays(40) + ",770305202"));
        String transfer = upload(scheme, "MV-2", HEADER + "\nT1,,,,MGMT,,CATEGORY," + LocalDate.now().minusDays(10));
        mockMvc.perform(get("/group/census-batches/" + transfer).with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.rows[0].status").value("VALID"))
                .andExpect(jsonPath("$.rows[0].premiumBefore").value(100.0))
                .andExpect(jsonPath("$.rows[0].premiumAfter").value(240.0));
        commit(transfer);
        String detail = detail(scheme);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'T1')].category")).containsExactly("MGMT");
        assertThat((List<Double>) JsonPath.read(detail, "$.members[?(@.memberKey == 'T1')].acceptedCover"))
                .containsExactly(400000.0);
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'T1')].membershipStatus"))
                .containsExactly("ON_SCHEME");
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'T1')].coverStatus"))
                .containsExactly("IN_FORCE");
        assertThat((List<String>) JsonPath.read(detail, "$.members[?(@.memberKey == 'T1')].partyRef"))
                .containsExactly("CUS-1003");
        Integer member = ((List<Integer>) JsonPath.read(detail, "$.members[?(@.memberKey == 'T1')].id")).get(0);
        mockMvc.perform(get("/group/members/" + member + "/cover-history").with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].category").value("STAFF"))
                .andExpect(jsonPath("$[0].acceptedCover").value(200000.0))
                .andExpect(jsonPath("$[0].validTo").value(LocalDate.now().minusDays(11).toString()))
                .andExpect(jsonPath("$[1].category").value("MGMT"))
                .andExpect(jsonPath("$[1].changeType").value("CATEGORY"));
        // Moving to the category the member is already in is refused.
        String again = upload(scheme, "MV-3", HEADER + "\nT1,,,,MGMT,,CATEGORY," + LocalDate.now());
        mockMvc.perform(get("/group/census-batches/" + again).with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.rows[0].status").value("INVALID"))
                .andExpect(jsonPath("$.rows[0].error").value(containsString("already in category MGMT")));
    }

    @Test
    void aBackdatedSalaryChangeIntoAnInvoicedMonthIsBilledAsASupplementaryAdjustment() throws Exception {
        String scheme = scheme("2026-01-01");
        category(scheme, "STAFF", "2", "1000000", "0.60");
        // Two members on cover from 1 September; September is invoiced on the original salaries.
        commit(upload(scheme, "SB-1", HEADER + "\nS1,Sara,One,1985-01-01,STAFF,120000,JOIN,2026-09-01"
                + "\nS2,Sipho,Two,1986-01-01,STAFF,60000,JOIN,2026-09-01"));
        String invoice = JsonPath.read(ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin",
                "{\"period\":\"2026-09\"}").andExpect(jsonPath("$.amount").value(216.0))
                .andExpect(jsonPath("$.prorationBasis").value("DAILY"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        // S1's salary rises from 16 September and S2 leaves on 20 September, both after the invoice was issued.
        String batch = upload(scheme, "SB-2", HEADER + "\nS1,,,,,180000,SALARY,2026-09-16\nS2,,,,,,LEAVE,2026-09-20");
        String committed = commit(batch);
        String adjustmentRef = JsonPath.read(committed, "$.rows[0].adjustmentRefs");
        assertThat(adjustmentRef).startsWith("GAD-");
        assertThat((String) JsonPath.read(committed, "$.rows[1].adjustmentRefs")).isEqualTo(adjustmentRef);
        String detail = detail(scheme);
        // The issued invoice keeps its amount and lines; the adjustment references the original lines.
        assertThat((List<Double>) JsonPath.read(detail, "$.invoices[?(@.ref == '" + invoice + "')].amount"))
                .containsExactly(216.0);
        // S1: 15 days at 240 000 and 15 days at 360 000 of cover at 0.60 = 72 + 108 = 180 (was 144): +36.
        // S2: 20 of 30 days of 120 000 at 0.60 = 48 (was 72): -24. Net debit 12.
        assertThat((List<Double>) JsonPath.read(detail, "$.invoices[?(@.ref == '" + invoice + "')].adjustmentAmount"))
                .containsExactly(12.0);
        assertThat((List<Double>) JsonPath.read(detail, "$.invoices[?(@.ref == '" + invoice + "')].outstanding"))
                .containsExactly(228.0);
        assertThat((String) JsonPath.read(detail, "$.invoices[0].adjustments[0].type")).isEqualTo("DEBIT");
        assertThat((List<Double>) JsonPath.read(detail,
                "$.invoices[0].adjustments[0].lines[?(@.memberKey == 'S1')].amount")).containsExactly(36.0);
        assertThat((List<Double>) JsonPath.read(detail,
                "$.invoices[0].adjustments[0].lines[?(@.memberKey == 'S2')].amount")).containsExactly(-24.0);
        List<Integer> originalLines = JsonPath.read(detail, "$.invoices[0].adjustments[0].lines[*].originalLineId");
        assertThat(originalLines).doesNotContainNull().hasSize(2);
        List<OutboxEvent> issued = events(Events.SCHEME_INVOICE_ISSUED, scheme);
        assertThat(issued).hasSize(2);
        assertThat(issued.get(1).getPayload()).contains("\"invoiceType\":\"SUPPLEMENTARY\"")
                .contains("\"originalInvoiceRef\":\"" + invoice + "\"").contains("\"amount\":\"12.00\"");

        // October is billed on the new terms; re-pricing September again changes nothing more.
        ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2026-10\"}")
                .andExpect(jsonPath("$.amount").value(216.0))
                .andExpect(jsonPath("$.memberCount").value(1));
    }

    @Test
    void aBackdatedCreditBeyondTheBalanceIsHeldForTheSponsor() throws Exception {
        String scheme = scheme("2026-01-01");
        category(scheme, "STAFF", "2", "1000000", "0.60");
        commit(upload(scheme, "CR-1", HEADER + "\nC1,Cara,One,1985-01-01,STAFF,120000,JOIN,2026-10-01"));
        String invoice = JsonPath.read(ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin",
                "{\"period\":\"2026-10\"}").andReturn().getResponse().getContentAsString(), "$.ref");
        ok(post("/group/invoices/" + invoice + "/receipts"), "grpadmin", "{\"amount\":144,\"reference\":\"EFT-C1\"}")
                .andExpect(jsonPath("$.invoice.status").value("PAID"));
        // C1 left on 15 October: the paid invoice is credited 72.00 (16 of 31 days), held for the sponsor.
        String committed = commit(upload(scheme, "CR-2", HEADER + "\nC1,,,,,,LEAVE,2026-10-15"));
        assertThat((String) JsonPath.read(committed, "$.rows[0].adjustmentRefs")).startsWith("GAD-");
        String detail = detail(scheme);
        assertThat((Double) JsonPath.read(detail, "$.invoices[0].outstanding")).isEqualTo(0.0);
        assertThat((Double) JsonPath.read(detail, "$.invoices[0].adjustments[0].heldAsCredit")).isEqualTo(74.32);
        Long credits = jdbc.queryForObject("select count(*) from grp_sponsor_payment p join ins_scheme s on s.id = p.scheme_id"
                + " where s.record_ref = ? and p.payment_kind = 'CREDIT_NOTE' and p.unallocated = 74.32", Long.class, scheme);
        assertThat(credits).isEqualTo(1L);
    }

    @Test
    void aRetrospectiveLeaverBehindAPaidClaimIsHeldAndReleasedOnlyByAnotherPerson() throws Exception {
        String scheme = scheme("2026-01-01");
        category(scheme, "STAFF", "2", "1000000", "0.60");
        commit(upload(scheme, "HR-1", HEADER + "\nH1,Hope,Held,1985-01-01,STAFF,100000,JOIN,2026-01-01"
                + "\nH2,Hugo,Free,1985-01-01,STAFF,100000,JOIN,2026-01-01"));
        String claim = JsonPath.read(ok(post("/group/schemes/" + scheme + "/claims"), "admin",
                "{\"memberKey\":\"H1\",\"benefitType\":\"GLA\",\"eventDate\":\"2026-05-10\",\"amount\":200000}")
                .andReturn().getResponse().getContentAsString(), "$.ref");
        ok(post("/group/claims/" + claim + "/decision"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Verified\"}");
        ok(post("/group/claims/" + claim + "/decision"), "admin", "{\"action\":\"PAY\",\"paymentReference\":\"EFT-9\"}");

        String batch = upload(scheme, "HR-2", HEADER + "\nH1,,,,,,LEAVE,2026-04-30\nH2,,,,,,LEAVE,2026-04-30");
        String staged = mockMvc.perform(get("/group/census-batches/" + batch).with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.heldRows").value(1))
                .andExpect(jsonPath("$.rows[0].status").value("HELD"))
                .andExpect(jsonPath("$.rows[0].holdReason").value(containsString(claim)))
                .andExpect(jsonPath("$.rows[1].status").value("VALID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(staged).isNotEmpty();
        String committed = commit(batch);
        assertThat((Integer) JsonPath.read(committed, "$.acceptedRows")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(committed, "$.heldRows")).isEqualTo(1);
        assertThat((String) JsonPath.read(committed, "$.rows[0].status")).isEqualTo("HELD");
        // H1 is still on cover until someone other than the submitter decides.
        assertThat((List<String>) JsonPath.read(detail(scheme), "$.members[?(@.memberKey == 'H1')].status"))
                .containsExactly("ACTIVE");
        mockMvc.perform(json(post("/group/census-batches/" + batch + "/rows/2/review"), "grpadmin",
                        "{\"decision\":\"APPLY\",\"note\":\"mine\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group/census-batches/" + batch + "/rows/3/review"), "admin",
                        "{\"decision\":\"APPLY\",\"note\":\"not held\"}"))
                .andExpect(status().isConflict());
        ok(post("/group/census-batches/" + batch + "/rows/2/review"), "admin",
                "{\"decision\":\"APPLY\",\"note\":\"Employer confirmed the leave date; claim stands on the earlier cover\"}")
                .andExpect(jsonPath("$.heldRows").value(0))
                .andExpect(jsonPath("$.acceptedRows").value(2))
                .andExpect(jsonPath("$.rows[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.rows[0].reviewedBy").value("admin"));
        assertThat((List<String>) JsonPath.read(detail(scheme), "$.members[?(@.memberKey == 'H1')].status"))
                .containsExactly("CEASED");
    }

    @Test
    void aChangeReachingFarBackIntoInvoicedMonthsIsHeldAndCanBeRejectedAtReview() throws Exception {
        String scheme = scheme("2026-01-01");
        category(scheme, "STAFF", "2", "1000000", "0.60");
        commit(upload(scheme, "OLD-1", HEADER + "\nO1,Olu,Old,1985-01-01,STAFF,100000,JOIN,2026-01-01"));
        ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2026-02\"}");
        String batch = upload(scheme, "OLD-2", HEADER + "\nO1,,,,,150000,SALARY,2026-02-01");
        mockMvc.perform(get("/group/census-batches/" + batch).with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.rows[0].status").value("HELD"))
                .andExpect(jsonPath("$.rows[0].holdReason").value(containsString("backdated more than 90 days")));
        ok(post("/group/census-batches/" + batch + "/rows/2/review"), "admin",
                "{\"decision\":\"REJECT\",\"note\":\"Sponsor to confirm the increase date\"}")
                .andExpect(jsonPath("$.rows[0].status").value("INVALID"))
                .andExpect(jsonPath("$.heldRows").value(0));
        // A correction batch points at the rejected line and replaces it.
        String correction = JsonPath.read(ok(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin",
                body(Map.of("externalBatchId", "OLD-3", "correctsBatchRef", batch, "csv",
                        HEADER + ",correctsLine\nO1,,,,,150000,SALARY," + LocalDate.now().withDayOfMonth(1) + ",2")))
                .andExpect(jsonPath("$.schemaVersion").value("2"))
                .andExpect(jsonPath("$.correctsBatchRef").value(batch))
                .andExpect(jsonPath("$.rows[0].status").value("VALID"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        commit(correction);
        mockMvc.perform(get("/group/census-batches/" + batch).with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.rows[0].correctedBy").value(correction + " line 2"));
        mockMvc.perform(get("/group/census-template").with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.version").value("2"))
                .andExpect(jsonPath("$.columns[?(@.name == 'correctsLine')].sinceVersion").value("2"));
    }

    @Test
    void aSchemeCanRequireAtomicBatchesAndASecondPersonToCommit() throws Exception {
        String scheme = scheme("2026-01-01");
        category(scheme, "STAFF", "2", "1000000", "0.60");
        mockMvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/group/schemes/" + scheme + "/census-terms"), "grpadmin", "{\"atomicRequired\":true,\"fourEyes\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atomicRequired").value(true));
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin",
                        body(Map.of("externalBatchId", "T-1", "mode", "PARTIAL", "csv", HEADER + "\nQ1,Q,One,1985-01-01,STAFF,100000,JOIN,"
                                + LocalDate.now()))))
                .andExpect(status().isUnprocessableEntity());
        String batch = JsonPath.read(ok(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin",
                body(Map.of("externalBatchId", "T-2", "csv", HEADER + "\nQ1,Q,One,1985-01-01,STAFF,100000,JOIN," + LocalDate.now())))
                .andExpect(jsonPath("$.mode").value("ATOMIC")).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("grpadmin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMMITTED"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String scheme(String inception) throws Exception {
        String name = "Movements " + System.nanoTime();
        return JsonPath.read(ok(post("/group/schemes"), "grpadmin", body(Map.of("name", name, "sector", "Retail",
                "inceptionDate", inception, "renewalDate", LocalDate.parse(inception).plusYears(1).toString())))
                .andReturn().getResponse().getContentAsString(), "$.scheme.ref");
    }

    private void category(String scheme, String code, String multiple, String freeCover, String rate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", code);
        body.put("benefitBasis", "SALARY_MULTIPLE");
        body.put("salaryMultiple", multiple);
        body.put("freeCoverLimit", freeCover);
        body.put("ratePerMille", rate);
        body.put("minEntryAge", 18);
        body.put("maxEntryAge", 64);
        ok(post("/group/schemes/" + scheme + "/categories"), "grpadmin", body(body));
    }

    private String upload(String scheme, String id, String csv) throws Exception {
        return JsonPath.read(ok(post("/group/schemes/" + scheme + "/census-batches"), "grpadmin",
                body(Map.of("externalBatchId", id, "csv", csv))).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private String commit(String batch) throws Exception {
        return mockMvc.perform(post("/group/census-batches/" + batch + "/commit").with(signedIn("grpadmin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String detail(String scheme) throws Exception {
        return mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("grpadmin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<OutboxEvent> events(String type, String scheme) {
        return em.createQuery("select e from OutboxEvent e where e.eventType = :t and e.payload like :s order by e.id",
                        OutboxEvent.class)
                .setParameter("t", type).setParameter("s", "%\"schemeRef\":\"" + scheme + "\"%").getResultList();
    }

    private ResultActions ok(MockHttpServletRequestBuilder request, String user, String body) throws Exception {
        return mockMvc.perform(json(request, user, body)).andExpect(status().isOk());
    }

    private String body(Map<String, ?> body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
