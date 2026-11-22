package com.nsure.finance;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.support.ModuleTestUsers;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NS-SH04-005 and SH04-EVENTS: a cooling-off cancellation raises its premium refund once; a refund paid to the
 * mandate on file is verified by it, one without a destination waits for Finance to record it and is approved by
 * someone else; approval sends a payout instruction, settles it (simulated rail) and posts the refund. A credit-life
 * PremiumRefunded is relayed as RefundSettled once.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-refund-payouts;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class RefundPayoutApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private com.nsure.integration.Outbox outbox;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aCoolingOffCancellationRaisesAVerifiedRefundThatIsPaidAndPostedOnce() throws Exception {
        // With a debit-order mandate on file: the refund goes there and the destination is verified by it.
        String withMandate = paidPolicy("Onalenna Cool", "+267 71 700 001");
        mockMvc.perform(json(post("/collections/mandates"), "finance", "{\"policyRef\":\"" + withMandate
                        + "\",\"channel\":\"DEBIT_ORDER\",\"institution\":\"FNBB\",\"accountNumber\":\"62007770001\","
                        + "\"debitDay\":3}"))
                .andExpect(status().isOk());
        cancel(withMandate);
        dispatcher.dispatch();
        dispatcher.dispatch();
        Map<String, Object> refund = refund("COOLOFF-" + withMandate);
        assertThat(refund.get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(refund.get("requestedBy")).isEqualTo("system");
        assertThat(refund.get("accountRef")).isEqualTo("62007770001");
        assertThat(refunds("COOLOFF-" + withMandate)).hasSize(1);
        String ref = (String) refund.get("ref");
        mockMvc.perform(json(post("/finance/refunds/" + ref + "/approve"), "secadmin", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.destinationCheck").value(org.hamcrest.Matchers.startsWith("Matches payment mandate MDT-")))
                .andExpect(jsonPath("$.payoutProvider").value("PAYOUT_SIM"))
                .andExpect(jsonPath("$.payoutReference").value(org.hamcrest.Matchers.startsWith("SIMPAY-RPO-")));
        // The premium refund reverses premium income: Dr 4000, Cr 1000.
        dispatcher.dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=RefundSettled").with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(journals, "$[?(@.sourceRef == '" + ref + "')].lines[*].accountCode"))
                .containsExactly("4000", "1000");

        // Without a mandate: Finance records the destination, and someone else approves.
        String noMandate = paidPolicy("Kitso Cool", "+267 71 700 002");
        cancel(noMandate);
        dispatcher.dispatch();
        String open = (String) refund("COOLOFF-" + noMandate).get("ref");
        assertThat(refund("COOLOFF-" + noMandate).get("accountRef")).isEqualTo("TO CONFIRM");
        mockMvc.perform(json(post("/finance/refunds/" + open + "/approve"), "secadmin",
                        "{\"verificationNote\":\"Called the policyholder on the number on file\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/finance/refunds/" + open + "/destination"), "admin",
                        "{\"payoutMethod\":\"Mobile Wallet\",\"accountRef\":\"+26771700002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinationSetBy").value("admin"));
        mockMvc.perform(json(post("/finance/refunds/" + open + "/approve"), "admin",
                        "{\"verificationNote\":\"Called the policyholder on the number on file\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/finance/refunds/" + open + "/approve"), "secadmin",
                        "{\"verificationNote\":\"Called the policyholder on the number on file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
    }

    @Test
    void aCreditLifeRefundIsRelayedAsRefundSettledOnce() throws Exception {
        tx.executeWithoutResult(s -> outbox.publish(Events.PREMIUM_REFUNDED, "CRF-TEST-1", Map.of("refundRef",
                "CRF-TEST-1", "loanRef", "LN-1", "policyRef", "", "amount", "120.00", "paymentReference", "BANK-9"),
                "admin"));
        dispatcher.dispatch();
        dispatcher.dispatch();
        List<String> settled = jdbc.queryForList("select payload from int_outbox_event where event_type = ?"
                + " and aggregate_ref = ?", String.class, Events.REFUND_SETTLED, "CRF-TEST-1");
        assertThat(settled).singleElement().asString().contains("\"source\":\"CREDIT_EARLY_SETTLEMENT\"")
                .contains("\"ledgerAmount\":\"0\"").contains("\"amount\":\"120.00\"");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void cancel(String policy) throws Exception {
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/cancel"), "admin",
                        "{\"reason\":\"Changed my mind within the cooling-off period\"}"))
                .andExpect(status().isOk());
    }

    private Map<String, Object> refund(String sourceRef) throws Exception {
        List<Map<String, Object>> found = refunds(sourceRef);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private List<Map<String, Object>> refunds(String sourceRef) throws Exception {
        String body = mockMvc.perform(get("/finance/refunds").with(signedIn("finance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[?(@.sourceRef == '" + sourceRef + "')]");
    }

    private String paidPolicy(String name, String phone) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                                "{\"name\":\"" + name + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\","
                                        + "\"phone\":\"" + phone + "\"}"))
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

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
