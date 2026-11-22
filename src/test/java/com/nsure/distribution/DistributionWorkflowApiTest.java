package com.nsure.distribution;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.support.ModuleTestUsers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SH06 workflow: accrual with receipt and trace, a receipt reversal clawing back exactly that receipt's commission
 * once (AT-SH06-01), statement approval and a released payment batch, clawback on lapse within the clawback period,
 * a negative statement carried forward, the exception queue with agreements, blocked sales from a suspended
 * intermediary (AT-SH06-02) and a future-dated transfer that moves entitlement only from its date (AT-SH06-03).
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-distribution-workflow;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class DistributionWorkflowApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxDispatcher dispatcher;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aReversedReceiptClawsBackItsOwnCommissionAndStatementsArePaidInAnApprovedBatch() throws Exception {
        String agent = appoint();
        String policy = pendingPolicy("Term Life");
        assign(policy, agent).andExpect(status().isOk());
        pay(policy);
        pay(policy);
        dispatch();
        BigDecimal premium = premium(policy);
        List<Map<String, Object>> lines = commissions(policy);
        assertThat(lines).hasSize(2);
        Map<String, Object> initial = lines.get(1);
        assertThat(initial.get("basis")).isEqualTo("INITIAL");
        assertThat((String) initial.get("calcTrace")).contains("20.00% initial rate").contains("receipt INV-");
        String firstReceipt = (String) initial.get("receiptRef");

        // AT-SH06-01: reversing the first receipt takes back the 20% it earned, not the latest (renewal) accrual.
        String request = mockMvc.perform(json(post("/collections/receipts/" + firstReceipt + "/reverse"), "finance",
                        "{\"kind\":\"RETURNED\",\"reason\":\"Debit returned: insufficient funds\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/collections/reversal-requests/" + JsonPath.read(request, "$.ref") + "/approve"),
                "admin", "{}")).andExpect(status().isOk());
        dispatch();
        dispatch();
        List<Map<String, Object>> clawbacks = commissions(policy).stream().filter(l -> "CLAWBACK".equals(l.get("kind")))
                .toList();
        assertThat(clawbacks).singleElement().satisfies(c -> {
            assertThat(c.get("reversesRef")).isEqualTo(initial.get("ref"));
            amount(c.get("amount"), percentOf(premium, "20").negate());
        });

        // Issued by Finance, approved by someone else, then paid in a batch released by a third look.
        String statement = JsonPath.read(mockMvc.perform(json(post("/distribution/statements"), "finance",
                        "{\"intermediaryRef\":\"" + agent + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/distribution/payment-batches").with(signedIn("finance", "password")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/distribution/statements/" + statement + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String batch = mockMvc.perform(post("/distribution/payment-batches").with(signedIn("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andReturn().getResponse().getContentAsString();
        String batchRef = JsonPath.read(batch, "$.ref");
        assertThat((List<String>) JsonPath.read(batch, "$.lines[*].statementRef")).contains(statement);
        mockMvc.perform(post("/distribution/payment-batches/" + batchRef + "/release").with(signedIn("finance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/distribution/payment-batches/" + batchRef + "/release").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(get("/distribution/statements").with(signedIn("distribution", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + statement + "')].status").value(org.hamcrest.Matchers.hasItem("PAID")))
                .andExpect(jsonPath("$[?(@.ref == '" + statement + "')].batchRef").value(org.hamcrest.Matchers.hasItem(batchRef)));
    }

    @Test
    void aLapseInTheClawbackPeriodTakesBackInitialCommissionAndANegativeStatementIsCarriedForward() throws Exception {
        String agent = appoint();
        String lapsing = pendingPolicy("Term Life");
        assign(lapsing, agent).andExpect(status().isOk());
        pay(lapsing);
        dispatch();
        BigDecimal premium = premium(lapsing);
        String paid = issueApproveAndPay(agent);

        // Lapsed three months later, within the 12-month clawback period: the initial commission comes back.
        tx.executeWithoutResult(s -> outbox.publish(Events.POLICY_TERMINATED, lapsing, Map.of("policyRef", lapsing,
                "customerName", "Client", "product", "Term Life", "reason", "LAPSE",
                "terminatedOn", LocalDate.now().plusMonths(3).toString(), "note", "No premium"), "system"));
        dispatch();
        dispatch();
        List<Map<String, Object>> clawbacks = commissions(lapsing).stream().filter(l -> "CLAWBACK".equals(l.get("kind")))
                .toList();
        assertThat(clawbacks).singleElement().satisfies(c -> amount(c.get("amount"), percentOf(premium, "20").negate()));

        // The clawback nets a negative statement: it cannot be approved, and is carried onto the next one.
        String negative = JsonPath.read(mockMvc.perform(json(post("/distribution/statements"), "finance",
                        "{\"intermediaryRef\":\"" + agent + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/distribution/statements/" + negative + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isUnprocessableEntity());
        String next = pendingPolicy("Term Life");
        assign(next, agent).andExpect(status().isOk());
        pay(next);
        dispatch();
        String carried = mockMvc.perform(json(post("/distribution/statements"), "finance",
                        "{\"intermediaryRef\":\"" + agent + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.carriedFromRef").value(negative))
                .andReturn().getResponse().getContentAsString();
        amount(JsonPath.read(carried, "$.netAmount"), percentOf(premium(next), "20").subtract(percentOf(premium, "20")));
        mockMvc.perform(get("/distribution/statements").with(signedIn("distribution", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + negative + "')].status").value(org.hamcrest.Matchers.hasItem("CARRIED_FORWARD")))
                .andExpect(jsonPath("$[?(@.ref == '" + paid + "')].status").value(org.hamcrest.Matchers.hasItem("PAID")));
    }

    @Test
    void aPremiumOutsideTheAgreementOpensAnExceptionThatIsRetriedAfterANewAgreementVersion() throws Exception {
        String agent = appoint();
        String draft = JsonPath.read(mockMvc.perform(json(post("/distribution/agreements"), "distribution",
                        "{\"intermediaryRef\":\"" + agent + "\",\"products\":[\"Term Life\"],\"clawbackMonths\":12,"
                                + "\"bankName\":\"Stanbic\",\"bankAccount\":\"9090123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.accountMasked").value("****3456"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/distribution/agreements/" + draft + "/activate").with(signedIn("distribution", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/distribution/agreements/" + draft + "/activate").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        String policy = pendingPolicy("Whole of Life");
        assign(policy, agent).andExpect(status().isOk());
        pay(policy);
        dispatch();
        assertThat(commissions(policy)).isEmpty();
        Map<String, Object> exception = exceptions(policy);
        assertThat(exception.get("reasonCode")).isEqualTo("NOT_IN_AGREEMENT");
        assertThat(exception.get("status")).isEqualTo("OPEN");
        String ref = (String) exception.get("ref");
        mockMvc.perform(post("/distribution/exceptions/" + ref + "/retry").with(signedIn("distribution", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        String v2 = JsonPath.read(mockMvc.perform(json(post("/distribution/agreements"), "distribution",
                        "{\"intermediaryRef\":\"" + agent + "\",\"clawbackMonths\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/distribution/agreements/" + v2 + "/activate").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/distribution/exceptions/" + ref + "/retry").with(signedIn("distribution", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.commissionRef").value(org.hamcrest.Matchers.startsWith("COM-")));
        assertThat(commissions(policy)).singleElement().satisfies(c -> assertThat(c.get("agreementRef")).isEqualTo(v2));
        mockMvc.perform(get("/distribution/agreements").with(signedIn("distribution", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + draft + "')].status").value(org.hamcrest.Matchers.hasItem("SUPERSEDED")));
    }

    @Test
    void aSuspendedIntermediarySellsNothingNewAndAFutureTransferMovesEntitlementOnlyFromItsDate() throws Exception {
        // AT-SH06-02: the seeded suspended agent cannot sell; the application is refused with the reason.
        String customer = customer();
        mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Term Life\",\"tier\":\"Silver\",\"declarationAccepted\":true,"
                        + "\"channel\":\"AGENT\",\"intermediaryCode\":\"INT-00003\","
                        + "\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot sell new business")));

        // Lapsing an appointment keeps its statements as they were.
        String agent = appoint();
        String policy = pendingPolicy("Term Life");
        assign(policy, agent).andExpect(status().isOk());
        pay(policy);
        dispatch();
        String statement = issueApproveAndPay(agent);
        mockMvc.perform(json(put("/distribution/intermediaries/" + agent), "distribution",
                        "{\"status\":\"LAPSED\",\"reason\":\"Appointment not renewed\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/distribution/statements").with(signedIn("distribution", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + statement + "')].status").value(org.hamcrest.Matchers.hasItem("PAID")))
                .andExpect(jsonPath("$[?(@.ref == '" + statement + "')].lineCount").value(org.hamcrest.Matchers.hasItem(1)));

        // AT-SH06-03: a transfer approved today but effective tomorrow leaves today's premium with the old agent.
        String from = appoint();
        String to = appoint();
        String moving = pendingPolicy("Term Life");
        assign(moving, from).andExpect(status().isOk());
        String pending = mockMvc.perform(json(post("/distribution/assignments"), "distribution",
                        "{\"policyRef\":\"" + moving + "\",\"intermediaryRef\":\"" + to + "\",\"effectiveFrom\":\""
                                + LocalDate.now().plusDays(1) + "\",\"reason\":\"Client asked to move broker\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/distribution/assignments/" + JsonPath.read(pending, "$.id") + "/approve")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        pay(moving);
        dispatch();
        assertThat(commissions(moving)).singleElement().satisfies(c -> assertThat(c.get("intermediaryRef")).isEqualTo(from));
        mockMvc.perform(get("/distribution/assignments?policyRef=" + moving).with(signedIn("distribution", "password")))
                .andExpect(jsonPath("$[?(@.intermediaryRef == '" + from + "')].endedOn")
                        .value(org.hamcrest.Matchers.hasItem(LocalDate.now().plusDays(1).toString())));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String issueApproveAndPay(String agent) throws Exception {
        String statement = JsonPath.read(mockMvc.perform(json(post("/distribution/statements"), "finance",
                        "{\"intermediaryRef\":\"" + agent + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/distribution/statements/" + statement + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/distribution/statements/" + statement + "/pay").with(signedIn("finance", "password")))
                .andExpect(status().isOk());
        return statement;
    }

    private Map<String, Object> exceptions(String policy) throws Exception {
        List<Map<String, Object>> found = JsonPath.read(mockMvc.perform(get("/distribution/exceptions")
                        .with(signedIn("distribution", "password")))
                .andReturn().getResponse().getContentAsString(), "$[?(@.policyRef == '" + policy + "')]");
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private void dispatch() {
        dispatcher.dispatch();
        dispatcher.dispatch();
    }

    private String appoint() throws Exception {
        int n = SEQ.incrementAndGet();
        return JsonPath.read(mockMvc.perform(json(post("/distribution/intermediaries"), "distribution",
                        "{\"name\":\"Workflow Agent " + n + "\",\"type\":\"AGENT\",\"licenceNumber\":\"WF-" + n
                                + "\",\"appointmentExpiry\":\"" + LocalDate.now().plusYears(2) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.ref");
    }

    private ResultActions assign(String policy, String intermediary) throws Exception {
        return mockMvc.perform(json(post("/distribution/assignments"), "distribution",
                "{\"policyRef\":\"" + policy + "\",\"intermediaryRef\":\"" + intermediary + "\"}"));
    }

    private String customer() throws Exception {
        int n = SEQ.incrementAndGet();
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Workflow Client " + n + "\",\"email\":\"wfclient" + n + "@example.com\","
                                + "\"phone\":\"+267 74 000 0" + (10 + n) + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return customer;
    }

    private String pendingPolicy(String product) throws Exception {
        String customer = customer();
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                        + customer + "\",\"product\":\"" + product + "\",\"amount\":20000,\"premium\":80}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void pay(String policy) throws Exception {
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
    }

    private BigDecimal premium(String policy) {
        return tx.execute(s -> em.createQuery("select p.premium from InsurancePolicy p where p.ref = :ref",
                BigDecimal.class).setParameter("ref", policy).getSingleResult());
    }

    private List<Map<String, Object>> commissions(String policy) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/distribution/commissions?policyRef=" + policy)
                        .with(signedIn("distribution", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
    }

    private static BigDecimal percentOf(BigDecimal amount, String percent) {
        return amount.multiply(new BigDecimal(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static void amount(Object actual, BigDecimal expected) {
        assertThat(new BigDecimal(String.valueOf(actual))).isEqualByComparingTo(expected);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
