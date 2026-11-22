package com.nsure.finance;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.support.ModuleTestUsers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SH08: delivery to the simulated corporate GL keyed by batch id (a timeout is resolved by asking by batch id, never
 * by sending blind; AT-SH08-02), the export file, the close report by category (AT-SH08-03), product-specific posting
 * rules with journal dimensions and cost centre, a NOT_POSTED recognition basis, an event type set to BLOCK refusing
 * the business action, soft-close stopping manual journals, and the aged receivables/payables report.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-subledger-controls;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class SubledgerControlsApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aGlTimeoutIsResolvedByBatchIdAndNothingIsPostedTwice() throws Exception {
        paidPolicy("Term Life");
        dispatch();
        String batch = exportBatch();
        // The GL posts the batch but its answer is lost.
        mockMvc.perform(json(post("/finance/gl-batches/" + batch + "/submit"), "finance", "{\"simulate\":\"TIMEOUT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.deliveryNote").value(org.hamcrest.Matchers.containsString("no answer")));
        mockMvc.perform(json(post("/finance/gl-batches/" + batch + "/submit"), "finance", "{\"simulate\":\"ACCEPT\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/finance/gl-batches/" + batch + "/status-check").with(signedIn("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.glReference").value("SIMGL-" + batch));
        assertThat(jdbc.queryForObject("select deliveries from fin_gl_simulator where batch_ref = ?", Integer.class,
                batch)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where event_type = 'GlBatchAccepted'"
                + " and aggregate_ref = ?", Integer.class, batch)).isEqualTo(1);
        // Reconciled against the totals the GL itself reports for the batch id.
        mockMvc.perform(json(post("/finance/gl-batches/" + batch + "/reconcile"), "finance", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILED"));
        String file = mockMvc.perform(get("/finance/gl-batches/" + batch + "/export").with(signedIn("compliance", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(file, "$.lines[0].lineId")).isEqualTo(batch + "-0001");
        BigDecimal debits = ((List<Object>) JsonPath.read(file, "$.lines[*].debit")).stream()
                .map(v -> new BigDecimal(v.toString())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(new BigDecimal(JsonPath.read(file, "$.debitTotal").toString()));

        // A batch the GL never received goes back to prepared when asked by batch id, and can be sent again.
        paidPolicy("Term Life");
        dispatch();
        String lost = exportBatch();
        mockMvc.perform(post("/finance/gl-batches/" + lost + "/submit").with(signedIn("finance", "password")))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        mockMvc.perform(post("/finance/gl-batches/" + lost + "/status-check").with(signedIn("finance", "password")))
                .andExpect(jsonPath("$.status").value("PREPARED"));
        // A rejection releases the journals; the close report names the rejected batch.
        mockMvc.perform(json(post("/finance/gl-batches/" + lost + "/submit"), "finance", "{\"simulate\":\"REJECT\"}"))
                .andExpect(jsonPath("$.status").value("REJECTED"));
        String report = mockMvc.perform(get("/finance/periods/" + YearMonth.now() + "/close-report")
                        .with(signedIn("finance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<Object>) JsonPath.read(report, "$[?(@.code == 'GL_REJECTED')].examples[*]")).contains(lost);
        assertThat((List<Integer>) JsonPath.read(report, "$[?(@.code == 'UNEXPORTED')].count")).allMatch(c -> c > 0);
    }

    @Test
    void productRulesCarryDimensionsAndANotPostedBasisAndBlockingAreHonoured() throws Exception {
        // A Term Life version of PremiumReceived with its own cost centre, approved by a second person.
        String body = "{\"debitAccount\":\"1000\",\"creditAccount\":\"4000\",\"domain\":\"INDIVIDUAL\","
                + "\"product\":\"Term Life\",\"costCentre\":\"CC-TERM\",\"reason\":\"Term Life has its own cost centre\","
                + "\"effectiveFrom\":\"" + LocalDate.now() + "\"}";
        String proposed = mockMvc.perform(json(put("/finance/posting-rules/PremiumReceived"), "admin", body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product").value("Term Life"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/finance/posting-rules/PremiumReceived/versions/" + JsonPath.read(proposed, "$.version")
                + "/approve").with(signedIn("secadmin", "password"))).andExpect(status().isOk());
        String term = paidPolicy("Term Life");
        String whole = paidPolicy("Whole of Life");
        dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=PremiumReceived")
                        .with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> termJournal = ((List<Map<String, Object>>) JsonPath.read(journals,
                "$[?(@.sourceRef == '" + term + "')]")).get(0);
        assertThat(termJournal.get("costCentre")).isEqualTo("CC-TERM");
        assertThat(termJournal.get("product")).isEqualTo("Term Life");
        assertThat(termJournal.get("policyRef")).isEqualTo(term);
        assertThat((List<String>) JsonPath.read(journals, "$[?(@.sourceRef == '" + whole + "')].costCentre"))
                .containsExactly("CC-IND");

        // NS-SH08-002: InvoiceIssued is NOT_POSTED under the cash basis: no journal, no exception.
        assertThat((List<?>) JsonPath.read(mockMvc.perform(get("/finance/journals?eventType=InvoiceIssued")
                .with(signedIn("finance", "password"))).andReturn().getResponse().getContentAsString(), "$")).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from fin_exception where event_type = 'InvoiceIssued'",
                Integer.class)).isZero();

        // NS-SH08-003: Family Funeral premiums set to BLOCK while their rule is inactive: the payment is refused whole.
        String block = "{\"debitAccount\":\"1000\",\"creditAccount\":\"4000\",\"domain\":\"INDIVIDUAL\","
                + "\"product\":\"Family Funeral\",\"status\":\"INACTIVE\",\"failureMode\":\"BLOCK\","
                + "\"reason\":\"Funeral posting suspended pending a mapping review\",\"effectiveFrom\":\"" + LocalDate.now() + "\"}";
        String blocked = mockMvc.perform(json(put("/finance/posting-rules/PremiumReceived"), "admin", block))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/finance/posting-rules/PremiumReceived/versions/" + JsonPath.read(blocked, "$.version")
                + "/approve").with(signedIn("secadmin", "password"))).andExpect(status().isOk());
        String funeral = pendingPolicy("Family Funeral");
        mockMvc.perform(json(post("/portfolio/policies/" + funeral + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("block the business action")));
        assertThat(jdbc.queryForObject("select status from ins_policy where record_ref = ?", String.class, funeral))
                .isEqualTo("Pending Payment");
        assertThat(jdbc.queryForObject("select count(*) from premium_payment where policy_ref = ?", Integer.class,
                funeral)).isZero();
    }

    @Test
    void aSoftClosedPeriodTakesNoManualJournalsAndTheAgeingReportBucketsOpenItems() throws Exception {
        String next = YearMonth.now().plusMonths(2).toString();
        mockMvc.perform(json(post("/finance/periods/" + next + "/soft-close"), "finance", "{}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/finance/manual-journals"), "admin", "{\"description\":\"Accrual\","
                        + "\"reason\":\"Month-end accrual for bank charges\",\"accountingDate\":\"" + next + "-05\","
                        + "\"lines\":[{\"account\":\"5000\",\"debit\":\"10\"},{\"account\":\"1000\",\"credit\":\"10\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("soft-closed")));

        String policy = paidPolicy("Term Life");
        jdbc.update("update ins_policy set next_premium_due = ? where record_ref = ?",
                java.sql.Date.valueOf(LocalDate.now().minusDays(40)), policy);
        mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password"))).andExpect(status().isOk());
        String ageing = mockMvc.perform(get("/finance/ageing").with(signedIn("compliance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(ageing, "$.receivables[?(@.party == '" + policy + "')].bucket"))
                .containsExactly("31-60");
        assertThat(new BigDecimal(JsonPath.read(ageing, "$.receivableTotals.TOTAL").toString())).isPositive();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String exportBatch() throws Exception {
        return JsonPath.read(mockMvc.perform(post("/finance/gl-batches").with(signedIn("finance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private void dispatch() {
        dispatcher.dispatch();
        dispatcher.dispatch();
    }

    private String pendingPolicy(String product) throws Exception {
        int n = SEQ.incrementAndGet();
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Ledger Client " + n + "\",\"email\":\"ledger" + n + "@example.com\","
                                + "\"phone\":\"+267 75 000 0" + (10 + n) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + customer + "\",\"product\":\"" + product + "\",\"amount\":20000,\"premium\":60}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String paidPolicy(String product) throws Exception {
        String policy = pendingPolicy(product);
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        return policy;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
