package com.nsure.reinsurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.DomainEvent;
import com.nsure.integration.Events;
import com.nsure.integration.Outbox;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.integration.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SH07: only an approved treaty cedes (AT-SH07-01), a treaty is approved by someone other than its drafter and never
 * overlaps another approved treaty, surplus cedes only above the retention, a paid claim on a ceded policy recognises
 * one recovery apart from the customer payout (AT-SH07-03), and a bordereau reports a month's cessions once.
 *
 * <p>The seeded approved quota share TRT-00001 (25%) covers Whole of Life; tests here never approve another treaty
 * for Whole of Life or Term Life that is in force today.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-reinsurance;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ReinsuranceApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final BigDecimal QUARTER = new BigDecimal("0.25");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private CessionEventHandler handler;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void anApprovedQuotaShareCedesItsShareOnceAndADraftTreatyCedesNothing() throws Exception {
        String policy = pendingPolicy("Whole of Life", "Bronze");
        pay(policy);
        dispatcher.dispatch();
        dispatcher.dispatch();
        List<Map<String, Object>> cessions = cessions(policy);
        assertThat(cessions).hasSize(1);
        Map<String, Object> cession = cessions.get(0);
        assertThat(cession.get("treatyRef")).isEqualTo("TRT-00001");
        assertAmount(cession.get("cededShare"), QUARTER);
        assertAmount(cession.get("cededPremium"), times(premium(policy), QUARTER));
        assertAmount(cession.get("cededSum"), times(sumAssured(policy), QUARTER));
        assertThat(cession.get("bordereauRef")).isNull();

        // A redelivered premium event cedes nothing more.
        OutboxEvent first = tx.execute(s -> em.createQuery("select e from OutboxEvent e where e.eventType = :type"
                        + " and e.aggregateRef = :ref order by e.id asc", OutboxEvent.class)
                .setParameter("type", Events.PREMIUM_RECEIVED)
                .setParameter("ref", policy)
                .setMaxResults(1)
                .getSingleResult());
        DomainEvent replay = new DomainEvent(first.getEventId(), first.getEventType(), first.getAggregateRef(),
                first.getCorrelationId(), first.getOccurredAt(), mapper.readTree(first.getPayload()));
        tx.executeWithoutResult(s -> handler.handle(replay));
        assertThat(cessions(policy)).hasSize(1);
        assertThat(eventCount(Events.RISK_CEDED, policy)).isEqualTo(1);

        // A draft quota share for Term Life is not applied (AT-SH07-01).
        mockMvc.perform(json(post("/reinsurance/treaties"), "reinsurance", treaty("Term Life QS draft", "QUOTA_SHARE",
                        "[\"Term Life\"]", "\"cededPercent\":50", LocalDate.now().minusDays(1), LocalDate.now().plusYears(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.ceding").value(false))
                .andExpect(jsonPath("$.ref").value(org.hamcrest.Matchers.startsWith("TRT-")));
        String uncovered = pendingPolicy("Term Life", "Silver");
        pay(uncovered);
        dispatcher.dispatch();
        assertThat(cessions(uncovered)).isEmpty();
        assertThat(eventCount(Events.RISK_CEDED, uncovered)).isZero();
    }

    @Test
    void theDrafterCannotApproveOverlapsAreRefusedAndSurplusCedesOnlyAboveTheRetention() throws Exception {
        String small = pendingPolicy("Care Cash", "Bronze");
        String large = pendingPolicy("Care Cash", "Silver");
        BigDecimal retention = sumAssured(small);
        BigDecimal largeSum = sumAssured(large);
        assertThat(largeSum).isGreaterThan(retention);

        String body = mockMvc.perform(json(post("/reinsurance/treaties"), "reinsurance", treaty("Credit surplus",
                        "SURPLUS", "\"Care Cash\"", "\"retention\":" + retention.toPlainString(),
                        LocalDate.now().minusDays(1), LocalDate.now().plusYears(1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[0]").value("Care Cash"))
                .andExpect(jsonPath("$.createdBy").value("reinsurance"))
                .andReturn().getResponse().getContentAsString();
        String surplus = JsonPath.read(body, "$.ref");

        // Four eyes: the drafter cannot approve. Super user and Super admin can approve a treaty they drafted.
        mockMvc.perform(post("/reinsurance/treaties/" + surplus + "/approve").with(httpBasic("reinsurance", "password")))
                .andExpect(status().isForbidden());
        String own = JsonPath.read(mockMvc.perform(json(post("/reinsurance/treaties"), "admin", treaty(
                        "Family Funeral quota share", "QUOTA_SHARE", "\"Family Funeral\"", "\"cededPercent\":10",
                        LocalDate.of(2031, 1, 1), LocalDate.of(2031, 12, 31))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdBy").value("admin"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/reinsurance/treaties/" + own + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("admin"));
        mockMvc.perform(post("/reinsurance/treaties/" + surplus + "/approve").with(httpBasic("finance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reinsurance/treaties/" + surplus + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("admin"))
                .andExpect(jsonPath("$.ceding").value(true));
        mockMvc.perform(post("/reinsurance/treaties/" + surplus + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isConflict());

        // At the retention nothing is ceded; above it, the surplus share.
        pay(small);
        pay(large);
        dispatcher.dispatch();
        assertThat(cessions(small)).isEmpty();
        List<Map<String, Object>> cessions = cessions(large);
        assertThat(cessions).hasSize(1);
        BigDecimal share = largeSum.subtract(retention).divide(largeSum, 6, RoundingMode.HALF_UP);
        assertThat(cessions.get(0).get("treatyRef")).isEqualTo(surplus);
        assertAmount(cessions.get(0).get("cededShare"), share);
        assertAmount(cessions.get(0).get("cededSum"), times(largeSum, share));
        assertAmount(cessions.get(0).get("cededPremium"), times(premium(large), share));

        // Another treaty covering Whole of Life on overlapping dates cannot be approved; later dates can.
        String overlapping = JsonPath.read(mockMvc.perform(json(post("/reinsurance/treaties"), "reinsurance",
                        treaty("WoL second QS", "QUOTA_SHARE", "\"Whole of Life, Family Funeral\"", "\"cededPercent\":10",
                                LocalDate.of(2030, 6, 1), LocalDate.of(2031, 5, 31))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/reinsurance/treaties/" + overlapping + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isConflict());
        String later = JsonPath.read(mockMvc.perform(json(post("/reinsurance/treaties"), "reinsurance",
                        treaty("WoL 2031 QS", "QUOTA_SHARE", "[\"Whole of Life\"]", "\"cededPercent\":30",
                                LocalDate.of(2031, 1, 1), LocalDate.of(2031, 12, 31))))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(post("/reinsurance/treaties/" + later + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ceding").value(false));

        mockMvc.perform(get("/reinsurance/treaties").with(httpBasic("reinsurance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ref == '" + overlapping + "')].status").value("DRAFT"))
                .andExpect(jsonPath("$[?(@.ref == '" + surplus + "')].cessionCount").value(1));
    }

    @Test
    void treatiesAreValidatedAndOnlyReinsuranceStaffManageThem() throws Exception {
        LocalDate from = LocalDate.now();
        LocalDate to = from.plusYears(1);
        String[] invalid = {
                treaty("Zero", "QUOTA_SHARE", "\"Care Cash\"", "\"cededPercent\":0", from, to),
                treaty("Too much", "QUOTA_SHARE", "\"Care Cash\"", "\"cededPercent\":150", from, to),
                treaty("No retention", "SURPLUS", "\"Care Cash\"", "\"retention\":0", from, to),
                treaty("Backwards", "QUOTA_SHARE", "\"Care Cash\"", "\"cededPercent\":20", to, from),
                treaty("No products", "QUOTA_SHARE", "[]", "\"cededPercent\":20", from, to),
                treaty("Unknown product", "QUOTA_SHARE", "\"Space Cover\"", "\"cededPercent\":20", from, to),
                treaty("Excess of loss", "XOL", "\"Care Cash\"", "\"cededPercent\":20", from, to),
        };
        for (String body : invalid) {
            mockMvc.perform(json(post("/reinsurance/treaties"), "reinsurance", body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(json(post("/reinsurance/treaties"), "finance",
                        treaty("Finance draft", "QUOTA_SHARE", "\"Care Cash\"", "\"cededPercent\":20", from, to)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/reinsurance/treaties").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ref == 'TRT-00002')].status").value("DRAFT"));
        mockMvc.perform(get("/reinsurance/treaties").with(httpBasic("distribution", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/reinsurance/treaties/TRT-99999/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void aPaidClaimOnACededPolicyRecognisesOneRecoveryApartFromThePayout() throws Exception {
        String ceded = pendingPolicy("Whole of Life", "Bronze");
        pay(ceded);
        dispatcher.dispatch();
        String claim = "CLM-9" + SEQ.incrementAndGet();
        claimPaid(claim, ceded, "100000.00");
        dispatcher.dispatch();
        dispatcher.dispatch();

        List<Map<String, Object>> recoveries = recoveries(claim);
        assertThat(recoveries).hasSize(1);
        assertThat(recoveries.get(0).get("treatyRef")).isEqualTo("TRT-00001");
        assertThat(recoveries.get(0).get("policyRef")).isEqualTo(ceded);
        assertThat(recoveries.get(0).get("status")).isEqualTo("EXPECTED");
        assertAmount(recoveries.get(0).get("claimAmount"), new BigDecimal("100000"));
        assertAmount(recoveries.get(0).get("recoveryAmount"), new BigDecimal("25000"));
        assertThat(eventCount(Events.RECOVERY_RECOGNISED, ceded)).isEqualTo(1);

        // No cession on the policy, no recovery.
        String retained = pendingPolicy("Term Life", "Silver");
        pay(retained);
        String other = "CLM-9" + SEQ.incrementAndGet();
        claimPaid(other, retained, "5000.00");
        dispatcher.dispatch();
        assertThat(recoveries(other)).isEmpty();
    }

    @Test
    void aBordereauReportsTheMonthsUnreportedCessionsOnceWithControlTotals() throws Exception {
        String policy = pendingPolicy("Whole of Life", "Bronze");
        pay(policy);
        dispatcher.dispatch();

        String all = mockMvc.perform(get("/reinsurance/cessions?treatyRef=TRT-00001").with(httpBasic("reinsurance", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> unreported = ((List<Map<String, Object>>) JsonPath.read(all, "$")).stream()
                .filter(c -> c.get("bordereauRef") == null)
                .toList();
        assertThat(unreported).isNotEmpty();
        BigDecimal premiumTotal = sum(unreported, "cededPremium");
        BigDecimal sumTotal = sum(unreported, "cededSum");

        String period = YearMonth.now().toString();
        String request = "{\"treatyRef\":\"TRT-00001\",\"period\":\"" + period + "\"}";
        mockMvc.perform(json(post("/reinsurance/bordereaux"), "finance", request))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(json(post("/reinsurance/bordereaux"), "reinsurance", request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value(period))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.recordCount").value(unreported.size()))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.ref");
        assertAmount(JsonPath.read(body, "$.totalCededPremium"), premiumTotal);
        assertAmount(JsonPath.read(body, "$.totalCededSum"), sumTotal);
        assertThat(cessions(policy).get(0).get("bordereauRef")).isEqualTo(ref);

        // A later cession in the month is not added to the submitted bordereau; asking again returns it unchanged.
        pay(policy);
        dispatcher.dispatch();
        mockMvc.perform(json(post("/reinsurance/bordereaux"), "reinsurance", request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(ref))
                .andExpect(jsonPath("$.recordCount").value(unreported.size()));
        List<Map<String, Object>> cessions = cessions(policy);
        assertThat(cessions).hasSize(2);
        assertThat(cessions.get(0).get("bordereauRef")).isNull();
        mockMvc.perform(get("/reinsurance/bordereaux").with(httpBasic("reinsurance", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "')].treatyRef").value("TRT-00001"));

        mockMvc.perform(json(post("/reinsurance/bordereaux"), "reinsurance",
                        "{\"treatyRef\":\"TRT-00001\",\"period\":\"2026-13\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/reinsurance/bordereaux"), "reinsurance",
                        "{\"treatyRef\":\"TRT-00001\",\"period\":\"" + YearMonth.now().plusMonths(1) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/reinsurance/bordereaux"), "reinsurance",
                        "{\"treatyRef\":\"TRT-00002\",\"period\":\"" + period + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/reinsurance/bordereaux"), "reinsurance",
                        "{\"treatyRef\":\"TRT-99999\",\"period\":\"" + period + "\"}"))
                .andExpect(status().isNotFound());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static String treaty(String name, String type, String products, String terms, LocalDate from,
            LocalDate to) {
        return "{\"name\":\"" + name + "\",\"reinsurer\":\"Test Re\",\"treatyType\":\"" + type + "\",\"products\":"
                + products + "," + terms + ",\"effectiveFrom\":\"" + from + "\",\"effectiveTo\":\"" + to + "\"}";
    }

    /** A new KYC-cleared customer's purchase, awaiting its first premium. */
    private String pendingPolicy(String product, String tier) throws Exception {
        int n = SEQ.incrementAndGet();
        String username = "ricust" + n;
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Reinsurance Client " + n + "\",\"email\":\"" + username + "@example.com\","
                                + "\"phone\":\"+267 72 000 0" + (10 + n) + "\",\"username\":\"" + username
                                + "\",\"password\":\"password1\",\"pep\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String customer = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(post("/portfolio/shop").with(httpBasic(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"" + product + "\",\"tier\":\"" + tier + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(shop, "$.requests[0].id");
    }

    private void pay(String policy) throws Exception {
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
    }

    /** The claim workflow's payout raises ClaimPaid; raised directly here to keep the test on reinsurance. */
    private void claimPaid(String claim, String policy, String amount) {
        tx.executeWithoutResult(s -> outbox.publish(Events.CLAIM_PAID, claim, Map.of("claimRef", claim,
                "policyRef", policy, "customerName", "Reinsurance Client", "product", "Whole of Life",
                "amount", amount, "payoutRef", "PAY-" + claim), "admin"));
    }

    private BigDecimal premium(String policy) {
        return tx.execute(s -> em.createQuery("select p.premium from InsurancePolicy p where p.ref = :ref",
                        BigDecimal.class)
                .setParameter("ref", policy)
                .getSingleResult());
    }

    private BigDecimal sumAssured(String policy) {
        Integer amount = tx.execute(s -> em.createQuery("select p.amount from InsurancePolicy p where p.ref = :ref",
                        Integer.class)
                .setParameter("ref", policy)
                .getSingleResult());
        return BigDecimal.valueOf(amount);
    }

    private List<Map<String, Object>> cessions(String policy) throws Exception {
        String body = mockMvc.perform(get("/reinsurance/cessions?policyRef=" + policy)
                        .with(httpBasic("reinsurance", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$");
    }

    private List<Map<String, Object>> recoveries(String claim) throws Exception {
        String body = mockMvc.perform(get("/reinsurance/recoveries").with(httpBasic("reinsurance", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return ((List<Map<String, Object>>) JsonPath.read(body, "$")).stream()
                .filter(r -> claim.equals(r.get("claimRef")))
                .toList();
    }

    private long eventCount(String type, String policy) {
        return tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = :type"
                        + " and e.payload like :policy", Long.class)
                .setParameter("type", type)
                .setParameter("policy", "%\"policyRef\":\"" + policy + "\"%")
                .getSingleResult());
    }

    private static BigDecimal times(BigDecimal amount, BigDecimal share) {
        return amount.multiply(share).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sum(List<Map<String, Object>> rows, String field) {
        return rows.stream().map(r -> new BigDecimal(String.valueOf(r.get(field)))).reduce(BigDecimal.ZERO,
                BigDecimal::add);
    }

    private static void assertAmount(Object actual, BigDecimal expected) {
        assertThat(new BigDecimal(String.valueOf(actual))).isEqualByComparingTo(expected);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
