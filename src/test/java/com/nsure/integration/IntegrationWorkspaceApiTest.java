package com.nsure.integration;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
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
 * SH12 workspace: the interface catalogue, a simulated outage that builds a monitored backlog and dead letters, and
 * recovery through retry without losing committed events (AT-SH12-02, NS-SH12-003/005, SH12-WORKSPACE).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-integration-workspace;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000",
        "nsure.integration.backlog-check-ms=3600000",
        "nsure.integration.backlog-alert-minutes=0"})
@AutoConfigureMockMvc
class IntegrationWorkspaceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private Outbox outbox;

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private InterfaceRegistry registry;

    @Autowired
    private BacklogMonitor backlog;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void anUnavailablePartnerBuildsAMonitoredBacklogAndRecoversThroughRetry() throws Exception {
        mockMvc.perform(get("/integration/interfaces").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interfaces[?(@.code == 'BANK_PAYMENTS')].connection").value(hasItem("SIMULATED")))
                .andExpect(jsonPath("$.interfaces[?(@.code == 'PARTNER_WEBHOOKS')].exceptionOwner")
                        .value(hasItem("Distribution and scheme teams")))
                .andExpect(jsonPath("$.interfaces[?(@.code == 'SUBLEDGER')].handlers[0]").value(hasItem("finance.subledger")));
        // Only integration managers switch availability, always with a reason.
        mockMvc.perform(json(put("/integration/interfaces/PARTNER_WEBHOOKS/availability"), "finance",
                "{\"available\":false,\"reason\":\"Partner endpoint down\"}")).andExpect(status().isForbidden());
        mockMvc.perform(json(put("/integration/interfaces/PARTNER_WEBHOOKS/availability"), "ops",
                "{\"available\":false}")).andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/integration/interfaces/ERP_GL/availability"), "ops",
                "{\"available\":false,\"reason\":\"No automated link\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/integration/interfaces/PARTNER_WEBHOOKS/availability"), "ops",
                        "{\"available\":false,\"reason\":\"Partner endpoint down for maintenance\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        String eventId = tx.execute(s -> outbox.publish(Events.COVER_LAPSED, "POL-OUTAGE-1",
                Map.of("policyRef", "POL-OUTAGE-1"), "test").getEventId());
        dispatcher.dispatch();
        Long delivery = deliveryId(eventId, "partner.webhooks");
        Map<String, Object> first = jdbc.queryForMap("select status, attempts, failure_class, last_error"
                + " from int_event_delivery where id = ?", delivery);
        assertThat(first.get("STATUS")).isEqualTo("PENDING");
        assertThat(first.get("ATTEMPTS")).isEqualTo(1);
        assertThat(first.get("FAILURE_CLASS")).isEqualTo("TRANSIENT");
        assertThat((String) first.get("LAST_ERROR")).startsWith("Unavailable: Partner event notifications");
        // The committed event is safe and the backlog is visible and alerted.
        mockMvc.perform(get("/integration/interfaces").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$.interfaces[?(@.code == 'PARTNER_WEBHOOKS')].health.backlog").value(hasItem(1)))
                .andExpect(jsonPath("$.interfaces[?(@.code == 'PARTNER_WEBHOOKS')].available").value(hasItem(false)));
        mockMvc.perform(get("/integration/dashboard").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$.interfacesDown").value(hasItem("Partner event notifications")))
                .andExpect(jsonPath("$.retrying").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        assertThat(backlog.check()).isTrue();
        assertThat(alerts("INTERFACE_UNAVAILABLE")).isPositive();

        // Retries run out: the delivery is dead-lettered with its owner and reason, and alerted.
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(json(post("/integration/deliveries/" + delivery + "/retry-now"), "ops", "{}"))
                    .andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("select status from int_event_delivery where id = ?", String.class, delivery))
                .isEqualTo("FAILED");
        mockMvc.perform(json(post("/integration/deliveries/" + delivery + "/retry-now"), "ops", "{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/integration/dead-letters").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[?(@.deliveryId == " + delivery + ")].owner").value(hasItem("Distribution and scheme teams")))
                .andExpect(jsonPath("$[?(@.deliveryId == " + delivery + ")].failureClass").value(hasItem("TRANSIENT")))
                .andExpect(jsonPath("$[?(@.deliveryId == " + delivery + ")].attempts").value(hasItem(5)));
        assertThat(alerts("DELIVERY_DEAD_LETTERED")).isPositive();

        // The partner is back: its transient dead letters resume on their own and nothing is lost or sent twice.
        mockMvc.perform(json(put("/integration/interfaces/PARTNER_WEBHOOKS/availability"), "ops",
                        "{\"available\":true,\"reason\":\"Partner confirmed the endpoint is back\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value(containsString("1 delivery(ies) resumed")));
        assertThat(jdbc.queryForObject("select status from int_event_delivery where id = ?", String.class, delivery))
                .isEqualTo("DELIVERED");
        assertThat(jdbc.queryForList("select signature from int_outbound_message where event_id = ?", String.class,
                eventId)).singleElement().satisfies(sig -> assertThat(sig).startsWith("sha256="));
        mockMvc.perform(get("/integration/operator-actions").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItems("INTERFACE_OUTAGE", "RETRY_NOW",
                        "INTERFACE_RESTORED")));
        mockMvc.perform(get("/integration/reconciliation").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[?(@.code == 'PARTNER_WEBHOOKS')].breaks").value(hasItem(0)));
        mockMvc.perform(get("/integration/interfaces").with(signedIn("compliance", "password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anOperatorReplaysADeadLetterWithAReasonAndTheReplayIsAudited() throws Exception {
        jdbc.update("update int_interface set available = FALSE, availability_note = 'test' where code = 'REINSURER'");
        registry.invalidate();
        String eventId = tx.execute(s -> outbox.publish(Events.BORDEREAU_SUBMITTED, "BDX-REPLAY-1",
                Map.of("bordereauRef", "BDX-REPLAY-1"), "test").getEventId());
        Long delivery = deliveryId(eventId, "reinsurer.bordereau-feed");
        for (int i = 0; i < 5; i++) {
            jdbc.update("update int_event_delivery set next_attempt_at = ? where id = ?",
                    // In UTC, as Hibernate stores it (hibernate.jdbc.time_zone), so it is due on any machine's zone.
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1), delivery);
            dispatcher.dispatch();
        }
        assertThat(jdbc.queryForObject("select status from int_event_delivery where id = ?", String.class, delivery))
                .isEqualTo("FAILED");
        // The provider came back without anyone switching it (no automatic resumption): an operator replays.
        jdbc.update("update int_interface set available = TRUE, availability_note = NULL where code = 'REINSURER'");
        registry.invalidate();
        mockMvc.perform(json(post("/integration/deliveries/" + delivery + "/replay"), "ops", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
        mockMvc.perform(json(post("/integration/deliveries/" + delivery + "/replay"), "finance",
                "{\"reason\":\"Reinsurer is back\"}")).andExpect(status().isForbidden());
        mockMvc.perform(json(post("/integration/deliveries/" + delivery + "/replay"), "ops",
                        "{\"reason\":\"Reinsurer confirmed their gateway is back\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from int_event_delivery where id = ?", String.class, delivery))
                .isEqualTo("DELIVERED");
        mockMvc.perform(get("/integration/operator-actions").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[0].action").value("REPLAY"))
                .andExpect(jsonPath("$[0].reason").value("Reinsurer confirmed their gateway is back"))
                .andExpect(jsonPath("$[0].outcome").value(containsString("DELIVERED")));
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where action = 'Integration: REPLAY'",
                Long.class)).isPositive();
        // A second replay of a delivered item is refused: replay never repeats a business effect.
        mockMvc.perform(json(post("/integration/deliveries/" + delivery + "/replay"), "ops",
                "{\"reason\":\"Try again for luck\"}")).andExpect(status().isConflict());
    }

    @Test
    void theBatchMonitorListsBatchesAcrossInterfaces() throws Exception {
        mockMvc.perform(json(post("/platform/extracts"), "admin", "{\"extractType\":\"CLAIMS\"}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(get("/integration/batches").with(signedIn("ops", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> types = JsonPath.read(body, "$[*].type");
        assertThat(types).contains("Data extract");
        List<String> codes = JsonPath.read(body, "$[*].interfaceCode");
        assertThat(codes).contains("ACTUARIAL");
    }

    private Long deliveryId(String eventId, String handler) {
        return jdbc.queryForObject("select d.id from int_event_delivery d join int_outbox_event e on e.id = d.outbox_event_id"
                + " where e.event_id = ? and d.handler_name = ?", Long.class, eventId, handler);
    }

    private long alerts(String type) {
        return jdbc.queryForObject("select count(*) from staff_alert where alert_type = ?", Long.class, type);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
