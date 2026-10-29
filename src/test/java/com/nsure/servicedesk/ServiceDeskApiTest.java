package com.nsure.servicedesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.OutboxDispatcher;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SH10: SLA deadlines by case type, atomic ownership, optimistic concurrency, a pausable SLA clock (AT-SH10-03),
 * resolve / reopen / close with history, and customer self-service limited to the customer's own cases.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-service-desk;DB_CLOSE_DELAY=-1",
        "nsure.communications.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class ServiceDeskApiTest {

    /** The seeded portal login "customer" is Neo Modise. */
    private static final String NEO = "CUS-1006";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private OutboxDispatcher outbox;

    @Test
    void casesGetTheirOwnSlaAndTheCustomerAnAcknowledgement() throws Exception {
        String request = open("service", NEO, "SERVICE_REQUEST", "Address change");
        assertThat(request).startsWith("SR-");
        String complaint = open("service", NEO, "COMPLAINT", "Claim delay");
        assertThat(complaint).startsWith("CMP-");

        String body = detail(request);
        assertThat(hours(JsonPath.read(body, "$.case.openedAt"), JsonPath.read(body, "$.case.dueAt"))).isEqualTo(72);
        assertThat((String) JsonPath.read(body, "$.case.channel")).isEqualTo("PHONE");
        assertThat((String) JsonPath.read(body, "$.history[0].action")).isEqualTo("Opened");
        body = detail(complaint);
        assertThat(hours(JsonPath.read(body, "$.case.openedAt"), JsonPath.read(body, "$.case.dueAt"))).isEqualTo(120);
        assertThat((Boolean) JsonPath.read(body, "$.case.breached")).isFalse();

        // ServiceCaseOpened → the communications handler acknowledges the case with its reference (NS-UX-005).
        outbox.dispatch();
        mockMvc.perform(get("/communications/messages").with(httpBasic("service", "password")))
                .andExpect(jsonPath("$[?(@.businessRef == '" + complaint + "')].templateCode")
                        .value("SERVICE_CASE_OPENED_EMAIL"))
                .andExpect(jsonPath("$[?(@.businessRef == '" + complaint + "')].status").value("QUEUED"))
                .andExpect(jsonPath("$[?(@.businessRef == '" + complaint + "')].subject")
                        .value(hasItem(containsString(complaint))));

        // Validation and permissions.
        mockMvc.perform(json(post("/service/cases"), "service",
                        "{\"customerRef\":\"" + NEO + "\",\"caseType\":\"QUESTION\",\"category\":\"x\",\"subject\":\"y\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/service/cases"), "service",
                        "{\"customerRef\":\"CUS-9999\",\"caseType\":\"COMPLAINT\",\"category\":\"x\",\"subject\":\"y\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(post("/service/cases"), "ops",
                        "{\"customerRef\":\"" + NEO + "\",\"caseType\":\"COMPLAINT\",\"category\":\"x\",\"subject\":\"y\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/service/cases").with(httpBasic("ops", "password"))).andExpect(status().isOk());
    }

    @Test
    void ownershipIsAtomicAndAStaleVersionIsAConflict() throws Exception {
        String ref = open("service", NEO, "SERVICE_REQUEST", "Beneficiary update");
        mockMvc.perform(json(post("/service/cases/" + ref + "/assign"), "service", "{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.owner").value("service"))
                .andExpect(jsonPath("$.case.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.case.version").value(1));

        // Someone acting on the version they loaded before the assignment must refresh.
        mockMvc.perform(json(post("/service/cases/" + ref + "/pause"), "admin",
                        "{\"expectedVersion\":0,\"reason\":\"Waiting for documents\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Refresh and retry")));
        // Another operator cannot silently take an owned case.
        mockMvc.perform(json(post("/service/cases/" + ref + "/assign"), "admin", "{\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already owned by service")));
        mockMvc.perform(json(post("/service/cases/" + ref + "/assign"), "admin", "{\"expectedVersion\":1,\"force\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.owner").value("admin"))
                .andExpect(jsonPath("$.history[?(@.action == 'Assigned')].details", hasItem("Taken over from service by admin")));

        mockMvc.perform(get("/service/cases?mine=true").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[*].ref", hasItem(ref)))
                .andExpect(jsonPath("$[*].owner", everyItem(is("admin"))));
        mockMvc.perform(get("/service/cases?mine=true").with(httpBasic("service", "password")))
                .andExpect(jsonPath("$[*].ref", not(hasItem(ref))));
        mockMvc.perform(get("/service/cases?type=SERVICE_REQUEST&status=IN_PROGRESS").with(httpBasic("service", "password")))
                .andExpect(jsonPath("$[*].ref", hasItem(ref)))
                .andExpect(jsonPath("$[*].caseType", everyItem(is("SERVICE_REQUEST"))));
    }

    @Test
    void pausingStopsTheSlaClockAndResumingMovesTheDueDate() throws Exception {
        String ref = open("service", NEO, "COMPLAINT", "Premium deducted twice");
        mockMvc.perform(json(post("/service/cases/" + ref + "/pause"), "service", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/service/cases/" + ref + "/resume"), "service", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/service/cases/" + ref + "/pause"), "service",
                        "{\"reason\":\"Awaiting bank statement from customer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("PAUSED"))
                .andExpect(jsonPath("$.case.pauseReason").value("Awaiting bank statement from customer"));

        // Opened three hours ago, paused for the last ninety minutes.
        LocalDateTime now = LocalDateTime.now();
        tx.executeWithoutResult(status -> em.createQuery(
                        "update ServiceCase c set c.openedAt = :opened, c.dueAt = :due, c.pausedAt = :paused where c.ref = :ref")
                .setParameter("opened", now.minusHours(3))
                .setParameter("due", now.minusHours(3).plusHours(120))
                .setParameter("paused", now.minusMinutes(90))
                .setParameter("ref", ref)
                .executeUpdate());

        String body = detail(ref);
        long elapsed = ((Number) JsonPath.read(body, "$.case.elapsedMinutes")).longValue();
        long adjusted = ((Number) JsonPath.read(body, "$.case.adjustedMinutes")).longValue();
        assertThat(elapsed).isBetween(179L, 181L);
        assertThat(elapsed - adjusted).isBetween(89L, 91L);
        String dueBefore = JsonPath.read(body, "$.case.dueAt");

        body = mockMvc.perform(json(post("/service/cases/" + ref + "/resume"), "service", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("OPEN"))
                .andReturn().getResponse().getContentAsString();
        int paused = JsonPath.read(body, "$.case.pausedMinutes");
        assertThat(paused).isBetween(89, 91);
        String dueAfter = JsonPath.read(body, "$.case.dueAt");
        assertThat(Duration.between(LocalDateTime.parse(dueBefore), LocalDateTime.parse(dueAfter)).toMinutes())
                .isEqualTo(paused);

        // Past its due date and not resolved: breached.
        tx.executeWithoutResult(status -> em.createQuery("update ServiceCase c set c.dueAt = :due where c.ref = :ref")
                .setParameter("due", LocalDateTime.now().minusHours(1))
                .setParameter("ref", ref)
                .executeUpdate());
        mockMvc.perform(get("/service/cases").with(httpBasic("service", "password")))
                .andExpect(jsonPath("$[?(@.ref == '" + ref + "')].breached").value(true));
    }

    @Test
    void aReopenedCaseKeepsTheEarlierResolutionInHistoryAndOnlyResolvedCasesClose() throws Exception {
        String ref = open("service", NEO, "SERVICE_REQUEST", "Statement request");
        mockMvc.perform(json(post("/service/cases/" + ref + "/close"), "service", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/service/cases/" + ref + "/resolve"), "service", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/service/cases/" + ref + "/escalate"), "service", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/service/cases/" + ref + "/resolve"), "service",
                        "{\"resolution\":\"Statement emailed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("RESOLVED"))
                .andExpect(jsonPath("$.case.resolution").value("Statement emailed"));
        mockMvc.perform(json(post("/service/cases/" + ref + "/reopen"), "service", "{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/service/cases/" + ref + "/reopen"), "service",
                        "{\"reason\":\"Customer did not receive it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("OPEN"))
                .andExpect(jsonPath("$.case.resolution").value(""))
                .andExpect(jsonPath("$.history[?(@.action == 'Reopened')].details")
                        .value(hasItem(containsString("Previous resolution: Statement emailed"))));
        mockMvc.perform(json(post("/service/cases/" + ref + "/resolve"), "service",
                        "{\"resolution\":\"Statement posted\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/service/cases/" + ref + "/close"), "service", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("CLOSED"))
                .andExpect(jsonPath("$.history[*].action", hasItem("Closed")));
        mockMvc.perform(json(post("/service/cases/" + ref + "/pause"), "service", "{\"reason\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aCustomerLodgesAndSeesOnlyTheirOwnCases() throws Exception {
        String other = customer("Kagiso Tau", "kagiso");
        String otherCase = open("service", other, "SERVICE_REQUEST", "Someone else's request");

        String body = mockMvc.perform(json(post("/portfolio/shop/cases"), "customer",
                        "{\"caseType\":\"COMPLAINT\",\"category\":\"Service\",\"subject\":\"Nobody called me back\","
                                + "\"description\":\"I phoned twice last week.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.channel").value("PORTAL"))
                .andReturn().getResponse().getContentAsString();
        String mine = JsonPath.read(body, "$.ref");
        assertThat(mine).startsWith("CMP-");

        mockMvc.perform(json(post("/portfolio/shop/cases"), "customer",
                        "{\"caseType\":\"SERVICE_REQUEST\",\"category\":\"Policy\",\"subject\":\"Change\",\"linkedRef\":\"POL-99999\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/portfolio/shop/cases").with(httpBasic("customer", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ref", hasItem(mine)))
                .andExpect(jsonPath("$[*].ref", not(hasItem(otherCase))))
                .andExpect(jsonPath("$[0].owner").doesNotExist());
        mockMvc.perform(get("/portfolio/shop/cases").with(httpBasic("kagiso", "password1")))
                .andExpect(jsonPath("$[*].ref", hasItem(otherCase)))
                .andExpect(jsonPath("$[*].ref", not(hasItem(mine))));

        // Customers never reach the staff queue; staff see the portal case there.
        mockMvc.perform(get("/service/cases").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/service/cases/" + mine).with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/service/cases/" + mine).with(httpBasic("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.customerRef").value(NEO))
                .andExpect(jsonPath("$.case.openedBy").value("customer"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private String open(String user, String customerRef, String type, String subject) throws Exception {
        String body = mockMvc.perform(json(post("/service/cases"), user,
                        "{\"customerRef\":\"" + customerRef + "\",\"caseType\":\"" + type + "\",\"category\":\"General\","
                                + "\"subject\":\"" + subject + "\",\"description\":\"Logged by phone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("OPEN"))
                .andExpect(jsonPath("$.case.version").value(0))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.case.ref");
    }

    private String detail(String ref) throws Exception {
        return mockMvc.perform(get("/service/cases/" + ref).with(httpBasic("service", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String customer(String name, String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static long hours(String from, String to) {
        return Duration.between(LocalDateTime.parse(from), LocalDateTime.parse(to)).toHours();
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = List.of("admin", "service", "ops", "customer").contains(user) ? "password" : "password1";
        return request.with(httpBasic(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
