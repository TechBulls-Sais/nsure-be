package com.nsure.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import com.nsure.integration.OutboxDispatcher;
import com.nsure.integration.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SH09B: event messages are created once per event, recipient and template version (AT-SH09B-01), opt-outs are
 * suppressed with a logged reason (AT-SH09B-02), provider failures retry then land in the failed queue, and a
 * template is approved by someone other than its author.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-communications;DB_CLOSE_DELAY=-1",
        "nsure.communications.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class CommunicationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OutboxDispatcher outbox;

    @Autowired
    private CommunicationEventHandler handler;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void anIssuedPolicyMessagesTheCustomerOnceEvenAfterReplay() throws Exception {
        customer("Onalenna Masire", "onalenna");
        String policy = buyAndPay("onalenna");
        outbox.dispatch();
        outbox.dispatch();

        List<Map<String, Object>> messages = messagesFor(policy);
        assertThat(messages).hasSize(3);
        assertThat(one(messages, "POLICY_ISSUED_EMAIL").get("status")).isEqualTo("QUEUED");
        assertThat(one(messages, "PREMIUM_RECEIVED_EMAIL").get("status")).isEqualTo("QUEUED");
        // WhatsApp needs an explicit opt-in: kept as SUPPRESSED with the reason, never dropped silently.
        Map<String, Object> whatsapp = one(messages, "POLICY_ISSUED_WHATSAPP");
        assertThat(whatsapp.get("status")).isEqualTo("SUPPRESSED");
        assertThat((String) whatsapp.get("lastError")).contains("not opted in");
        Map<String, Object> email = one(messages, "POLICY_ISSUED_EMAIL");
        assertThat(email.get("contact")).isEqualTo("on***@example.com");
        assertThat((String) email.get("subject")).contains(policy);
        assertThat((String) email.get("body")).contains("Onalenna Masire").doesNotContain("{{");

        // Replaying the same events straight into the handler creates nothing new.
        List<OutboxEvent> events = tx.execute(status -> em.createQuery(
                        "select e from OutboxEvent e where e.aggregateRef = :ref", OutboxEvent.class)
                .setParameter("ref", policy).getResultList());
        assertThat(events).extracting(OutboxEvent::getEventType)
                .contains(Events.POLICY_ISSUED, Events.PREMIUM_RECEIVED);
        for (OutboxEvent event : events) {
            if (handler.eventTypes().contains(event.getEventType())) {
                DomainEvent domainEvent = new DomainEvent(event.getEventId(), event.getEventType(),
                        event.getAggregateRef(), event.getCorrelationId(), event.getOccurredAt(),
                        mapper.readTree(event.getPayload()));
                tx.executeWithoutResult(status -> handler.handle(domainEvent));
            }
        }
        assertThat(messagesFor(policy)).hasSize(3);

        mockMvc.perform(post("/communications/dispatch").with(httpBasic("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").isNumber());
        messages = messagesFor(policy);
        assertThat(one(messages, "POLICY_ISSUED_EMAIL").get("status")).isEqualTo("DELIVERED");
        assertThat((String) one(messages, "POLICY_ISSUED_EMAIL").get("providerMessageId")).startsWith("EML-");
        assertThat(one(messages, "PREMIUM_RECEIVED_EMAIL").get("status")).isEqualTo("DELIVERED");
        assertThat(one(messages, "POLICY_ISSUED_WHATSAPP").get("status")).isEqualTo("SUPPRESSED");
        mockMvc.perform(post("/communications/dispatch").with(httpBasic("service", "password")))
                .andExpect(status().isOk());
        assertThat(messagesFor(policy)).hasSize(3);
    }

    @Test
    void anOptOutSuppressesTheChannelAndAnOptInSendsIt() throws Exception {
        String ref = customer("Boitumelo Sebina", "boitumelo");
        mockMvc.perform(get("/communications/preferences/" + ref).with(httpBasic("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.channels[0].status").value("OPTED_IN"))
                .andExpect(jsonPath("$.channels[0].explicit").value(false))
                .andExpect(jsonPath("$.channels[0].deliverable").value(true))
                .andExpect(jsonPath("$.channels[1].status").value("NOT_SET"))
                .andExpect(jsonPath("$.channels[1].deliverable").value(false));

        // Ops may read and approve, not change preferences.
        mockMvc.perform(json(put("/communications/preferences/" + ref), "ops",
                        "{\"channel\":\"EMAIL\",\"status\":\"OPTED_OUT\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/communications/preferences/" + ref), "service",
                        "{\"channel\":\"WHATSAPP\",\"status\":\"OPTED_IN\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(put("/communications/preferences/" + ref), "service",
                        "{\"channel\":\"EMAIL\",\"status\":\"OPTED_OUT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].status").value("OPTED_OUT"))
                .andExpect(jsonPath("$.channels[0].explicit").value(true))
                .andExpect(jsonPath("$.channels[0].deliverable").value(false));
        mockMvc.perform(json(put("/communications/preferences/" + ref), "service",
                        "{\"channel\":\"WHATSAPP\",\"status\":\"OPTED_IN\",\"contact\":\"+267 72 123 456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[1].status").value("OPTED_IN"))
                .andExpect(jsonPath("$.channels[1].contact").value("+*** ** *** 456"))
                .andExpect(jsonPath("$.channels[1].deliverable").value(true));

        String policy = buyAndPay("boitumelo");
        outbox.dispatch();
        List<Map<String, Object>> messages = messagesFor(policy);
        assertThat(messages).hasSize(3);
        assertThat(one(messages, "POLICY_ISSUED_EMAIL").get("status")).isEqualTo("SUPPRESSED");
        assertThat(one(messages, "POLICY_ISSUED_EMAIL").get("lastError"))
                .isEqualTo("Customer opted out of Email messages.");
        assertThat(one(messages, "PREMIUM_RECEIVED_EMAIL").get("status")).isEqualTo("SUPPRESSED");
        assertThat(one(messages, "POLICY_ISSUED_WHATSAPP").get("status")).isEqualTo("QUEUED");

        mockMvc.perform(post("/communications/dispatch").with(httpBasic("service", "password")))
                .andExpect(status().isOk());
        messages = messagesFor(policy);
        assertThat(one(messages, "POLICY_ISSUED_WHATSAPP").get("status")).isEqualTo("DELIVERED");
        assertThat(one(messages, "POLICY_ISSUED_EMAIL").get("status")).isEqualTo("SUPPRESSED");

        // A manual service message obeys the same opt-out.
        mockMvc.perform(json(post("/communications/messages"), "service",
                        "{\"customerRef\":\"" + ref + "\",\"channel\":\"EMAIL\",\"subject\":\"Your request\","
                                + "\"body\":\"Please see your portal.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.eventType").value("ServiceMessage"))
                .andExpect(jsonPath("$.ref").value(containsString("MSG-")));
        mockMvc.perform(json(post("/communications/messages"), "service",
                        "{\"customerRef\":\"" + ref + "\",\"channel\":\"WHATSAPP\",\"body\":\"Please see your portal.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        mockMvc.perform(get("/communications/messages?status=SUPPRESSED").with(httpBasic("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUPPRESSED"));
        mockMvc.perform(get("/communications/messages").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aProviderFailureRetriesThenLandsInTheFailedQueue() throws Exception {
        customer("Kabelo Fail", "failcase");
        String policy = buyAndPay("failcase");
        outbox.dispatch();
        Map<String, Object> email = one(messagesFor(policy), "POLICY_ISSUED_EMAIL");
        assertThat(email.get("status")).isEqualTo("QUEUED");
        Number id = (Number) email.get("id");

        for (int attempt = 1; attempt <= 3; attempt++) {
            mockMvc.perform(post("/communications/dispatch").with(httpBasic("service", "password")))
                    .andExpect(status().isOk());
            email = one(messagesFor(policy), "POLICY_ISSUED_EMAIL");
            assertThat(email.get("attempts")).isEqualTo(attempt);
            assertThat(email.get("status")).isEqualTo(attempt < 3 ? "QUEUED" : "FAILED");
        }
        assertThat((String) email.get("lastError")).contains("rejected").contains("attempt 3 of 3");

        mockMvc.perform(get("/communications/messages?status=FAILED").with(httpBasic("service", "password")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("FAILED"));
        mockMvc.perform(post("/communications/messages/" + id + "/retry").with(httpBasic("ops", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/communications/messages/" + id + "/retry").with(httpBasic("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0));
        mockMvc.perform(post("/communications/messages/" + id + "/retry").with(httpBasic("service", "password")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTemplateVersionIsApprovedByAnotherUser() throws Exception {
        mockMvc.perform(get("/communications/templates").with(httpBasic("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'POLICY_ISSUED_EMAIL' && @.version == 1)].status").value("APPROVED"))
                .andExpect(jsonPath("$[?(@.code == 'SERVICE_CASE_OPENED_EMAIL')].approvedBy").value("system"));

        String body = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"POLICY_ISSUED_EMAIL\",\"subject\":\"Welcome: {{policyRef}}\","
                                + "\"body\":\"Dear {{customerName}}, policy {{policyRef}} is active. See {{portalLink}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.eventType").value("PolicyIssued"))
                .andExpect(jsonPath("$.channel").value("EMAIL"))
                .andExpect(jsonPath("$.createdBy").value("service"))
                .andReturn().getResponse().getContentAsString();
        Number draft = JsonPath.read(body, "$.id");

        // Service may draft but not approve; ops (APPROVE_TEMPLATE) may not draft.
        mockMvc.perform(post("/communications/templates/" + draft + "/approve").with(httpBasic("service", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/communications/templates"), "ops",
                        "{\"code\":\"OPS_DRAFT\",\"eventType\":\"ClaimPaid\",\"channel\":\"EMAIL\",\"subject\":\"x\",\"body\":\"y\"}"))
                .andExpect(status().isForbidden());

        // Super user (admin) may approve a version they drafted. A specialist who only drafts still cannot.
        String own = mockMvc.perform(json(post("/communications/templates"), "admin",
                        "{\"code\":\"CLAIM_PAID_WHATSAPP\",\"eventType\":\"ClaimPaid\",\"channel\":\"WHATSAPP\","
                                + "\"body\":\"Hi {{customerName}}, claim {{claimRef}} is paid. {{portalLink}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        Number ownId = JsonPath.read(own, "$.id");
        mockMvc.perform(post("/communications/templates/" + ownId + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("admin"));

        // Unknown merge fields are refused at approval.
        String bad = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"CASE_ACK_EMAIL\",\"eventType\":\"ServiceCaseOpened\",\"channel\":\"EMAIL\","
                                + "\"subject\":\"Ref {{caseRef}}\",\"body\":\"Your ID {{nationalId}}\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number badId = JsonPath.read(bad, "$.id");
        mockMvc.perform(post("/communications/templates/" + badId + "/approve").with(httpBasic("ops", "password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("{{nationalId}}")));

        mockMvc.perform(post("/communications/templates/" + draft + "/approve").with(httpBasic("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("ops"));
        mockMvc.perform(get("/communications/templates").with(httpBasic("service", "password")))
                .andExpect(jsonPath("$[?(@.code == 'POLICY_ISSUED_EMAIL' && @.version == 1)].status").value("RETIRED"))
                .andExpect(jsonPath("$[?(@.code == 'POLICY_ISSUED_EMAIL' && @.version == 2)].status").value("APPROVED"));
        mockMvc.perform(post("/communications/templates/" + draft + "/approve").with(httpBasic("ops", "password")))
                .andExpect(status().isBadRequest());

        // A new version keeps its event and channel.
        mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"POLICY_ISSUED_EMAIL\",\"channel\":\"WHATSAPP\",\"body\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private String customer(String name, String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 001\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    /** Buys Term Life as the customer and pays the first premium: PolicyIssued + PremiumReceived. */
    private String buyAndPay(String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/shop"), username,
                        "{\"product\":\"Term Life\",\"tier\":\"Silver\",\"beneficiaries\":[{\"fullName\":\"Lesego Moremi\","
                                + "\"relationship\":\"Spouse\",\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(body, "$.requests[0].id");
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), username, "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isOk());
        return policy;
    }

    private List<Map<String, Object>> messagesFor(String businessRef) throws Exception {
        String body = mockMvc.perform(get("/communications/messages").with(httpBasic("service", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[?(@.businessRef == '" + businessRef + "')]");
    }

    private static Map<String, Object> one(List<Map<String, Object>> messages, String templateCode) {
        List<Map<String, Object>> matching = messages.stream()
                .filter(m -> templateCode.equals(m.get("templateCode"))).toList();
        assertThat(matching).as("messages for " + templateCode).hasSize(1);
        return matching.get(0);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = List.of("admin", "service", "ops", "customer").contains(user) ? "password" : "password1";
        return request.with(httpBasic(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
