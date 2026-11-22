package com.nsure.collections;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
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
 * NS-SH04-001/003/006: debit-order due dates on the debit day and a working day with a projected schedule; receipts
 * matched by instalment reference or payer account, paying whole premiums oldest-first with the remainder in
 * suspense; and statement lines for attempts closed as failed reported as late settlements, with control totals.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-receipt-matching;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class ReceiptMatchingApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aDebitOrderFallsDueOnItsDebitDayOnAWorkingDayAndTheScheduleIsProjected() throws Exception {
        String policy = paidPolicy();
        // The 13th of next month is the debit day; the period starts on the 1st.
        LocalDate start = LocalDate.now().plusMonths(1).withDayOfMonth(1);
        jdbc.update("update ins_policy set next_premium_due = ? where record_ref = ?", java.sql.Date.valueOf(start), policy);
        mockMvc.perform(json(post("/collections/mandates"), "finance", "{\"policyRef\":\"" + policy
                + "\",\"channel\":\"DEBIT_ORDER\",\"institution\":\"FNBB\",\"accountNumber\":\"62001110000\","
                + "\"debitDay\":13}")).andExpect(status().isOk());
        String account = account(policy);
        List<String> due = JsonPath.read(account, "$.schedule[*].dueDate");
        assertThat(due).hasSize(12);
        LocalDate first = LocalDate.parse(due.get(0));
        LocalDate expected = start.withDayOfMonth(13);
        while (expected.getDayOfWeek() == DayOfWeek.SATURDAY || expected.getDayOfWeek() == DayOfWeek.SUNDAY) {
            expected = expected.plusDays(1);
        }
        assertThat(first).isEqualTo(expected);
        assertThat((List<String>) JsonPath.read(account, "$.schedule[*].currency")).containsOnly("BWP");
    }

    @Test
    void anOverpaymentPaysWholePremiumsOldestFirstAndTheRestWaitsInSuspense() throws Exception {
        String policy = paidPolicy();
        jdbc.update("update ins_policy set next_premium_due = ? where record_ref = ?",
                java.sql.Date.valueOf(LocalDate.now().plusDays(2)), policy);
        mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password"))).andExpect(status().isOk());
        String instalment = JsonPath.read(account(policy), "$.instalments[1].ref");
        // Quoting the instalment's own reference: 2.5 premiums pay two periods; half a premium stays unapplied.
        String over = "OVR-" + SEQ.incrementAndGet();
        providerHolds(over, "125.00");
        String body = mockMvc.perform(json(post("/integration/payment-notifications"), "ops",
                        "{\"provider\":\"StatementBank\",\"providerReference\":\"" + over
                                + "\",\"policyRef\":\"" + instalment + "\",\"amount\":\"125.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENSE"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.detail")).contains("Paid 2 premium(s)").contains("matched by instalment");
        String account = account(policy);
        assertThat((List<String>) JsonPath.read(account, "$.instalments[*].status")).containsExactly("PAID", "PAID", "PAID");
        String suspense = mockMvc.perform(get("/finance/suspense").with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(((List<Object>) JsonPath.read(suspense, "$[?(@.policyRef == '" + policy
                + "')].unapplied")).get(0).toString())).isEqualByComparingTo("25.00");

        // An unknown reference is matched by the payer's account on an active mandate.
        mockMvc.perform(json(post("/collections/mandates"), "finance", "{\"policyRef\":\"" + policy
                + "\",\"channel\":\"MOBILE_WALLET\",\"institution\":\"MyZaka\",\"accountNumber\":\"+26772220000\"}"))
                .andExpect(status().isOk());
        String payer = "PAYER-" + SEQ.incrementAndGet();
        providerHolds(payer, "50.00");
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops",
                        "{\"provider\":\"StatementBank\",\"providerReference\":\"" + payer
                                + "\",\"policyRef\":\"UNKNOWN-REF\",\"payerAccount\":\"+26772220000\",\"amount\":\"50.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.businessRef").value(policy));
    }

    @Test
    void aStatementLineForAnAttemptClosedAsFailedIsALateSettlementAndTotalsAreShown() throws Exception {
        String policy = paidPolicy();
        jdbc.update("update payment_transaction set status = 'FAILED' where policy_ref = ?", policy);
        String reference = jdbc.queryForObject("select provider_reference from payment_transaction where policy_ref = ?",
                String.class, policy);
        String provider = jdbc.queryForObject("select provider from payment_transaction where policy_ref = ?",
                String.class, policy);
        LocalDate today = LocalDate.now();
        String statement = mockMvc.perform(json(post("/collections/statements"), "finance", "{\"provider\":\""
                        + provider + "\",\"periodFrom\":\"" + today.minusDays(1) + "\",\"periodTo\":\"" + today
                        + "\",\"lines\":[{\"reference\":\"" + reference + "\",\"amount\":\"50.00\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(JsonPath.read(statement, "$.totals.statementTotal").toString()))
                .isEqualByComparingTo("50.00");
        String items = mockMvc.perform(get("/collections/statements/" + JsonPath.read(statement, "$.ref") + "/items")
                .with(signedIn("finance", "password"))).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(items, "$[?(@.providerRef == '" + reference + "')].status"))
                .containsExactly("LATE_SETTLEMENT");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** The simulated bank records that it received the money, as a real bank would on its side. */
    private void providerHolds(String reference, String amount) throws Exception {
        mockMvc.perform(json(post("/integration/simulator/provider-receipts"), "ops", "{\"provider\":\"StatementBank\","
                + "\"providerReference\":\"" + reference + "\",\"amount\":\"" + amount + "\"}"))
                .andExpect(status().isOk());
    }

    private String paidPolicy() throws Exception {
        int n = SEQ.incrementAndGet();
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Matching Client " + n + "\",\"email\":\"match" + n + "@example.com\","
                                + "\"phone\":\"+267 77 000 0" + (10 + n) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        return policy;
    }

    private String account(String policy) throws Exception {
        return mockMvc.perform(get("/collections/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
