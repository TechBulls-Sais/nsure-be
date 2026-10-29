package com.nsure.distribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * SH06: appointments, assignments and the commission ledger. Commission accrues once per premium event at the rule
 * in force, is clawed back once on cancellation (AT-SH06-01), a suspended, lapsed or expired appointment takes no new
 * business (AT-SH06-02), a transfer moves future commission (AT-SH06-03), and statements net and pay once.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-distribution;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DistributionApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

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
    private CommissionEventHandler handler;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void commissionAccruesOncePerPremiumAndIsClawedBackOnceOnCancellation() throws Exception {
        String agent = appoint("AGENT");
        String policy = pendingPolicy("Term Life", "Silver");
        mockMvc.perform(json(post("/distribution/assignments"), "distribution",
                        "{\"policyRef\":\"" + policy + "\",\"intermediaryRef\":\"" + agent + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.product").value("Term Life"))
                .andExpect(jsonPath("$.effectiveFrom").value(LocalDate.now().toString()));
        BigDecimal premium = premium(policy);

        // First premium: the seeded Term Life agent rule pays 20% initial.
        pay(policy);
        dispatcher.dispatch();
        List<Map<String, Object>> lines = commissions(policy);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("kind")).isEqualTo("ACCRUAL");
        assertThat(lines.get(0).get("intermediaryRef")).isEqualTo(agent);
        assertAmount(lines.get(0).get("ratePercent"), new BigDecimal("20"));
        assertAmount(lines.get(0).get("amount"), percentOf(premium, "20"));

        // Renewal premium: 5%. Dispatching again changes nothing.
        pay(policy);
        dispatcher.dispatch();
        dispatcher.dispatch();
        lines = commissions(policy);
        assertThat(lines).hasSize(2);
        assertAmount(lines.get(0).get("amount"), percentOf(premium, "5"));

        // Redelivering the same premium event (a replay) accrues nothing more.
        OutboxEvent first = tx.execute(s -> em.createQuery("select e from OutboxEvent e where e.eventType = :type"
                        + " and e.aggregateRef = :ref order by e.id asc", OutboxEvent.class)
                .setParameter("type", Events.PREMIUM_RECEIVED)
                .setParameter("ref", policy)
                .setMaxResults(1)
                .getSingleResult());
        DomainEvent replay = new DomainEvent(first.getEventId(), first.getEventType(), first.getAggregateRef(),
                first.getCorrelationId(), first.getOccurredAt(), mapper.readTree(first.getPayload()));
        tx.executeWithoutResult(s -> handler.handle(replay));
        tx.executeWithoutResult(s -> handler.handle(replay));
        assertThat(commissions(policy)).hasSize(2);
        assertThat(eventCount(Events.COMMISSION_ACCRUED, policy)).isEqualTo(2);

        // Cancellation claws back each accrual once, however often it is delivered or repeated.
        cancelled(policy);
        dispatcher.dispatch();
        dispatcher.dispatch();
        cancelled(policy);
        dispatcher.dispatch();
        lines = commissions(policy);
        assertThat(lines).hasSize(4);
        List<Map<String, Object>> clawbacks = lines.stream().filter(l -> "CLAWBACK".equals(l.get("kind"))).toList();
        assertThat(clawbacks).hasSize(2);
        assertThat(clawbacks.stream().map(l -> l.get("reversesRef")).distinct().count()).isEqualTo(2);
        BigDecimal net = lines.stream().map(l -> new BigDecimal(String.valueOf(l.get("amount"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(net).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(eventCount(Events.COMMISSION_REVERSED, policy)).isEqualTo(2);
    }

    @Test
    void aSuspendedLapsedOrExpiredAppointmentTakesNoNewPolicies() throws Exception {
        String agent = appoint("AGENT");
        String kept = pendingPolicy("Term Life", "Silver");
        String later = pendingPolicy("Term Life", "Silver");
        assign(kept, agent).andExpect(status().isOk());

        mockMvc.perform(json(put("/distribution/intermediaries/" + agent), "distribution", "{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/distribution/intermediaries/" + agent), "distribution",
                        "{\"status\":\"SUSPENDED\",\"reason\":\"Complaint under investigation\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.statusReason").value("Complaint under investigation"))
                .andExpect(jsonPath("$.canTakeBusiness").value(false))
                .andExpect(jsonPath("$.openAssignments").value(1));

        // Refused, and what was already assigned stays as it was.
        assign(later, agent).andExpect(status().isBadRequest());
        mockMvc.perform(get("/distribution/assignments?intermediaryRef=" + agent).with(httpBasic("distribution", "password")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].policyRef").value(kept))
                .andExpect(jsonPath("$[0].open").value(true));
        mockMvc.perform(get("/distribution/assignments?policyRef=" + later).with(httpBasic("distribution", "password")))
                .andExpect(jsonPath("$.length()").value(0));
        // The seeded suspended agent too.
        assign(later, "INT-00003").andExpect(status().isBadRequest());

        // Lapsed needs a reason as well; reinstated, the agent takes business again.
        mockMvc.perform(json(put("/distribution/intermediaries/" + agent), "distribution", "{\"status\":\"LAPSED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/distribution/intermediaries/" + agent), "distribution", "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canTakeBusiness").value(true));
        assign(later, agent).andExpect(status().isOk());

        // An expired appointment takes nothing new.
        String expired = appoint("BROKER");
        mockMvc.perform(json(put("/distribution/intermediaries/" + expired), "distribution",
                        "{\"appointmentExpiry\":\"" + LocalDate.now().minusDays(1) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.canTakeBusiness").value(false));
        assign(pendingPolicy("Term Life", "Silver"), expired).andExpect(status().isBadRequest());

        assign("POL-99999", agent).andExpect(status().isNotFound());
        assign(kept, "INT-99999").andExpect(status().isNotFound());
        mockMvc.perform(json(post("/distribution/assignments"), "reinsurance",
                        "{\"policyRef\":\"" + kept + "\",\"intermediaryRef\":\"" + agent + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aTransferEndsTheOldAssignmentAndLaterPremiumsAccrueToTheNewIntermediary() throws Exception {
        String agent = appoint("AGENT");
        String broker = appoint("BROKER");
        String policy = pendingPolicy("Term Life", "Silver");
        BigDecimal premium = premium(policy);
        assign(policy, agent).andExpect(status().isOk());
        pay(policy);
        dispatcher.dispatch();

        assign(policy, broker).andExpect(status().isOk()).andExpect(jsonPath("$.open").value(true));
        assign(policy, broker).andExpect(status().isBadRequest());
        mockMvc.perform(get("/distribution/assignments?policyRef=" + policy).with(httpBasic("distribution", "password")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].intermediaryRef").value(broker))
                .andExpect(jsonPath("$[0].open").value(true))
                .andExpect(jsonPath("$[1].intermediaryRef").value(agent))
                .andExpect(jsonPath("$[1].open").value(false))
                .andExpect(jsonPath("$[1].endedOn").value(LocalDate.now().toString()));

        pay(policy);
        dispatcher.dispatch();
        List<Map<String, Object>> lines = commissions(policy);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1).get("intermediaryRef")).isEqualTo(agent);
        assertAmount(lines.get(1).get("amount"), percentOf(premium, "20"));
        // The broker gets the renewal at the seeded Term Life broker renewal rate, 7.5%.
        assertThat(lines.get(0).get("intermediaryRef")).isEqualTo(broker);
        assertAmount(lines.get(0).get("amount"), percentOf(premium, "7.5"));
    }

    @Test
    void statementsNetAccrualsAndClawbacksAndArePaidOnce() throws Exception {
        String agent = appoint("AGENT");
        String policy = pendingPolicy("Term Life", "Silver");
        assign(policy, agent).andExpect(status().isOk());
        pay(policy);
        dispatcher.dispatch();
        BigDecimal accrued = percentOf(premium(policy), "20");

        String issue = "{\"intermediaryRef\":\"" + agent + "\"}";
        mockMvc.perform(json(post("/distribution/statements"), "distribution", issue))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(json(post("/distribution/statements"), "finance", issue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.lineCount").value(1))
                .andExpect(jsonPath("$.lines[0].status").value("STATEMENTED"))
                .andReturn().getResponse().getContentAsString();
        String statement = JsonPath.read(body, "$.ref");
        assertAmount(JsonPath.read(body, "$.netAmount"), accrued);
        // Nothing accrued is left for a second statement.
        mockMvc.perform(json(post("/distribution/statements"), "finance", issue))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/distribution/statements/" + statement + "/pay").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidBy").value("finance"))
                .andExpect(jsonPath("$.lines[0].status").value("PAID"));
        mockMvc.perform(post("/distribution/statements/" + statement + "/pay").with(httpBasic("finance", "password")))
                .andExpect(status().isConflict());
        Long paidEvents = tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = :type"
                        + " and e.aggregateRef = :ref", Long.class)
                .setParameter("type", Events.COMMISSION_PAID)
                .setParameter("ref", statement)
                .getSingleResult());
        assertThat(paidEvents).isEqualTo(1L);

        // Cancelled after payment: the clawback goes on the next statement, which nets negative and is not paid.
        cancelled(policy);
        dispatcher.dispatch();
        String negative = mockMvc.perform(json(post("/distribution/statements"), "finance", issue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].kind").value("CLAWBACK"))
                .andReturn().getResponse().getContentAsString();
        assertAmount(JsonPath.read(negative, "$.netAmount"), accrued.negate());
        mockMvc.perform(post("/distribution/statements/" + JsonPath.read(negative, "$.ref") + "/pay")
                        .with(httpBasic("finance", "password")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/distribution/statements").with(httpBasic("distribution", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ref == '" + statement + "')].status").value("PAID"));
    }

    @Test
    void rulesAndAppointmentsAreValidatedAndRulesAreNeverOverwritten() throws Exception {
        String today = LocalDate.now().toString();
        String rule = "{\"product\":\"Individual Credit\",\"intermediaryType\":\"BROKER\",\"initialRatePercent\":12.5,"
                + "\"renewalRatePercent\":6,\"effectiveFrom\":\"" + today + "\"}";
        mockMvc.perform(json(post("/distribution/commission-rules"), "distribution", rule))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.initialRatePercent").value(12.5));
        mockMvc.perform(json(post("/distribution/commission-rules"), "distribution", rule))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/distribution/commission-rules"), "distribution", rule.replace("12.5", "120")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/distribution/commission-rules"), "distribution",
                        rule.replace("Individual Credit", "Space Cover")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/distribution/commission-rules").with(httpBasic("distribution", "password")))
                .andExpect(jsonPath("$[?(@.product == 'Individual Credit' && @.intermediaryType == 'BROKER'"
                        + " && @.current == true)].effectiveFrom").value(today))
                .andExpect(jsonPath("$[?(@.product == 'Individual Credit' && @.intermediaryType == 'BROKER'"
                        + " && @.current == false)].effectiveFrom").value("2026-01-01"));

        mockMvc.perform(json(post("/distribution/intermediaries"), "distribution",
                        "{\"name\":\"Kopano Agency\",\"type\":\"AGENCY\",\"licenceNumber\":\"X-1\",\"appointmentExpiry\":\""
                                + LocalDate.now().plusYears(1) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/distribution/intermediaries"), "distribution",
                        "{\"name\":\"Kopano Mmusi\",\"type\":\"AGENT\",\"licenceNumber\":\"NBFIRA-AG-2291\",\"appointmentExpiry\":\""
                                + LocalDate.now().plusYears(1) + "\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/distribution/intermediaries"), "distribution",
                        "{\"name\":\"Kopano Mmusi\",\"type\":\"AGENT\",\"licenceNumber\":\"X-2\",\"appointmentExpiry\":\""
                                + LocalDate.now().minusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/distribution/intermediaries"), "distribution",
                        "{\"name\":\"Kopano Mmusi\",\"type\":\"AGENT\",\"licenceNumber\":\"X-3\",\"parentRef\":\"INT-00002\","
                                + "\"appointmentExpiry\":\"" + LocalDate.now().plusYears(1) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(org.hamcrest.Matchers.startsWith("INT-")))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.parentName").value("Mokolodi Insurance Brokers"));

        mockMvc.perform(get("/distribution/intermediaries").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ref == 'INT-00003')].status").value("SUSPENDED"));
        mockMvc.perform(get("/distribution/intermediaries").with(httpBasic("reinsurance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/distribution/intermediaries"), "finance", "{}"))
                .andExpect(status().isForbidden());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private String appoint(String type) throws Exception {
        int n = SEQ.incrementAndGet();
        String body = mockMvc.perform(json(post("/distribution/intermediaries"), "distribution",
                        "{\"name\":\"Test " + type + " " + n + "\",\"type\":\"" + type + "\",\"licenceNumber\":\"TEST-" + n
                                + "\",\"appointmentExpiry\":\"" + LocalDate.now().plusYears(2) + "\","
                                + "\"email\":\"intermediary" + n + "@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value(type))
                .andExpect(jsonPath("$.canTakeBusiness").value(true))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.ref");
    }

    private org.springframework.test.web.servlet.ResultActions assign(String policy, String intermediary)
            throws Exception {
        return mockMvc.perform(json(post("/distribution/assignments"), "distribution",
                "{\"policyRef\":\"" + policy + "\",\"intermediaryRef\":\"" + intermediary + "\"}"));
    }

    /** A new KYC-cleared customer's purchase, awaiting its first premium. */
    private String pendingPolicy(String product, String tier) throws Exception {
        int n = SEQ.incrementAndGet();
        String username = "distcust" + n;
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"Distribution Client " + n + "\",\"email\":\"" + username + "@example.com\","
                                + "\"phone\":\"+267 71 000 0" + (10 + n) + "\",\"username\":\"" + username
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

    /** Cancellation of a paid policy has no API yet (only unpaid ones are cancelled), so the event is raised here. */
    private void cancelled(String policy) {
        tx.executeWithoutResult(s -> outbox.publish(Events.POLICY_CANCELLED, policy, Map.of("policyRef", policy,
                "customerName", "Distribution Client", "product", "Term Life", "reason", "Customer cancelled"), "admin"));
    }

    private BigDecimal premium(String policy) {
        return tx.execute(s -> em.createQuery("select p.premium from InsurancePolicy p where p.ref = :ref",
                        BigDecimal.class)
                .setParameter("ref", policy)
                .getSingleResult());
    }

    private List<Map<String, Object>> commissions(String policy) throws Exception {
        String body = mockMvc.perform(get("/distribution/commissions?policyRef=" + policy)
                        .with(httpBasic("distribution", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$");
    }

    private long eventCount(String type, String policy) {
        return tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = :type"
                        + " and e.payload like :policy", Long.class)
                .setParameter("type", type)
                .setParameter("policy", "%\"policyRef\":\"" + policy + "\"%")
                .getSingleResult());
    }

    private static BigDecimal percentOf(BigDecimal amount, String percent) {
        return amount.multiply(new BigDecimal(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static void assertAmount(Object actual, BigDecimal expected) {
        assertThat(new BigDecimal(String.valueOf(actual))).isEqualByComparingTo(expected);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
