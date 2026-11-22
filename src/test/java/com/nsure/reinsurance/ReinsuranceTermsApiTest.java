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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NS-SH07-001/004/006: participants whose shares must add up to 100%, an excess-of-loss cover recovering above its
 * priority on the retained claim, a treaty amended as its next version, a claims bordereau through acknowledgement,
 * correction and settlement, and the reconciliation of ceded premium to the subledger with operational queues.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-reinsurance-terms;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class ReinsuranceTermsApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private TransactionTemplate tx;

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
    void sharesMustAddUpAndAnExcessOfLossRecoversAboveItsPriorityOnTheRetainedClaim() throws Exception {
        String from = LocalDate.now().minusDays(1).toString();
        String to = LocalDate.now().plusYears(1).toString();
        String uneven = "{\"name\":\"WoL cat XL\",\"reinsurer\":\"Lead Re\",\"treatyType\":\"EXCESS_OF_LOSS\","
                + "\"products\":[\"Whole of Life\"],\"xlPriority\":\"100000\",\"xlLimit\":\"200000\","
                + "\"effectiveFrom\":\"" + from + "\",\"effectiveTo\":\"" + to + "\",\"participants\":["
                + "{\"reinsurer\":\"Lead Re\",\"sharePercent\":\"60\",\"lead\":true},"
                + "{\"reinsurer\":\"Follow Re\",\"sharePercent\":\"30\"}]}";
        mockMvc.perform(json(post("/reinsurance/treaties"), "reinsurance", uneven))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exactly 100%")));
        String xl = mockMvc.perform(json(post("/reinsurance/treaties"), "reinsurance", uneven.replace("\"30\"", "\"40\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treatyType").value("EXCESS_OF_LOSS"))
                .andExpect(jsonPath("$.participants.length()").value(2))
                .andExpect(jsonPath("$.participants[0].lead").value(true))
                .andReturn().getResponse().getContentAsString();
        String xlRef = JsonPath.read(xl, "$.ref");
        // An excess of loss sits beside the Whole of Life quota share; it needs a second person like any treaty.
        mockMvc.perform(post("/reinsurance/treaties/" + xlRef + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk());

        String policy = paidPolicy("Whole of Life", "Bronze");
        String claim = "CLM-XL" + SEQ.incrementAndGet();
        tx.executeWithoutResult(s -> outbox.publish(Events.CLAIM_APPROVED, claim, Map.of("claimRef", claim,
                "policyRef", policy, "product", "Whole of Life", "amount", "300000.00"), "admin"));
        dispatch();
        List<Map<String, Object>> recoveries = JsonPath.read(mockMvc.perform(get("/reinsurance/recoveries")
                        .with(signedIn("reinsurance", "password"))).andReturn().getResponse().getContentAsString(),
                "$[?(@.claimRef == '" + claim + "')]");
        // 25% quota share: 75,000; the retained 225,000 is 125,000 above the priority, within the 200,000 limit.
        assertThat(recoveries).hasSize(2);
        amount(recoveries.stream().filter(r -> "TRT-00001".equals(r.get("treatyRef"))).findFirst().orElseThrow()
                .get("recoveryAmount"), "75000");
        amount(recoveries.stream().filter(r -> xlRef.equals(r.get("treatyRef"))).findFirst().orElseThrow()
                .get("recoveryAmount"), "125000");
    }

    @Test
    void anAmendedTreatyIsItsNextVersionAndAClaimsBordereauRunsItsLifecycle() throws Exception {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        String amended = mockMvc.perform(json(post("/reinsurance/treaties/TRT-00001/amend"), "reinsurance",
                        "{\"reason\":\"Renewal at a higher share\",\"cededPercent\":\"30\",\"commissionPercent\":\"22.5\","
                                + "\"effectiveFrom\":\"" + tomorrow + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.amendsRef").value("TRT-00001"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String v2 = JsonPath.read(amended, "$.ref");
        mockMvc.perform(post("/reinsurance/treaties/" + v2 + "/approve").with(signedIn("reinsurance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reinsurance/treaties/" + v2 + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String treaties = mockMvc.perform(get("/reinsurance/treaties").with(signedIn("reinsurance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(treaties, "$[?(@.ref == 'TRT-00001')].effectiveTo"))
                .containsExactly(LocalDate.now().toString());

        // Claims bordereau: the month's recoveries on the treaty, acknowledged, corrected and settled.
        String policy = paidPolicy("Whole of Life", "Bronze");
        String claim = "CLM-BX" + SEQ.incrementAndGet();
        tx.executeWithoutResult(s -> outbox.publish(Events.CLAIM_APPROVED, claim, Map.of("claimRef", claim,
                "policyRef", policy, "product", "Whole of Life", "amount", "40000.00"), "admin"));
        dispatch();
        String period = YearMonth.now().toString();
        String bordereau = mockMvc.perform(json(post("/reinsurance/bordereaux"), "reinsurance",
                        "{\"treatyRef\":\"TRT-00001\",\"period\":\"" + period + "\",\"kind\":\"CLAIMS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("CLAIMS"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(bordereau, "$.ref");
        assertThat(new BigDecimal(JsonPath.read(bordereau, "$.totalRecovery").toString())).isPositive();
        assertThat((List<String>) JsonPath.read(mockMvc.perform(get("/reinsurance/bordereaux/" + ref + "/lines")
                .with(signedIn("reinsurance", "password"))).andReturn().getResponse().getContentAsString(),
                "$[*].claimRef")).contains(claim);
        mockMvc.perform(json(post("/reinsurance/bordereaux/" + ref + "/acknowledge"), "reinsurance",
                        "{\"reference\":\"HR-ACK-77\"}"))
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mockMvc.perform(json(post("/reinsurance/bordereaux/" + ref + "/correction"), "reinsurance",
                        "{\"note\":\"Claim event dates missing\"}"))
                .andExpect(jsonPath("$.status").value("CORRECTION_REQUESTED"));
        mockMvc.perform(post("/reinsurance/bordereaux/" + ref + "/correct").with(signedIn("reinsurance", "password")))
                .andExpect(jsonPath("$.status").value("CORRECTED"))
                .andExpect(jsonPath("$.correctionCount").value(1));
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Integer.class, Events.BORDEREAU_SUBMITTED, ref)).isEqualTo(2);
        mockMvc.perform(json(post("/reinsurance/bordereaux/" + ref + "/settle"), "reinsurance",
                        "{\"reference\":\"SET-1\",\"amount\":\"10000\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/reinsurance/bordereaux/" + ref + "/acknowledge"), "reinsurance",
                        "{\"reference\":\"HR-ACK-78\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/reinsurance/bordereaux/" + ref + "/settle"), "reinsurance",
                        "{\"reference\":\"SET-1\",\"amount\":\"10000\"}"))
                .andExpect(jsonPath("$.status").value("SETTLED"));
    }

    @Test
    void cededPremiumReconcilesToTheSubledgerAndQueuesShowMissingAgreements() throws Exception {
        paidPolicy("Whole of Life", "Silver");
        String funeral = paidPolicy("Term Life", "Silver");
        dispatch();
        String recon = mockMvc.perform(get("/reinsurance/reconciliation?period=" + YearMonth.now())
                        .with(signedIn("reinsurance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        BigDecimal ceded = new BigDecimal(JsonPath.read(recon, "$.cededPremium").toString());
        assertThat(ceded).isPositive();
        assertThat(new BigDecimal(JsonPath.read(recon, "$.difference").toString())).isZero();
        assertThat((List<String>) JsonPath.read(recon, "$.missingAgreements[*].policyRef")).contains(funeral);
        BigDecimal gross = new BigDecimal(JsonPath.read(recon, "$.grossPremium").toString());
        BigDecimal retained = new BigDecimal(JsonPath.read(recon, "$.retainedPremium").toString());
        assertThat(gross.subtract(ceded)).isEqualByComparingTo(retained);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void dispatch() {
        dispatcher.dispatch();
        dispatcher.dispatch();
    }

    private String paidPolicy(String product, String tier) throws Exception {
        int n = SEQ.incrementAndGet();
        String username = "riterms" + n;
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Terms Client " + n + "\",\"email\":\"" + username + "@example.com\","
                                + "\"phone\":\"+267 76 000 0" + (10 + n) + "\",\"username\":\"" + username
                                + "\",\"password\":\"password1\",\"pep\":false}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(body, "$.id") + "/kyc")
                .with(signedIn("admin", "password"))).andExpect(status().isOk());
        String shop = mockMvc.perform(post("/portfolio/shop").with(signedIn(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"" + product + "\",\"tier\":\"" + tier + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(shop, "$.requests[0].id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        return policy;
    }

    private static void amount(Object actual, String expected) {
        assertThat(new BigDecimal(String.valueOf(actual))).isEqualByComparingTo(new BigDecimal(expected));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
