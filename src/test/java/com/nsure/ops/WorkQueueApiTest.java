package com.nsure.ops;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The work queue (NS-SH10-002, D-24): each user sees and takes only work they may act on, unknown work is refused,
 * a supervisor assigns, the modules respect the owner, and TaskAssigned / SlaBreached / CaseOpened / ApprovalGranted
 * are published (SH10 events).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-work-queue;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WorkQueueApiTest {

    /** The seeded portal login "customer" is Neo Modise. */
    private static final String NEO = "CUS-1006";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkQueueService queue;

    @BeforeEach
    void provisionModuleUsers() {
        ModuleTestUsers.provision(jdbc);
    }

    /**
     * GL03 workspace and AT-SH09B-03: group members waiting for an underwriting decision and replies from unverified
     * senders are queue work for the people who may act on them, and nobody else.
     */
    @Test
    void groupUnderwritingAndSenderVerificationAreQueueWork() throws Exception {
        jdbc.update("INSERT INTO com_inbound (provider_message_id, channel, sender, body, received_at, status,"
                + " verification_status) VALUES ('QA-INB-QUEUE-1', 'WHATSAPP', '+267 71 999 001', 'Is my claim paid?',"
                + " CURRENT_TIMESTAMP, 'TRIAGE', 'REQUIRED')");
        Long inbound = jdbc.queryForObject("select id from com_inbound where provider_message_id = 'QA-INB-QUEUE-1'",
                Long.class);
        Long pending = jdbc.queryForObject("select count(*) from grp_member where member_status ="
                + " 'UNDERWRITING_PENDING'", Long.class);

        String grp = ok(get("/ops/queue"), "grpadmin", null);
        assertThat(JsonPath.<List<String>>read(grp, "$[?(@.workflow == 'GROUP_MEMBER_UW')].key")).hasSize(
                pending.intValue());
        assertThat(JsonPath.<List<String>>read(grp, "$[*].workflow")).doesNotContain("SENDER_VERIFICATION");

        String service = ok(get("/ops/queue"), "service", null);
        assertThat(JsonPath.<List<String>>read(service, "$[*].key")).contains("SENDER_VERIFICATION:INB-" + inbound)
                .noneMatch(k -> k.startsWith("GROUP_MEMBER_UW:"));
        Map<String, Object> item = JsonPath.<List<Map<String, Object>>>read(service,
                "$[?(@.key == 'SENDER_VERIFICATION:INB-" + inbound + "')]").get(0);
        assertThat(item).containsEntry("canAct", true).containsEntry("team", "Communications")
                .containsEntry("page", "communications");
    }

    @Test
    void eachRoleSeesAndTakesOnlyWorkItMayActOn() throws Exception {
        String request = partnerRequest("lender");
        String key = "PORTAL_REQUEST:" + request;
        String complaint = JsonPath.read(ok(post("/service/cases"), "service", "{\"customerRef\":\"" + NEO
                + "\",\"caseType\":\"COMPLAINT\",\"category\":\"Claims\",\"subject\":\"Slow claim\",\"priority\":\"HIGH\"}"),
                "$.case.ref");

        // Read-only and unrelated roles have nothing to act on, so nothing to see or take.
        for (String user : List.of("compliance", "onboarding")) {
            assertThat(JsonPath.<List<Object>>read(ok(get("/ops/queue"), user, null), "$")).isEmpty();
            assertThat(call(post("/ops/queue/claim").param("key", key), user, null).getStatus()).isEqualTo(403);
        }
        // An unknown key is refused, not owned.
        assertThat(call(post("/ops/queue/claim").param("key", "QA-D:FAKE-001"), "credit", null).getStatus())
                .isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from wf_assignment where item_key = 'QA-D:FAKE-001'",
                Integer.class)).isZero();

        // A lender's request is credit life's work; a complaint is the service desk's.
        String credit = ok(get("/ops/queue"), "credit", null);
        assertThat(JsonPath.<List<String>>read(credit, "$[*].key")).contains(key);
        assertThat(JsonPath.<List<String>>read(credit, "$[*].workflow")).doesNotContain("COMPLAINT", "UNDERWRITING");
        String service = ok(get("/ops/queue"), "service", null);
        assertThat(JsonPath.<List<String>>read(service, "$[*].key")).contains("COMPLAINT:" + complaint)
                .doesNotContain(key);
        Map<String, Object> item = JsonPath.<List<Map<String, Object>>>read(service,
                "$[?(@.key == 'COMPLAINT:" + complaint + "')]").get(0);
        assertThat(item).containsEntry("priority", "HIGH").containsEntry("canAct", true)
                .containsEntry("team", "Customer service").containsKeys("nextAction", "ageHours", "dueAt");
        assertThat(call(post("/ops/queue/claim").param("key", key), "service", null).getStatus()).isEqualTo(403);

        // Approvals: the maker never gets their own change to approve.
        Number roleId = JsonPath.read(ok(post("/roles"), "admin", "{\"name\":\"Queue clerk\",\"description\":\"x\"}"),
                "$.resourceId");
        Number commandId = JsonPath.read(call(put("/roles/" + roleId), "admin", "{\"description\":\"Queue\"}")
                .getContentAsString(), "$.commandId");
        String approval = "ACCESS_APPROVAL:" + commandId;
        String adminQueue = ok(get("/ops/queue"), "admin", null);
        assertThat(JsonPath.<List<Object>>read(adminQueue, "$[?(@.key == '" + approval + "')].canAct"))
                .containsExactly(false);
        assertThat(JsonPath.<List<Object>>read(adminQueue, "$[?(@.key == '" + approval + "')].approval"))
                .containsExactly(true);
        assertThat(JsonPath.<List<Object>>read(adminQueue, "$[?(@.key == '" + key + "')].approval"))
                .containsExactly(false);
        assertThat(call(post("/ops/queue/claim").param("key", approval), "admin", null).getStatus()).isEqualTo(403);
        assertThat(call(post("/ops/queue/claim").param("key", approval), "secadmin", null).getStatus()).isEqualTo(200);
        ok(post("/audits/" + commandId + "/approve"), "secadmin", null);
        assertThat(events("ApprovalGranted", "COMMAND-" + commandId)).isEqualTo(1);
        assertThat(events("CaseOpened", complaint)).isEqualTo(1);
    }

    @Test
    void theOwnerWorksItAndASupervisorHandsItOn() throws Exception {
        String request = partnerRequest("lender");
        String key = "PORTAL_REQUEST:" + request;
        ok(post("/ops/queue/claim").param("key", key), "credit", null);
        assertThat(events("TaskAssigned", key)).isEqualTo(1);
        assertThat(call(post("/ops/queue/claim").param("key", key), "admin", null).getStatus()).isEqualTo(409);

        // The module's own action respects the owner: someone else cannot close it under them.
        MockHttpServletResponse refused = call(post("/ops/portal-requests/" + request + "/close"), "admin",
                "{\"note\":\"Done by someone else\"}");
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(refused.getContentAsString()).contains("owned by credit");

        // Only a supervisor assigns, and only to someone who may act on it.
        assertThat(call(post("/ops/queue/assign").param("key", key).param("to", "admin"), "credit", null).getStatus())
                .isEqualTo(403);
        assertThat(JsonPath.<List<String>>read(ok(get("/ops/queue/assignees").param("key", key), "admin", null), "$"))
                .contains("credit", "admin").doesNotContain("compliance", "lender");
        assertThat(call(post("/ops/queue/assign").param("key", key).param("to", "compliance"), "admin", null)
                .getStatus()).isEqualTo(422);
        ok(post("/ops/queue/assign").param("key", key).param("to", "admin"), "admin", null);
        assertThat(JsonPath.<List<Object>>read(ok(get("/ops/queue"), "admin", null),
                "$[?(@.key == '" + key + "')].owner")).containsExactly("admin");
        assertThat(events("TaskAssigned", key)).isEqualTo(2);
        ok(post("/ops/portal-requests/" + request + "/close"), "admin", "{\"note\":\"Loan registered\"}");

        // A service case keeps its owner on the case: taken from the queue, a non-owner cannot resolve it.
        String sr = JsonPath.read(ok(post("/service/cases"), "service", "{\"customerRef\":\"" + NEO
                + "\",\"caseType\":\"SERVICE_REQUEST\",\"category\":\"Policy\",\"subject\":\"Address\"}"), "$.case.ref");
        ok(post("/ops/queue/claim").param("key", "SERVICE_REQUEST:" + sr), "service", null);
        assertThat(JsonPath.<String>read(ok(get("/service/cases/" + sr), "service", null), "$.case.owner"))
                .isEqualTo("service");
        assertThat(call(post("/service/cases/" + sr + "/resolve"), "admin", "{\"resolution\":\"Changed\"}")
                .getStatus()).isEqualTo(409);
        // A supervisor gives it to someone else in the service desk itself; nobody else can.
        assertThat(call(post("/service/cases/" + sr + "/assign"), "service", "{\"to\":\"admin\"}").getStatus())
                .isEqualTo(403);
        ok(post("/service/cases/" + sr + "/assign"), "admin", "{\"to\":\"admin\"}");
        ok(post("/service/cases/" + sr + "/resolve"), "admin", "{\"resolution\":\"Changed\"}");
        ok(post("/service/cases/" + sr + "/close"), "admin", "{}");
        assertThat(events("CaseClosed", sr)).isEqualTo(1);
    }

    @Test
    void workPastItsDueTimeRaisesSlaBreachedOnce() throws Exception {
        String request = partnerRequest("employer");
        String key = "PORTAL_REQUEST:" + request;
        jdbc.update("update portal_request set created_at = ? where record_ref = ?",
                LocalDateTime.now().minusDays(30), request);
        assertThat(JsonPath.<List<Object>>read(ok(get("/ops/queue"), "admin", null),
                "$[?(@.key == '" + key + "')].breached")).containsExactly(true);
        assertThat(queue.detectBreaches()).isPositive();
        assertThat(events("SlaBreached", key)).isEqualTo(1);
        queue.detectBreaches();
        assertThat(events("SlaBreached", key)).isEqualTo(1);
    }

    private String partnerRequest(String partner) throws Exception {
        return JsonPath.read(ok(post("/ops/portal/requests"), partner,
                "{\"requestType\":\"GENERAL\",\"detail\":\"Please call us about our account\"}"), "$.ref");
    }

    private int events(String type, String aggregateRef) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Integer.class, type, aggregateRef);
    }

    private String ok(MockHttpServletRequestBuilder request, String user, String body) throws Exception {
        MockHttpServletResponse response = call(request, user, body);
        assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(200, 201);
        return response.getContentAsString();
    }

    private MockHttpServletResponse call(MockHttpServletRequestBuilder request, String user, String body)
            throws Exception {
        request.with(signedIn(user, "password"));
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }
}
