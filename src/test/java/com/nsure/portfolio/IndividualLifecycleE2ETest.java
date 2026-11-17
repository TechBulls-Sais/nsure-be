package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * §14 individual lifecycle, chained end to end through the API: quote → application and underwriting → issue →
 * first premium (inception, receipt, journal, schedule, message) → endorsement → grace, cover ended and resumed by a
 * late premium → claim. Each step checks the versions, the cover history and the financial effect it should leave.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-individual-lifecycle;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class IndividualLifecycleE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PolicyWorkflow policies;

    @Test
    void oneIndividualPolicyFromQuoteToClaim() throws Exception {
        // Party: created, identity checked and verified.
        String customer = JsonPath.read(perform(post("/portfolio/customers"), "{\"name\":\"Kagiso Lifecycle\","
                        + "\"email\":\"kagiso.lifecycle@example.com\",\"phone\":\"+267 71 909 090\",\"poiType\":\"Omang\","
                        + "\"poiNumber\":\"919000001\",\"dateOfBirth\":\"" + LocalDate.now().minusYears(34) + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        perform(post("/portfolio/customers/" + customer + "/kyc"), "{}").andExpect(status().isOk());

        // Quote, version 1, accepted by the customer.
        String quote = JsonPath.read(perform(post("/portfolio/quotes"), "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        perform(post("/portfolio/quotes/" + quote + "/accept"), "{\"version\":1}").andExpect(status().isOk());

        // Application on that quote, with answers and the declaration; the rules accept it.
        String application = perform(post("/portfolio/applications"), "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Term Life\",\"quoteRef\":\"" + quote + "\",\"declarationAccepted\":true,"
                        + "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Ready to issue"))
                .andExpect(jsonPath("$.quoteRef").value(quote))
                .andExpect(jsonPath("$.quoteVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String app = JsonPath.read(application, "$.id");
        BigDecimal premium = new BigDecimal(JsonPath.read(application, "$.premium").toString());
        assertThat(events("ApplicationSubmitted", app)).isEqualTo(1);

        // Issue: an offer awaiting its first premium, on the accepted quote version.
        String policy = JsonPath.read(perform(post("/portfolio/policies").header("Idempotency-Key", "life-" + app),
                        "{\"applicationRef\":\"" + app + "\",\"product\":\"Term Life\",\"tier\":\"Bronze\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"))
                .andExpect(jsonPath("$.quoteRef").value(quote))
                .andExpect(jsonPath("$.quoteVersion").value(1))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(events("IssueRequested", app)).isEqualTo(1);
        assertThat(events("PolicyIssued", policy)).isZero();

        // First premium: cover starts, a receipt and a balanced journal follow, then the schedule and the message.
        perform(post("/portfolio/policies/" + policy + "/payments"), "{\"method\":\"Bank EFT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.coverStartDate").value(LocalDate.now().toString()));
        assertThat(events("PolicyIssued", policy)).isEqualTo(1);
        assertThat(events("PremiumReceived", policy)).isEqualTo(1);
        dispatch();
        dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=PremiumReceived")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Object> amounts = JsonPath.read(journals, "$[?(@.sourceRef == '" + policy + "')].amount");
        assertThat(amounts).hasSize(1);
        assertThat(new BigDecimal(amounts.get(0).toString())).isEqualByComparingTo(premium);
        mockMvc.perform(get("/portfolio/policies/" + policy + "/documents").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        assertThat(jdbc.queryForObject("select count(*) from com_message where business_ref = ? and event_type = ?",
                Long.class, policy, "PolicyIssued")).isGreaterThanOrEqualTo(1);

        // Endorsement: the payer changes, and the change is an event and a version of the policy.
        long before = version(policy);
        perform(post("/servicing/policies/" + policy + "/endorsements"), "{\"type\":\"PAYER\",\"payerName\":\"Lesego"
                + " Lifecycle\",\"payerRelationship\":\"Spouse\",\"reason\":\"Spouse now pays\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(events("PolicyChanged", policy)).isEqualTo(1);
        assertThat(version(policy)).isGreaterThan(before);

        // Grace runs out: cover ends (the policy stays in force), and a late premium resumes it from the same start.
        LocalDate start = LocalDate.now().minusMonths(3);
        backdate(policy, start, LocalDate.now().minusDays(40), LocalDate.now().minusDays(10));
        tx.execute(s -> policies.lapseOverdue());
        assertThat(statusOf(policy)).isEqualTo(PolicyWorkflow.ACTIVE_NO_COVER);
        assertThat(events("CoverLapsed", policy)).isEqualTo(1);
        perform(post("/portfolio/policies/" + policy + "/payments"), "{\"method\":\"Orange Money\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.coverStartDate").value(start.toString()));
        assertThat(events("PremiumReceived", policy)).isEqualTo(2);

        // Claim against the policy.
        String claim = JsonPath.read(perform(post("/portfolio/claims"), "{\"policy\":\"" + policy + "\",\"claimantName\":"
                        + "\"Lesego Lifecycle\",\"claimantMobile\":\"+267 71 909 091\",\"claimantRelationship\":\"Spouse\","
                        + "\"type\":\"Death\",\"description\":\"Lifecycle check\",\"eventDate\":\"" + LocalDate.now() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy").value(policy))
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(events("ClaimReceived", claim)).isEqualTo(1);

        // The cover history tells the whole story in order; the application's timeline ends in its issue.
        String history = mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(history, "$[*].newState")).containsSubsequence(
                "Pending Payment", "Active", PolicyWorkflow.ACTIVE_NO_COVER, "Active");
        assertThat(JsonPath.<List<String>>read(mockMvc.perform(get("/portfolio/history/application/" + app)
                        .with(signedIn("admin", "password"))).andReturn().getResponse().getContentAsString(),
                "$[*].action")).containsSubsequence("Submitted", "Issued");
        mockMvc.perform(get("/portfolio/quotes/" + quote).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[0].policyRef").value(policy))
                .andExpect(jsonPath("$[0].applicationRef").value(app));
    }

    private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request,
            String body) throws Exception {
        return mockMvc.perform(request.with(signedIn("admin", "password")).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void dispatch() throws Exception {
        mockMvc.perform(post("/integration/outbox/dispatch").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
    }

    private long events(String type, String aggregate) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Long.class, type, aggregate);
    }

    private long version(String policy) {
        return jdbc.queryForObject("select row_version from ins_policy where record_ref = ?", Long.class, policy);
    }

    private String statusOf(String policy) {
        return jdbc.queryForObject("select status from ins_policy where record_ref = ?", String.class, policy);
    }

    /** As if cover started at {@code start} and the premium due on {@code nextDue} was missed. */
    private void backdate(String ref, LocalDate start, LocalDate nextDue, LocalDate graceEnd) {
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.coverStartDate = :start,"
                        + " p.nextPremiumDue = :due, p.gracePeriodEnd = :grace where p.ref = :ref")
                .setParameter("start", start).setParameter("due", nextDue).setParameter("grace", graceEnd)
                .setParameter("ref", ref).executeUpdate());
        tx.executeWithoutResult(s -> em.createQuery("update PremiumPayment t set t.periodStart = :start,"
                        + " t.periodEnd = :end where t.policyRef = :ref")
                .setParameter("start", start).setParameter("end", start.plusMonths(1).minusDays(1))
                .setParameter("ref", ref).executeUpdate());
        tx.executeWithoutResult(s -> em.createQuery("update Instalment i set i.periodStart = :start, i.dueDate = :start,"
                        + " i.periodEnd = :end where i.policyRef = :ref")
                .setParameter("start", start).setParameter("end", start.plusMonths(1).minusDays(1))
                .setParameter("ref", ref).executeUpdate());
    }
}
