package com.nsure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.EventDelivery;
import com.nsure.integration.OutboxDispatcher;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

        // Once the earlier event is through, the held one follows.
        tx.executeWithoutResult(s -> em.createQuery("update EventDelivery d set d.status = 'PENDING', d.attempts = 0,"
                        + " d.nextAttemptAt = :now where d.event.id in (select e.id from OutboxEvent e"
                        + " where e.aggregateRef = :ref)")
                .setParameter("now", LocalDateTime.now().minusSeconds(1)).setParameter("ref", policy).executeUpdate());
        outbox.dispatch();
        assertThat(deliveries(policy, 2)).allSatisfy(d -> assertThat(d.getStatus()).isEqualTo(EventDelivery.DELIVERED));
    }

    @Test
    void decisionsLeaveChainedEvidenceAndAnEditOrRemovalIsCaught() throws Exception {
        String customer = customer("Kgosi Evidence", "+267 71 400 002");
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin",
                                "{\"customerRef\":\"" + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(get("/audits/evidence").param("entityRef", policy).with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entityType").value("Policy"))
                .andExpect(jsonPath("$[0].actor").value("admin"))
                .andExpect(jsonPath("$[0].correlationId").isNotEmpty());
        mockMvc.perform(get("/audits/evidence/verify").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.intact").value(true));
        mockMvc.perform(get("/audits/evidence/verify").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());

        // Someone with database access edits a record: the chain names it.
        Long target = jdbc.queryForObject("select min(sequence_no) from audit_evidence where entity_ref = ?", Long.class,
                policy);
        String original = jdbc.queryForObject("select reason from audit_evidence where sequence_no = ?", String.class,
                target);
        jdbc.update("update audit_evidence set reason = 'quietly edited' where sequence_no = ?", target);
        String broken = mockMvc.perform(get("/audits/evidence/verify").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.intact").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(broken, "$.brokenAt")).longValue()).isEqualTo(target);
        assertThat((String) JsonPath.read(broken, "$.problem")).contains("changed after it was recorded");

        // Put it back, then remove the newest record instead: the head shows the tail is gone.
        jdbc.update("update audit_evidence set reason = ? where sequence_no = ?", original, target);
        mockMvc.perform(get("/audits/evidence/verify").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.intact").value(true));
        Long last = jdbc.queryForObject("select max(sequence_no) from audit_evidence", Long.class);
        jdbc.update("delete from audit_evidence where sequence_no = ?", last);
        mockMvc.perform(get("/audits/evidence/verify").with(httpBasic("admin", "password")))
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
        return request.with(httpBasic(user, "password")).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
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
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }
}
