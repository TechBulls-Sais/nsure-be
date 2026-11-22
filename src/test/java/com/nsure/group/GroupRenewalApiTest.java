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
 * GL05 renewal (NS-GL05-005/006, AT-GL05-03): prepared from census and claims experience, negotiated in versions,
 * accepted by the sponsor with year continuity enforced, approved by a second person into next-year category
 * versions; income benefits in payment continue across it.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-group-renewal;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupRenewalApiTest {

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
    void aRenewalIsReviewedNegotiatedAcceptedAndApprovedIntoNextYearRates() throws Exception {
        String scheme = scheme("2026-01-01");
        category(scheme, "STAFF", "2", "1000000", "1.00");
        commit(upload(scheme, "RN-1", HEADER + "\nM1,Mosa,Older,1962-03-01,STAFF,100000,JOIN,2026-01-01"
                + "\nM2,Mpho,Younger,1980-01-01,STAFF,150000,JOIN,2026-01-01"));
        ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2026-02\"}")
                .andExpect(jsonPath("$.amount").value(500.0));
        mockMvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                        "/group-life/schemes/" + scheme + "/benefits"), "admin",
                        "{\"benefitType\":\"INCOME_PROTECTION\",\"basis\":\"MONTHLY_INCOME_PERCENT\",\"multiple\":75,\"waitingMonths\":3}"))
                .andExpect(status().isOk());
        String claim = JsonPath.read(ok(post("/group/schemes/" + scheme + "/claims"), "admin",
                "{\"memberKey\":\"M2\",\"benefitType\":\"INCOME_PROTECTION\",\"eventDate\":\"2026-03-01\",\"amount\":5000}")
                .andReturn().getResponse().getContentAsString(), "$.ref");
        ok(post("/group/claims/" + claim + "/decision"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Disabled\"}")
                .andExpect(jsonPath("$.status").value("IN_PAYMENT"));

        // Review: experience (loss ratio 1000% on 500 billed, 2 lives) and the impact checks.
        String prepared = ok(post("/group/schemes/" + scheme + "/renewals"), "grpadmin", "{}")
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.nextYear").value(2))
                .andExpect(jsonPath("$.nextStart").value("2027-01-01"))
                .andExpect(jsonPath("$.experience.premiumBilled").value(500.0))
                .andExpect(jsonPath("$.experience.claimsIncurred").value(5000.0))
                .andExpect(jsonPath("$.recommendedChangePercent").value(15.5))
                .andExpect(jsonPath("$.versions[0].terms[0].expiringRate").value(1.0))
                .andExpect(jsonPath("$.versions[0].terms[0].proposedRate").value(1.155))
                .andExpect(jsonPath("$.checks.membersReachingCessationAge[0].memberKey").value("M1"))
                .andExpect(jsonPath("$.checks.incomeBenefitsInPayment[0].ref").value(claim))
                .andExpect(jsonPath("$.checks.incomeBenefitsInPayment[0].treatment").value(containsString("does not end it")))
                .andReturn().getResponse().getContentAsString();
        String renewal = JsonPath.read(prepared, "$.ref");
        mockMvc.perform(json(post("/group/schemes/" + scheme + "/renewals"), "grpadmin", "{}"))
                .andExpect(status().isConflict());

        // Negotiation: the sponsor's counter-offer becomes version 2; version 1 is kept, superseded.
        ok(post("/group/renewals/" + renewal + "/versions"), "grpadmin",
                "{\"terms\":[{\"category\":\"STAFF\",\"ratePerMille\":1.10,\"cessationAge\":66}],"
                        + "\"note\":\"Sponsor counter-offer at 10%\"}")
                .andExpect(jsonPath("$.status").value("NEGOTIATING"))
                .andExpect(jsonPath("$.versions[0].version").value(2))
                .andExpect(jsonPath("$.versions[0].terms[0].proposedRate").value(1.1))
                .andExpect(jsonPath("$.versions[0].annualPremium").value(6600.0))
                .andExpect(jsonPath("$.versions[1].status").value("SUPERSEDED"));

        // Acceptance: an overlapping start is refused, a gap needs a reason.
        mockMvc.perform(json(post("/group/renewals/" + renewal + "/acceptance"), "grpadmin",
                        "{\"sponsorReference\":\"SPN-1\",\"note\":\"Signed\",\"nextStartDate\":\"2026-12-15\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/group/renewals/" + renewal + "/acceptance"), "grpadmin",
                        "{\"sponsorReference\":\"SPN-1\",\"note\":\"Signed\",\"nextStartDate\":\"2027-02-01\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("gap")));
        ok(post("/group/renewals/" + renewal + "/acceptance"), "grpadmin",
                "{\"version\":2,\"sponsorReference\":\"SPN-1\",\"note\":\"Signed renewal letter\"}")
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.acceptedVersion").value(2));

        // Approval by someone else renews the scheme into year 2 on the accepted terms.
        mockMvc.perform(json(post("/group/renewals/" + renewal + "/approval"), "grpadmin", "{\"note\":\"mine\"}"))
                .andExpect(status().isForbidden());
        ok(post("/group/renewals/" + renewal + "/approval"), "admin", "{\"note\":\"Terms approved\"}")
                .andExpect(jsonPath("$.status").value("RENEWED"));
        mockMvc.perform(get("/group/schemes/" + scheme).with(signedIn("grpadmin", "password")))
                .andExpect(jsonPath("$.scheme.policyYear").value(2))
                .andExpect(jsonPath("$.scheme.renewalDate").value("2028-01-01"))
                .andExpect(jsonPath("$.categories[0].ratePerMille").value(1.1));
        assertThat(events(Events.SCHEME_RENEWED, scheme)).singleElement()
                .satisfies(e -> assertThat(e.getPayload()).contains("\"renewalRef\":\"" + renewal + "\"")
                        .contains("\"incomeBenefitsContinuing\":1"));
        // The income benefit in payment continues; December keeps the expiring rate, January bills the new one.
        assertThat(jdbc.queryForObject("select claim_status from grp_claim where record_ref = ?", String.class, claim))
                .isEqualTo("IN_PAYMENT");
        ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2026-12\"}")
                .andExpect(jsonPath("$.amount").value(500.0));
        ok(post("/group/schemes/" + scheme + "/invoices"), "grpadmin", "{\"period\":\"2027-01\"}")
                .andExpect(jsonPath("$.amount").value(550.0))
                .andExpect(jsonPath("$.policyYear").value(2));
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
