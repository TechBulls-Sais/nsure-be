package com.nsure.reinsurance;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.support.ModuleTestUsers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NS-SH07-002/003/005, AT-SH07-02: exposure at issue per life under the seeded quota share TRT-00001 (25%, demo capacity
 * BWP 300,000 per life), the excess referred for facultative placement and accepted by a second person, a benefit
 * change superseding the exposure, a returned premium adjusting exactly its own cessions, and a recovery expected at
 * liability approval with dispute and an approved write-off.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-reinsurance-exposure;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class ReinsuranceExposureApiTest {

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

    /** The Whole of Life rule set's limits before this test raised them (restored after each test). */
    private java.util.Map<String, Object> wholeOfLifeLimits;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
        // Portal purchases are underwritten (NS-IL01-005). These tests are about cession, so the large plans they buy
        // are kept inside the automatic acceptance limits for the duration of the test.
        wholeOfLifeLimits = jdbc.queryForMap("select auto_max_sum_assured, medical_evidence_above, aggregate_exposure_limit"
                + " from uw_rule_set where product = 'Whole of Life'");
        jdbc.update("update uw_rule_set set auto_max_sum_assured = 100000000, medical_evidence_above = 100000000,"
                + " aggregate_exposure_limit = 100000000 where product = 'Whole of Life'");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreLimits() {
        jdbc.update("update uw_rule_set set auto_max_sum_assured = ?, medical_evidence_above = ?, aggregate_exposure_limit = ?"
                + " where product = 'Whole of Life'", wholeOfLifeLimits.get("auto_max_sum_assured"),
                wholeOfLifeLimits.get("medical_evidence_above"), wholeOfLifeLimits.get("aggregate_exposure_limit"));
    }

    @Test
    void exposureIsCededPerLifeAtIssueAndTheExcessIsPlacedFacultatively() throws Exception {
        String username = customer();
        String gold = buy(username, "Gold");
        pay(gold);
        dispatch();
        // BWP 1.5m at 25% would cede 375,000; the treaty takes 300,000 on one life and refers 75,000.
        Map<String, Object> exposure = exposures(gold).get(0);
        assertThat(exposure.get("version")).isEqualTo(1);
        assertThat(exposure.get("treatyRef")).isEqualTo("TRT-00001");
        amount(exposure.get("cededSum"), "300000");
        amount(exposure.get("cededShare"), "0.2");
        assertThat(exposure.get("facultativeStatus")).isEqualTo("REFERRED");
        String fac = (String) exposure.get("facultativeRef");
        amount(field("/reinsurance/facultative", fac, "excessSum"), "75000");
        assertThat(cessions(gold)).hasSize(1);
        amount(cessions(gold).get(0).get("cededShare"), "0.2");

        // A second policy for the same life finds the capacity used up: all of its ceded sum is referred.
        String bronze = buy(username, "Bronze");
        pay(bronze);
        dispatch();
        Map<String, Object> second = exposures(bronze).get(0);
        amount(second.get("cededSum"), "0");
        amount(second.get("aggregateSum"), "1875000");
        assertThat(second.get("facultativeStatus")).isEqualTo("REFERRED");

        // Offered by one person; the reinsurer's acceptance is captured by another and cedes from then on.
        mockMvc.perform(json(post("/reinsurance/facultative/" + fac + "/offer"), "reinsurance",
                        "{\"reinsurer\":\"Swiss Re (demo)\",\"offeredSum\":\"90000\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/reinsurance/facultative/" + fac + "/offer"), "reinsurance",
                        "{\"reinsurer\":\"Swiss Re (demo)\",\"offeredSum\":\"75000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OFFERED"));
        String accept = "{\"outcome\":\"ACCEPTED\",\"acceptedSum\":\"75000\",\"acceptanceRef\":\"SR-FAC-881\","
                + "\"note\":\"Accepted on standard terms\"}";
        mockMvc.perform(json(post("/reinsurance/facultative/" + fac + "/decision"), "reinsurance", accept))
                .andExpect(status().isForbidden());
        String accepted = mockMvc.perform(json(post("/reinsurance/facultative/" + fac + "/decision"), "admin", accept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn().getResponse().getContentAsString();
        String facTreaty = JsonPath.read(accepted, "$.facTreatyRef");
        pay(gold);
        dispatch();
        List<Map<String, Object>> cessions = cessions(gold);
        assertThat(cessions).hasSize(3);
        assertThat(cessions.stream().filter(c -> facTreaty.equals(c.get("treatyRef"))).toList()).singleElement()
                .satisfies(c -> amount(c.get("cededShare"), "0.05"));
    }

    @Test
    void aBenefitChangeSupersedesTheExposureAndAReturnedPremiumAdjustsItsOwnCessions() throws Exception {
        String policy = buy(customer(), "Bronze");
        pay(policy);
        pay(policy);
        dispatch();
        List<Map<String, Object>> premiums = cessions(policy);
        assertThat(premiums).hasSize(2);
        String account = mockMvc.perform(get("/collections/policies/" + policy).with(signedIn("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        String firstReceipt = JsonPath.read(account, "$.receipts[1].receiptRef");
        String firstCession = premiums.stream().filter(c -> firstReceipt.equals(c.get("receiptRef")))
                .map(c -> (String) c.get("ref")).findFirst().orElseThrow();

        // The sum assured doubles by endorsement: a new exposure version supersedes the first.
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.amount = :sa where p.ref = :ref")
                .setParameter("sa", new BigDecimal("750000")).setParameter("ref", policy).executeUpdate());
        tx.executeWithoutResult(s -> outbox.publish(Events.POLICY_CHANGED, policy, Map.of("policyRef", policy,
                "endorsementRef", "END-T1", "endorsementType", "SUM_ASSURED", "effectiveDate",
                LocalDate.now().toString()), "admin"));
        dispatch();
        List<Map<String, Object>> versions = exposures(policy);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("version")).isEqualTo(2);
        assertThat(versions.get(0).get("status")).isEqualTo("ACTIVE");
        assertThat(versions.get(0).get("supersedesRef")).isEqualTo(versions.get(1).get("ref"));
        assertThat(versions.get(1).get("status")).isEqualTo("SUPERSEDED");
        amount(versions.get(0).get("cededSum"), "187500");

        // The first receipt bounces: one adjustment, linked to that receipt's cession, however often delivered.
        String request = mockMvc.perform(json(post("/collections/receipts/" + firstReceipt + "/reverse"), "finance",
                        "{\"kind\":\"RETURNED\",\"reason\":\"Debit returned: account closed\"}"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/collections/reversal-requests/" + JsonPath.read(request, "$.ref") + "/approve"),
                        "admin", "{}"))
                .andExpect(status().isOk());
        dispatch();
        dispatch();
        List<Map<String, Object>> adjustments = cessions(policy).stream().filter(c -> c.get("adjustsRef") != null).toList();
        assertThat(adjustments).singleElement().satisfies(a -> {
            assertThat(a.get("adjustsRef")).isEqualTo(firstCession);
            assertThat(a.get("receiptRef")).isEqualTo(firstReceipt);
            assertThat(a.get("reason")).isEqualTo("PaymentReturned");
            assertThat(new BigDecimal(String.valueOf(a.get("cededPremium"))).signum()).isNegative();
        });
    }

    @Test
    void aRecoveryIsExpectedAtApprovalCanBeDisputedAndIsWrittenOffWithApproval() throws Exception {
        String policy = buy(customer(), "Bronze");
        pay(policy);
        dispatch();
        String claim = "CLM-RX" + SEQ.incrementAndGet();
        claimEvent(Events.CLAIM_APPROVED, claim, policy, "40000.00");
        dispatch();
        Map<String, Object> recovery = recovery(claim);
        assertThat(recovery.get("status")).isEqualTo("EXPECTED");
        amount(recovery.get("recoveryAmount"), "10000");
        assertThat(recovery.get("claimPaidAmount")).isNull();
        // Paid at a different amount: the expectation follows the paid claim.
        claimEvent(Events.CLAIM_PAID, claim, policy, "36000.00");
        dispatch();
        recovery = recovery(claim);
        amount(recovery.get("recoveryAmount"), "9000");
        amount(recovery.get("originalAmount"), "10000");
        String ref = (String) recovery.get("ref");

        mockMvc.perform(json(post("/reinsurance/recoveries/" + ref + "/dispute"), "reinsurance",
                        "{\"reason\":\"Reinsurer queries the cause of death\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("DISPUTED"));
        mockMvc.perform(json(post("/reinsurance/recoveries/" + ref + "/receipt"), "reinsurance",
                        "{\"amount\":\"9000\",\"reference\":\"RE-1\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/reinsurance/recoveries/" + ref + "/resolve-dispute"), "reinsurance",
                        "{\"note\":\"Agreed at 8,000 after review\",\"agreedAmount\":\"8000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("NONE"))
                .andExpect(jsonPath("$.recoveryAmount").value(8000.00));
        mockMvc.perform(json(post("/reinsurance/recoveries/" + ref + "/write-off"), "reinsurance",
                        "{\"reason\":\"Reinsurer insolvent; nothing more will be paid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("WRITE_OFF_PENDING"));
        mockMvc.perform(post("/reinsurance/recoveries/" + ref + "/write-off/approve").with(signedIn("reinsurance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reinsurance/recoveries/" + ref + "/write-off/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WRITTEN_OFF"));
        dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=RecoveryWrittenOff")
                        .with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(journals, "$[?(@.sourceRef == '" + ref + "')].lines[*].accountCode"))
                .containsExactly("5300", "1200");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void claimEvent(String type, String claim, String policy, String amount) {
        tx.executeWithoutResult(s -> outbox.publish(type, claim, Map.of("claimRef", claim, "policyRef", policy,
                "customerName", "Reinsurance Client", "product", "Whole of Life", "amount", amount), "admin"));
    }

    private void dispatch() {
        dispatcher.dispatch();
        dispatcher.dispatch();
    }

    private String customer() throws Exception {
        int n = SEQ.incrementAndGet();
        String username = "riexp" + n;
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Exposure Client " + n + "\",\"email\":\"" + username + "@example.com\","
                                + "\"phone\":\"+267 73 000 0" + (10 + n) + "\",\"username\":\"" + username
                                + "\",\"password\":\"password1\",\"pep\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(body, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return username;
    }

    private String buy(String username, String tier) throws Exception {
        String shop = mockMvc.perform(post("/portfolio/shop").with(signedIn(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Whole of Life\",\"tier\":\"" + tier + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(shop, "$.requests[0].id");
    }

    private void pay(String policy) throws Exception {
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
    }

    private List<Map<String, Object>> exposures(String policy) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/reinsurance/exposures?policyRef=" + policy)
                        .with(signedIn("reinsurance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
    }

    private List<Map<String, Object>> cessions(String policy) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/reinsurance/cessions?policyRef=" + policy)
                        .with(signedIn("reinsurance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$");
    }

    private Map<String, Object> recovery(String claim) throws Exception {
        List<Map<String, Object>> found = JsonPath.read(mockMvc.perform(get("/reinsurance/recoveries")
                        .with(signedIn("reinsurance", "password")))
                .andReturn().getResponse().getContentAsString(), "$[?(@.claimRef == '" + claim + "')]");
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    private Object field(String path, String ref, String field) throws Exception {
        List<Object> values = JsonPath.read(mockMvc.perform(get(path).with(signedIn("reinsurance", "password")))
                .andReturn().getResponse().getContentAsString(), "$[?(@.ref == '" + ref + "')]." + field);
        return values.get(0);
    }

    private static void amount(Object actual, String expected) {
        assertThat(new BigDecimal(String.valueOf(actual))).isEqualByComparingTo(new BigDecimal(expected));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
