package com.nsure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.DomainEvent;
import com.nsure.integration.DomainEventHandler;
import com.nsure.integration.EventDelivery;
import com.nsure.integration.Outbox;
import com.nsure.integration.OutboxDispatcher;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cross-cutting foundations of the Detailed Product Specification: event envelope versions and per-aggregate order
 * (NS-API-003/004), tamper-evident audit evidence (NS-SH11-005, AT-SH11-03) and stale-version refusal (NS-TXN-001).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-foundations;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(PlatformFoundationsApiTest.SupersedingConsumer.class)
class PlatformFoundationsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private OutboxDispatcher outbox;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void eventsCarryTheirVersionsAndReachEachHandlerInAggregateOrder() throws Exception {
        String customer = customer("Tebogo Order", "+267 71 400 001");
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin",
                                "{\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");
        // The first premium raises the policy's events, under the caller's correlation id.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}")
                        .header(CorrelationId.HEADER, "corr-order-0001"))
                .andExpect(status().isOk());
        List<Object[]> events = tx.execute(s -> em.createQuery("select e.aggregateVersion, e.schemaVersion,"
                        + " e.correlationId, e.eventType from OutboxEvent e where e.aggregateRef = :ref"
                        + " order by e.aggregateVersion", Object[].class)
                .setParameter("ref", policy).getResultList());
        assertThat(events).hasSizeGreaterThanOrEqualTo(2);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i)[0]).isEqualTo((long) (i + 1));
            assertThat(events.get(i)[1]).isEqualTo(1);
            assertThat(events.get(i)[2]).isEqualTo("corr-order-0001");
        }
        String firstType = (String) events.get(0)[3];

        // Version 1 fails for every handler before anything is dispatched; version 2 must wait behind it.
        tx.executeWithoutResult(s -> em.createQuery("update EventDelivery d set d.status = 'FAILED' where d.event.id in"
                        + " (select e.id from OutboxEvent e where e.aggregateRef = :ref and e.aggregateVersion = 1)")
                .setParameter("ref", policy).executeUpdate());
        outbox.dispatch();

        List<EventDelivery> second = deliveries(policy, 2);
        List<String> firstHandlers = tx.execute(s -> em.createQuery("select d.handler from EventDelivery d"
                + " where d.event.aggregateRef = :ref and d.event.aggregateVersion = 1", String.class)
                .setParameter("ref", policy).getResultList());
        assertThat(second).isNotEmpty();
        for (EventDelivery d : second) {
            if (firstHandlers.contains(d.getHandler())) {
                assertThat(d.getStatus()).isEqualTo(EventDelivery.PENDING);
                assertThat(d.getLastError()).startsWith("Waiting for " + firstType + " (version 1 of " + policy);
            } else {
                assertThat(d.getStatus()).isEqualTo(EventDelivery.DELIVERED);
            }
        }

        // NS-API-004: a hold behind a failed event is a sequence gap that will not close by itself: integration staff
        // get one reconciliation alert per handler and aggregate, and the hold says what to do.
        assertThat(second.stream().filter(d -> firstHandlers.contains(d.getHandler())))
                .allSatisfy(d -> assertThat(d.getLastError()).contains("sequence gap"));
        Long gapAlerts = jdbc.queryForObject("select count(*) from staff_alert where alert_type = 'EVENT_SEQUENCE_GAP'"
                + " and dedup_key like ?", Long.class, "%:" + policy);
        assertThat(gapAlerts).isPositive();
        outbox.dispatch();
        assertThat(jdbc.queryForObject("select count(*) from staff_alert where alert_type = 'EVENT_SEQUENCE_GAP'"
                + " and dedup_key like ?", Long.class, "%:" + policy)).isEqualTo(gapAlerts);

        // Once the earlier event is through, the held one follows.
        tx.executeWithoutResult(s -> em.createQuery("update EventDelivery d set d.status = 'PENDING', d.attempts = 0,"
                        + " d.nextAttemptAt = :now where d.event.id in (select e.id from OutboxEvent e"
                        + " where e.aggregateRef = :ref)")
                .setParameter("now", LocalDateTime.now().minusSeconds(1)).setParameter("ref", policy).executeUpdate());
        outbox.dispatch();
        assertThat(deliveries(policy, 2)).allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(EventDelivery.DELIVERED));
    }

    /** A consumer that judges some older events irrelevant (here: those marked stale). */
    @TestConfiguration
    static class SupersedingConsumer {
        static final java.util.List<String> HANDLED = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Bean
        DomainEventHandler supersedingHandler() {
            return new DomainEventHandler() {
                @Override
                public String name() {
                    return "test.superseding";
                }

                @Override
                public java.util.Set<String> eventTypes() {
                    return java.util.Set.of("TestReading");
                }

                @Override
                public String ignoreReason(DomainEvent event) {
                    return event.flag("stale") ? "a later reading of " + event.aggregateRef() + " replaced it" : null;
                }

                @Override
                public void handle(DomainEvent event) {
                    HANDLED.add(event.eventId());
                }
            };
        }
    }

    @Autowired
    private Outbox events;

    /** NS-API-004: an event the consumer judges irrelevant is recorded IGNORED with its reason, not handled or lost. */
    @Test
    void anIrrelevantOlderEventIsIgnoredWithEvidence() {
        String stale = tx.execute(s -> events.publish("TestReading", "METER-1", java.util.Map.of("stale", true), "test")
                .getEventId());
        String fresh = tx.execute(s -> events.publish("TestReading", "METER-1", java.util.Map.of("stale", false), "test")
                .getEventId());
        outbox.dispatch();
        List<EventDelivery> first = deliveries("METER-1", 1);
        assertThat(first).singleElement().satisfies(d -> {
            assertThat(d.getStatus()).isEqualTo(EventDelivery.IGNORED);
            assertThat(d.getLastError()).isEqualTo("Ignored: a later reading of METER-1 replaced it");
        });
        // The ignored event does not hold up the next one.
        assertThat(deliveries("METER-1", 2)).singleElement()
                .satisfies(d -> assertThat(d.getStatus()).isEqualTo(EventDelivery.DELIVERED));
        assertThat(SupersedingConsumer.HANDLED).contains(fresh).doesNotContain(stale);
    }

    /** NS-API-004: an event in a newer contract version than its consumer supports is refused, not guessed at. */
    @Test
    void anEventNewerThanItsConsumerSupportsIsRefusedWithTheReason() throws Exception {
        String customer = customer("Kopano Version", "+267 71 400 003");
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin",
                                "{\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        jdbc.update("update int_outbox_event set schema_version = 2 where aggregate_ref = ? and aggregate_version = 1", policy);
        outbox.dispatch();
        List<EventDelivery> refused = deliveries(policy, 1);
        assertThat(refused).isNotEmpty().allSatisfy(d -> {
            assertThat(d.getStatus()).isEqualTo(EventDelivery.FAILED);
            assertThat(d.getLastError()).contains("schema version 2").contains("Upgrade the consumer");
        });
    }

    @Test
    void decisionsLeaveChainedEvidenceAndAnEditOrRemovalIsCaught() throws Exception {
        String customer = customer("Kgosi Evidence", "+267 71 400 002");
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin",
                                "{\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/audits/evidence").param("entityRef", policy).with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityType").value("Policy"))
                .andExpect(jsonPath("$[0].actor").value("admin"))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
        mockMvc.perform(get("/audits/evidence/verify").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.intact").value(true));
        mockMvc.perform(get("/audits/evidence/verify").with(signedIn("customer", "password")))
                .andExpect(status().isForbidden());

        // Someone with database access edits a record: the chain names it.
        Long target = jdbc.queryForObject("select min(sequence_no) from audit_evidence where entity_ref = ?", Long.class,
                policy);
        String original = jdbc.queryForObject("select reason from audit_evidence where sequence_no = ?", String.class,
                target);
        jdbc.update("update audit_evidence set reason = 'quietly edited' where sequence_no = ?", target);
        String broken = mockMvc.perform(get("/audits/evidence/verify").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.intact").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(broken, "$.brokenAt")).longValue()).isEqualTo(target);
        assertThat((String) JsonPath.read(broken, "$.problem")).contains("changed after it was recorded");

        // Put it back, then remove the newest record instead: the head shows the tail is gone.
        jdbc.update("update audit_evidence set reason = ? where sequence_no = ?", original, target);
        mockMvc.perform(get("/audits/evidence/verify").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.intact").value(true));
        Long last = jdbc.queryForObject("select max(sequence_no) from audit_evidence", Long.class);
        jdbc.update("delete from audit_evidence where sequence_no = ?", last);
        mockMvc.perform(get("/audits/evidence/verify").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.intact").value(false))
                .andExpect(jsonPath("$.problem").value(org.hamcrest.Matchers.containsString("newest records were removed")));
    }

    @Test
    void aClaimActionOnAVersionSomeoneElseChangedIsRefused() throws Exception {
        String customer = customer("Neo Stale", "+267 71 400 003");
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin",
                                "{\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        String lodged = mockMvc.perform(json(post("/portfolio/claims"), "admin", "{\"policy\":\"" + policy
                                + "\",\"claimantName\":\"Mpho Stale\",\"claimantMobile\":\"+267 71 400 004\","
                                + "\"claimantRelationship\":\"Spouse\",\"description\":\"Test\",\"eventDate\":\""
                                + java.time.LocalDate.now() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Under Review"))
                .andReturn().getResponse().getContentAsString();
        String claim = JsonPath.read(lodged, "$.id");
        Integer version = JsonPath.read(lodged, "$.version");

        mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "admin", "{\"reason\":\"Duplicate claim\"}")
                        .header(ExpectedVersion.HEADER, String.valueOf(version + 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Refresh")));
        String done = mockMvc.perform(json(post("/portfolio/claims/" + claim + "/reject"), "admin",
                                "{\"reason\":\"Duplicate claim\"}")
                        .header(ExpectedVersion.HEADER, String.valueOf(version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Rejected"))
                .andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(done, "$.version")).isGreaterThan(version);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body);
    }

    private List<EventDelivery> deliveries(String policy, long version) {
        return tx.execute(s -> em.createQuery("select d from EventDelivery d join fetch d.event e"
                        + " where e.aggregateRef = :ref and e.aggregateVersion = :v", EventDelivery.class)
                .setParameter("ref", policy).setParameter("v", version).getResultList());
    }

    private String customer(String name, String phone) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\","
                                + "\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }
}
