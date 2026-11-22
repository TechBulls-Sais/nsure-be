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
 * The sponsor account (GL05): lump-sum payments allocated oldest first or by member with unallocated money held
 * (NS-GL05-003), short-payment and unallocated-money exceptions (AT-GL05-02), and arrears through the grace period to
 * an escalated, then approved lapse that keeps cover to the paid-to date (NS-GL05-004).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-account;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupSponsorAccountApiTest {

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
    void lumpSumPaymentsArrearsEscalationAndLapse() throws Exception {
        String scheme = scheme("2026-01-01");
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("code", "STAFF");
        cat.put("name", "Staff");
        cat.put("benefitBasis", "FIXED");
        cat.put("fixedCover", "100000");
        cat.put("freeCoverLimit", "500000");
        cat.put("ratePerMille", "1");
        cat.put("minEntryAge", 18);
        cat.put("maxEntryAge", 64);
        ok(post("/group/schemes/" + scheme + "/categories"), "grpadmin", body(cat));
        commit(upload(scheme, "SA-1", HEADER + "\nM1,Mina,One,1985-01-01,STAFF,,JOIN,2026-08-01"
                + "\nM2,Max,Two,1986-01-01,STAFF,,JOIN,2026-08-01"));
        String aug = invoice(scheme, "2026-08");
        String sep = invoice(scheme, "2026-09");
        String oct = invoice(scheme, "2026-10");

        // 250 oldest first: August paid, September short paid (an exception), nothing held.
        ok(post("/group/schemes/" + scheme + "/sponsor-payments"), "grpadmin",
                "{\"reference\":\"EFT-LUMP-1\",\"amount\":250}")
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.allocations.length()").value(2))
                .andExpect(jsonPath("$.allocations[0].invoiceRef").value(aug))
                .andExpect(jsonPath("$.allocations[1].invoiceRef").value(sep))
                .andExpect(jsonPath("$.allocations[1].amount").value(50.0));
        // The same payer reference again is the same payment, not a second allocation.
        ok(post("/group/schemes/" + scheme + "/sponsor-payments"), "grpadmin",
                "{\"reference\":\"EFT-LUMP-1\",\"amount\":250}")
                .andExpect(jsonPath("$.allocations.length()").value(2));
        String exceptions = mockMvc.perform(get("/group/billing-exceptions").with(signedIn("grpadmin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(exceptions, "$[?(@.invoiceRef == '" + sep + "')].type"))
                .containsExactly("SHORT_PAYMENT");

        // Member rows: M1's 100 pays M1's September line; 30 for an unknown member is held unallocated.
        ok(post("/group/schemes/" + scheme + "/sponsor-payments"), "grpadmin",
                "{\"reference\":\"EFT-MEM-1\",\"amount\":130,\"rule\":\"MEMBERS\",\"members\":["
                        + "{\"memberKey\":\"M1\",\"amount\":100},{\"memberKey\":\"M9\",\"amount\":30}]}")
                .andExpect(jsonPath("$.status").value("PARTLY_ALLOCATED"))
                .andExpect(jsonPath("$.unallocated").value(30.0))
                .andExpect(jsonPath("$.unmatched").value(containsString("M9: 30")));
        assertThat(events(Events.UNMATCHED_RECEIPT, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"amount\":\"30.00\""));

        // Arrears: September is past the grace period, October within it; unallocated money blocks a lapse.
        String arrears = mockMvc.perform(get("/group/arrears").with(signedIn("grpadmin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(arrears, "$[?(@.invoiceRef == '" + sep + "')].stage"))
                .containsExactly("GRACE_EXPIRED");
        assertThat((List<String>) JsonPath.read(arrears, "$[?(@.invoiceRef == '" + oct + "')].stage"))
                .containsExactly(LocalDate.now().isAfter(LocalDate.parse("2026-11-30")) ? "GRACE_EXPIRED" : "IN_GRACE");
        ok(post("/group/schemes/" + scheme + "/lapse"), "grpadmin", "{\"note\":\"Two months unpaid\"}")
                .andExpect(jsonPath("$.outcome").value("ESCALATED"))
                .andExpect(jsonPath("$.blockers[0]").value(containsString("unallocated")));
        String payment = JsonPath.read(mockMvc.perform(get("/group/schemes/" + scheme + "/sponsor-account")
                        .with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.exceptions[?(@.type == 'LAPSE_ESCALATED')].status").value("OPEN"))
                .andReturn().getResponse().getContentAsString(), "$.payments[0].ref");

        // The held 30 goes to October: suspense is cleared, the unallocated exception resolves.
        ok(post("/group/sponsor-payments/" + payment + "/allocations"), "grpadmin",
                "{\"invoiceRef\":\"" + oct + "\",\"amount\":30}")
                .andExpect(jsonPath("$.status").value("ALLOCATED"));
        assertThat(events(Events.SUSPENSE_CLEARED, scheme)).hasSize(1);

        // Now the lapse is proposed: paid to 31 August, so cover would end then.
        String lapse = JsonPath.read(ok(post("/group/schemes/" + scheme + "/lapse"), "grpadmin",
                        "{\"note\":\"Sponsor has not paid September\"}")
                .andExpect(jsonPath("$.outcome").value("PROPOSED"))
                .andExpect(jsonPath("$.lapse.paidToDate").value("2026-08-31"))
                .andExpect(jsonPath("$.lapse.effectiveDate").value("2026-09-01"))
                .andExpect(jsonPath("$.lapse.membersAffected").value(2))
                .andReturn().getResponse().getContentAsString(), "$.lapse.ref");
        mockMvc.perform(json(post("/group/scheme-lapses/" + lapse + "/decision"), "grpadmin",
                        "{\"approve\":true,\"note\":\"mine\"}"))
                .andExpect(status().isForbidden());
        ok(post("/group/scheme-lapses/" + lapse + "/decision"), "admin",
                "{\"approve\":true,\"note\":\"Final notice expired\"}")
                .andExpect(jsonPath("$.status").value("APPROVED"));
        String detail = mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.scheme.status").value("CLOSED"))
                .andExpect(jsonPath("$.scheme.outstandingBalance").value(0.0))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(detail, "$.members[*].exitDate")).containsOnly("2026-08-31");
        // September and October were credited in full; what had been paid towards them is held for the sponsor.
        assertThat(events(Events.SCHEME_CLOSED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("Lapsed for non-payment"));
        Double held = jdbc.queryForObject("select sum(p.unallocated) from grp_sponsor_payment p join ins_scheme s"
                + " on s.id = p.scheme_id where s.record_ref = ? and p.payment_kind = 'CREDIT_NOTE'", Double.class, scheme);
        assertThat(held).isEqualTo(180.0);
    }

    @Test
    void aShortDirectReceiptRaisesAnExceptionThatClosesWhenPaid() throws Exception {
        String scheme = scheme("2026-01-01");
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("code", "STAFF");
        cat.put("name", "Staff");
        cat.put("benefitBasis", "FIXED");
        cat.put("fixedCover", "100000");
        cat.put("freeCoverLimit", "500000");
        cat.put("ratePerMille", "1");
        cat.put("minEntryAge", 18);
        cat.put("maxEntryAge", 64);
        ok(post("/group/schemes/" + scheme + "/categories"), "grpadmin", body(cat));
        commit(upload(scheme, "SR-1", HEADER + "\nR1,Ria,One,1985-01-01,STAFF,,JOIN,2026-10-01"));
        String inv = invoice(scheme, "2026-10");
        ok(post("/group/invoices/" + inv + "/receipts"), "grpadmin", "{\"amount\":60,\"reference\":\"D-1\"}");
        mockMvc.perform(get("/group/schemes/" + scheme + "/sponsor-account").with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.exceptions[0].type").value("SHORT_PAYMENT"))
                .andExpect(jsonPath("$.exceptions[0].amount").value(40.0))
                .andExpect(jsonPath("$.exceptions[0].status").value("OPEN"));
        ok(post("/group/invoices/" + inv + "/receipts"), "grpadmin", "{\"amount\":40,\"reference\":\"D-2\"}");
        mockMvc.perform(get("/group/schemes/" + scheme + "/sponsor-account").with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.exceptions[0].status").value("RESOLVED"));
    }

    private String invoice(String scheme, String period) throws Exception {
        return JsonPath.read(ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin",
                "{\"period\":\"" + period + "\"}").andReturn().getResponse().getContentAsString(), "$.ref");
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
        com.nsure.testsupport.GroupSchemeSetup.activate(mockMvc, scheme); // schemes start in SETUP (NS-GL01-004)
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
