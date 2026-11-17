package com.nsure.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import com.nsure.integration.DomainEvent;
import com.nsure.integration.Events;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.integration.OutboxEvent;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private JdbcTemplate moduleJdbc;

    @BeforeEach
    void provisionModuleUsers() {
        ModuleTestUsers.provision(moduleJdbc);
    }

    @Autowired
    private OutboxDispatcher outbox;

    @Autowired
    private CommunicationEventHandler handler;

    @Autowired
    private MessagingWebhooks webhooks;

    @Autowired
    private JdbcTemplate jdbc;

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

        // NS-SH09B-004: the provider's acceptance is not delivery; delivery comes from its report (next run).
        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        messages = messagesFor(policy);
        assertThat(one(messages, "POLICY_ISSUED_EMAIL").get("status")).isEqualTo("ACCEPTED");
        assertThat((String) one(messages, "POLICY_ISSUED_EMAIL").get("providerMessageId")).startsWith("EML-");
        assertThat((String) one(messages, "POLICY_ISSUED_EMAIL").get("deliveredAt")).isEmpty();
        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivered").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
        messages = messagesFor(policy);
        assertThat(one(messages, "POLICY_ISSUED_EMAIL").get("status")).isEqualTo("DELIVERED");
        assertThat(one(messages, "PREMIUM_RECEIVED_EMAIL").get("status")).isEqualTo("DELIVERED");
        assertThat(one(messages, "POLICY_ISSUED_WHATSAPP").get("status")).isEqualTo("SUPPRESSED");
        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
                .andExpect(status().isOk());
        assertThat(messagesFor(policy)).hasSize(3);
    }

    @Test
    void anOptOutSuppressesTheChannelAndAnOptInSendsIt() throws Exception {
        String ref = customer("Boitumelo Sebina", "boitumelo", "+267 71 555 127");
        mockMvc.perform(get("/communications/preferences/" + ref).with(signedIn("ops", "password")))
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
                .andExpect(status().isUnprocessableEntity());
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

        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
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
        // Blueprint §5: a free-form WhatsApp message only within 24 hours of the customer's last WhatsApp message.
        String whatsapp = "{\"customerRef\":\"" + ref + "\",\"channel\":\"WHATSAPP\",\"body\":\"Please see your portal.\"}";
        mockMvc.perform(json(post("/communications/messages"), "service", whatsapp))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("within 24 hours")));
        // NS-SH09B-006: the opted-in number was never verified, so a reply from it is not trusted to name the customer.
        callback("inbound", "{\"providerMessageId\":\"wa-in-0\",\"channel\":\"WHATSAPP\",\"from\":\"+26772123456\","
                + "\"text\":\"Hello, is my cover active?\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRIAGE"));
        mockMvc.perform(json(post("/communications/messages"), "service", whatsapp))
                .andExpect(status().isUnprocessableEntity());
        // The phone registered at onboarding is verified, so a reply from it links to the customer.
        callback("inbound", "{\"providerMessageId\":\"wa-in-1\",\"channel\":\"WHATSAPP\",\"from\":\"+26771555127\","
                + "\"text\":\"Hello, is my cover active?\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LINKED"))
                .andExpect(jsonPath("$.caseRef").value(org.hamcrest.Matchers.startsWith("SR-")));
        mockMvc.perform(json(post("/communications/messages"), "service", whatsapp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"));

        // D-23 / AT-SH09B-02: once the customer opts out of WhatsApp, a new send is suppressed with the reason.
        mockMvc.perform(json(put("/communications/preferences/" + ref), "service",
                        "{\"channel\":\"WHATSAPP\",\"status\":\"OPTED_OUT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[1].deliverable").value(false));
        mockMvc.perform(json(post("/communications/messages"), "service", whatsapp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.lastError").value("Customer opted out of WhatsApp messages."));
        // The opt-out is checked before the 24-hour window, so it shows even when the window has closed.
        jdbc.update("update com_inbound set received_at = ? where customer_ref = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(3)), ref);
        mockMvc.perform(json(post("/communications/messages"), "service", whatsapp))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPPRESSED"))
                .andExpect(jsonPath("$.lastError").value("Customer opted out of WhatsApp messages."));

        mockMvc.perform(get("/communications/messages?status=SUPPRESSED").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUPPRESSED"));
        mockMvc.perform(get("/communications/messages").with(signedIn("customer", "password")))
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
            mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
                    .andExpect(status().isOk());
            email = one(messagesFor(policy), "POLICY_ISSUED_EMAIL");
            assertThat(email.get("attempts")).isEqualTo(attempt);
            assertThat(email.get("status")).isEqualTo(attempt < 3 ? "QUEUED" : "FAILED");
        }
        assertThat((String) email.get("lastError")).contains("rejected").contains("attempt 3 of 3");

        mockMvc.perform(get("/communications/messages?status=FAILED").with(signedIn("service", "password")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].status").value("FAILED"));
        // SH09B-EVENTS: landing in the failed queue publishes MessageFailed (references only).
        assertThat(eventsFor(Events.MESSAGE_FAILED, (String) email.get("ref"))).hasSize(1)
                .allSatisfy(payload -> assertThat(payload).contains("\"purpose\":\"POLICY_ISSUANCE\"")
                        .doesNotContain("@example.com"));
        // The failed queue has owners: one person takes a message, another is told who has it.
        mockMvc.perform(json(post("/communications/messages/" + id + "/owner"), "service", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("service"));
        mockMvc.perform(json(post("/communications/messages/" + id + "/owner"), "admin", "{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/communications/messages/" + id + "/retry").with(signedIn("ops", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/communications/messages/" + id + "/retry").with(signedIn("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.attempts").value(0));
        mockMvc.perform(post("/communications/messages/" + id + "/retry").with(signedIn("service", "password")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aTemplateVersionIsApprovedByAnotherUser() throws Exception {
        mockMvc.perform(get("/communications/templates").with(signedIn("ops", "password")))
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
        mockMvc.perform(post("/communications/templates/" + draft + "/approve").with(signedIn("service", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/communications/templates"), "ops",
                        "{\"code\":\"OPS_DRAFT\",\"eventType\":\"ClaimPaid\",\"channel\":\"EMAIL\",\"subject\":\"x\",\"body\":\"y\"}"))
                .andExpect(status().isForbidden());

        // Nobody approves a version they drafted, the administrator included (Nsure Life NS-UX-004): a second
        // administrator does.
        String own = mockMvc.perform(json(post("/communications/templates"), "admin",
                        "{\"code\":\"CLAIM_PAID_WHATSAPP\",\"eventType\":\"ClaimPaid\",\"channel\":\"WHATSAPP\","
                                + "\"body\":\"Hi {{customerName}}, claim {{claimRef}} is paid. {{portalLink}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        Number ownId = JsonPath.read(own, "$.id");
        mockMvc.perform(post("/communications/templates/" + ownId + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/communications/templates/" + ownId + "/approve").with(signedIn("secadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("secadmin"));

        // Unknown merge fields are refused at approval.
        String bad = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"CASE_ACK_EMAIL\",\"eventType\":\"ServiceCaseOpened\",\"channel\":\"EMAIL\","
                                + "\"subject\":\"Ref {{caseRef}}\",\"body\":\"Your ID {{nationalId}}\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number badId = JsonPath.read(bad, "$.id");
        mockMvc.perform(post("/communications/templates/" + badId + "/approve").with(signedIn("ops", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("{{nationalId}}")));

        mockMvc.perform(post("/communications/templates/" + draft + "/approve").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("ops"));
        mockMvc.perform(get("/communications/templates").with(signedIn("service", "password")))
                .andExpect(jsonPath("$[?(@.code == 'POLICY_ISSUED_EMAIL' && @.version == 1)].status").value("RETIRED"))
                .andExpect(jsonPath("$[?(@.code == 'POLICY_ISSUED_EMAIL' && @.version == 2)].status").value("APPROVED"));
        mockMvc.perform(post("/communications/templates/" + draft + "/approve").with(signedIn("ops", "password")))
                .andExpect(status().isUnprocessableEntity());

        // A new version keeps its event and channel.
        mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"POLICY_ISSUED_EMAIL\",\"channel\":\"WHATSAPP\",\"body\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    /** D-22: an approved template is withdrawn without a replacement, with a reason and a second person. */
    @Test
    void anApprovedTemplateIsWithdrawnWithAReasonAndASecondPerson() throws Exception {
        String drafted = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"CLAIM_REJECTED_EMAIL_D22\",\"eventType\":\"ClaimRejected\",\"channel\":\"EMAIL\","
                                + "\"subject\":\"Claim {{claimRef}}\",\"body\":\"Dear {{customerName}}, see {{portalLink}}\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(drafted, "$.id");
        String path = "/communications/templates/" + id;

        // A draft cannot be withdrawn: it is not in use.
        mockMvc.perform(json(post(path + "/withdraw"), "service", "{\"reason\":\"Wrong wording for this event\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post(path + "/approve").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // A reason is required; the customer never reaches it.
        mockMvc.perform(json(post(path + "/withdraw"), "service", "{\"reason\":\"short\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post(path + "/withdraw"), "customer", "{\"reason\":\"Wrong wording for this event\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post(path + "/withdraw"), "service", "{\"reason\":\"Wrong wording for this event\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.withdrawalRequestedBy").value("service"))
                .andExpect(jsonPath("$.withdrawalReason").value("Wrong wording for this event"));
        mockMvc.perform(json(post(path + "/withdraw"), "service", "{\"reason\":\"Wrong wording for this event\"}"))
                .andExpect(status().isConflict());
        // The requester cannot confirm (service lacks APPROVE_TEMPLATE; admin asking and confirming is refused too).
        mockMvc.perform(post(path + "/withdraw/approve").with(signedIn("service", "password")))
                .andExpect(status().isForbidden());
        // Declined: still approved, the request cleared.
        mockMvc.perform(post(path + "/withdraw/decline").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.withdrawalRequestedBy").value(""));

        mockMvc.perform(json(post(path + "/withdraw"), "admin", "{\"reason\":\"Sends the wrong appeal address\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(path + "/withdraw/approve").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path + "/withdraw/approve").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.withdrawnBy").value("ops"))
                .andExpect(jsonPath("$.withdrawalReason").value("Sends the wrong appeal address"));
        mockMvc.perform(post(path + "/withdraw/approve").with(signedIn("ops", "password")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where entity_ref = ?", Long.class,
                "CLAIM_REJECTED_EMAIL_D22 v1")).isGreaterThanOrEqualTo(4L);

        // A withdrawn version sends nothing: the event finds no approved template for the code.
        DomainEvent event = new DomainEvent(java.util.UUID.randomUUID().toString(), Events.CLAIM_REJECTED, "CLM-D22",
                java.util.UUID.randomUUID().toString(), java.time.LocalDateTime.now(),
                mapper.readTree("{\"claimRef\":\"CLM-D22\",\"customerName\":\"Nobody D22\"}"));
        tx.executeWithoutResult(status -> handler.handle(event));
        assertThat(messagesFor("CLM-D22")).noneMatch(m -> "CLAIM_REJECTED_EMAIL_D22".equals(m.get("templateCode")));
        // A new version can still be drafted for the code.
        mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"CLAIM_REJECTED_EMAIL_D22\",\"subject\":\"Claim {{claimRef}}\",\"body\":\"Corrected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    /**
     * AT-SH09B-01 / NS-SH09B-003: an issue event messages the customer and, through an approved intermediary template,
     * the authorised agent servicing the policy, each once even after replay. The agent's message carries references
     * only; an agent who is not active is suppressed with the reason.
     */
    @Test
    void anIssueEventMessagesTheCustomerAndTheAuthorisedAgentOnceEvenAfterReplay() throws Exception {
        // An intermediary template may use references only: an amount is refused at approval.
        String risky = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"POLICY_ISSUED_AGENT_RISKY\",\"eventType\":\"PolicyIssued\",\"channel\":\"EMAIL\","
                                + "\"audience\":\"INTERMEDIARY\",\"subject\":\"{{policyRef}}\",\"body\":\"Premium {{premium}}\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/communications/templates/" + JsonPath.read(risky, "$.id") + "/approve")
                        .with(signedIn("ops", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("{{premium}}")));
        String agentTemplate = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"POLICY_ISSUED_AGENT_EMAIL\",\"eventType\":\"PolicyIssued\",\"channel\":\"EMAIL\","
                                + "\"audience\":\"INTERMEDIARY\",\"subject\":\"Policy {{policyRef}} issued\","
                                + "\"body\":\"Dear {{intermediaryName}}, {{customerName}}'s {{product}} policy {{policyRef}}"
                                + " is issued. Details in the partner portal: {{portalLink}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.knownFields").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("premium"))))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/communications/templates/" + JsonPath.read(agentTemplate, "$.id") + "/approve")
                        .with(signedIn("ops", "password")))
                .andExpect(status().isOk());

        customer("Lorato Agentless", "loratoa");
        String policy = buyAndPay("loratoa");
        intermediary("INT-AGT01", "Kgosi Brokers", "ACTIVE", "kgosi.brokers@example.com");
        assign(policy, "INT-AGT01");
        outbox.dispatch();
        outbox.dispatch();

        List<Map<String, Object>> messages = messagesFor(policy);
        Map<String, Object> agent = one(messages, "POLICY_ISSUED_AGENT_EMAIL");
        assertThat(agent.get("recipientRole")).isEqualTo("INTERMEDIARY");
        assertThat(agent.get("recipientName")).isEqualTo("Kgosi Brokers");
        assertThat(agent.get("customerRef")).isEqualTo("");
        assertThat(agent.get("status")).isEqualTo("QUEUED");
        assertThat(agent.get("contact")).isEqualTo("kg***@example.com");
        assertThat((String) agent.get("recipientBasis")).contains("Intermediary servicing policy " + policy, "INT-AGT01");
        assertThat((String) agent.get("body")).contains("Kgosi Brokers", "Lorato Agentless", policy).doesNotContain("BWP");
        Map<String, Object> customerMessage = one(messages, "POLICY_ISSUED_EMAIL");
        assertThat(customerMessage.get("recipientRole")).isEqualTo("CUSTOMER");
        assertThat((String) customerMessage.get("recipientBasis")).contains("Customer named on PolicyIssued");
        int count = messages.size();

        // Replaying the issue event creates nothing new for either recipient.
        replay(policy);
        assertThat(messagesFor(policy)).hasSize(count);

        // An agent who is not active is not messaged: the message is kept as suppressed with the reason.
        customer("Mpho Suspended", "mphos");
        String second = buyAndPay("mphos");
        intermediary("INT-AGT02", "Lapsed Agency", "SUSPENDED", "lapsed@example.com");
        assign(second, "INT-AGT02");
        outbox.dispatch();
        Map<String, Object> suspended = one(messagesFor(second), "POLICY_ISSUED_AGENT_EMAIL");
        assertThat(suspended.get("status")).isEqualTo("SUPPRESSED");
        assertThat((String) suspended.get("lastError")).contains("INT-AGT02 is SUSPENDED");
        // A policy no intermediary services gets no agent message at all.
        customer("Neo Direct", "neodirect");
        String direct = buyAndPay("neodirect");
        outbox.dispatch();
        assertThat(messagesFor(direct)).noneMatch(m -> "POLICY_ISSUED_AGENT_EMAIL".equals(m.get("templateCode")));
    }

    /**
     * NS-SH09B-004/007 and SH09B-EVENTS: each message keeps its trail (queued, submitted, accepted, delivered; staff
     * actions; the provider's raw report), its purpose and why its recipient was chosen; the bus carries
     * CommunicationRequested, MessageDelivered and InboundMessageReceived with references only. The workspace gets a
     * template preview, a delivery dashboard and the customer's conversation; promotional templates are refused.
     */
    @Test
    void messagesKeepTheirTrailAndPublishTheirEvents() throws Exception {
        String ref = customer("Dineo Trail", "dineot");
        String policy = buyAndPay("dineot");
        outbox.dispatch();
        Map<String, Object> email = one(messagesFor(policy), "POLICY_ISSUED_EMAIL");
        String messageRef = (String) email.get("ref");
        assertThat(email.get("purpose")).isEqualTo("POLICY_ISSUANCE");
        assertThat(eventsFor(Events.COMMUNICATION_REQUESTED, messageRef)).hasSize(1)
                .allSatisfy(payload -> assertThat(payload).contains("\"status\":\"QUEUED\"", "\"channel\":\"EMAIL\"")
                        .doesNotContain("dineot@example.com", "Dear"));

        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")));
        String providerId = (String) one(messagesFor(policy), "POLICY_ISSUED_EMAIL").get("providerMessageId");
        String read = "{\"providerMessageId\":\"" + providerId + "\",\"status\":\"READ\",\"detail\":\"opened\"}";
        callback("status", read).andExpect(jsonPath("$.status").value("READ"));
        assertThat(eventsFor(Events.MESSAGE_DELIVERED, messageRef)).isEmpty();
        mockMvc.perform(get("/communications/messages/" + email.get("id")).with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.recipientBasis").value(containsString(ref)))
                .andExpect(jsonPath("$.trail[*].status").value(org.hamcrest.Matchers.contains("QUEUED", "SUBMITTED",
                        "ACCEPTED", "READ")))
                .andExpect(jsonPath("$.trail[3].source").value("PROVIDER"))
                .andExpect(jsonPath("$.trail[3].rawPayload").value(read));

        // A message delivered by the provider's report publishes MessageDelivered.
        Map<String, Object> receipt = one(messagesFor(policy), "PREMIUM_RECEIVED_EMAIL");
        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")));
        assertThat(one(messagesFor(policy), "PREMIUM_RECEIVED_EMAIL").get("status")).isEqualTo("DELIVERED");
        assertThat(eventsFor(Events.MESSAGE_DELIVERED, (String) receipt.get("ref"))).hasSize(1);

        // An inbound reply publishes InboundMessageReceived without the sender or the text.
        callback("inbound", "{\"providerMessageId\":\"trail-in-1\",\"channel\":\"EMAIL\",\"from\":\"dineot@example.com\","
                + "\"text\":\"Thank you for the welcome letter\"}")
                .andExpect(jsonPath("$.status").value("LINKED"));
        List<String> inbound = jdbc.queryForList("select payload from int_outbox_event where event_type = ?",
                String.class, Events.INBOUND_MESSAGE_RECEIVED);
        assertThat(inbound).anySatisfy(payload -> assertThat(payload).contains("\"status\":\"LINKED\"", ref)
                .doesNotContain("dineot@example.com", "welcome letter"));

        // A manual reply on the case joins the customer's conversation timeline with the inbound message.
        String caseRef = jdbc.queryForObject("select case_ref from com_inbound where provider_message_id = 'trail-in-1'",
                String.class);
        mockMvc.perform(json(post("/communications/messages"), "service", "{\"customerRef\":\"" + ref
                        + "\",\"channel\":\"EMAIL\",\"subject\":\"Re: your letter\",\"body\":\"You are welcome.\","
                        + "\"caseRef\":\"" + caseRef + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessRef").value(caseRef))
                .andExpect(jsonPath("$.purpose").value("SERVICE_UPDATE"));
        mockMvc.perform(get("/communications/conversations/" + ref).with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.direction == 'IN')].caseRef").value(org.hamcrest.Matchers.hasItem(caseRef)))
                .andExpect(jsonPath("$[?(@.direction == 'OUT' && @.caseRef == '" + caseRef + "')].subject")
                        .value(org.hamcrest.Matchers.hasItem("Re: your letter")));
        mockMvc.perform(json(post("/communications/messages"), "service", "{\"customerRef\":\"" + ref
                        + "\",\"channel\":\"EMAIL\",\"subject\":\"x\",\"body\":\"y\",\"caseRef\":\"SR-999999\"}"))
                .andExpect(status().isUnprocessableEntity());

        // Staff actions are in the evidence chain.
        assertThat(jdbc.queryForObject("select count(*) from audit_evidence where action = 'Service message written'"
                + " and actor = 'service'", Long.class)).isPositive();

        // The delivery dashboard counts per channel over the period.
        mockMvc.perform(get("/communications/metrics?days=7").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$.channels[0].delivered").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.byDay.length()").value(7));

        // The template designer previews with sample values and flags fields the audience may not use.
        mockMvc.perform(json(post("/communications/templates/preview"), "service", "{\"eventType\":\"ClaimPaid\","
                        + "\"audience\":\"INTERMEDIARY\",\"subject\":\"{{claimRef}}\",\"body\":\"Paid {{amount}} to {{customerName}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("CLM-000045"))
                .andExpect(jsonPath("$.unknownFields").value(org.hamcrest.Matchers.contains("amount")))
                .andExpect(jsonPath("$.purpose").value("CLAIM_PROGRESS"));
        // Promotional campaigns are a separate purpose that is not available.
        mockMvc.perform(json(post("/communications/templates"), "service", "{\"code\":\"SUMMER_PROMO\","
                        + "\"eventType\":\"OperationalNotice\",\"channel\":\"EMAIL\",\"purpose\":\"PROMOTIONAL\","
                        + "\"subject\":\"Offer\",\"body\":\"Buy more cover\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Promotional")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    /** The payloads of the outbox events of a type for an aggregate. */
    private List<String> eventsFor(String type, String aggregateRef) {
        return jdbc.queryForList("select payload from int_outbox_event where event_type = ? and aggregate_ref = ?",
                String.class, type, aggregateRef);
    }

    private void intermediary(String ref, String name, String status, String emailAddress) {
        jdbc.update("insert into dist_intermediary (record_ref, name, intermediary_type, licence_number, status,"
                + " appointment_expiry, email, mobile, created_by, created_at, updated_by, updated_at)"
                + " values (?, ?, 'BROKER', ?, ?, ?, ?, '+267 72 555 100', 'test', ?, 'test', ?)", ref, name, "LIC-" + ref,
                status, java.sql.Date.valueOf(java.time.LocalDate.now().plusYears(1)), emailAddress,
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()), java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
    }

    private void assign(String policy, String intermediary) {
        jdbc.update("insert into dist_policy_assignment (policy_ref, open_policy_ref, intermediary_id, effective_from,"
                + " assigned_by, assigned_at) select ?, ?, id, ?, 'test', ? from dist_intermediary where record_ref = ?",
                policy, policy, java.sql.Date.valueOf(java.time.LocalDate.now()),
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()), intermediary);
    }

    /** Hands the policy's events straight to the communications handler again, as a replay would. */
    private void replay(String policy) throws Exception {
        List<OutboxEvent> events = tx.execute(status -> em.createQuery(
                        "select e from OutboxEvent e where e.aggregateRef = :ref", OutboxEvent.class)
                .setParameter("ref", policy).getResultList());
        for (OutboxEvent event : events) {
            if (handler.eventTypes().contains(event.getEventType())) {
                DomainEvent domainEvent = new DomainEvent(event.getEventId(), event.getEventType(),
                        event.getAggregateRef(), event.getCorrelationId(), event.getOccurredAt(),
                        mapper.readTree(event.getPayload()));
                tx.executeWithoutResult(status -> handler.handle(domainEvent));
            }
        }
    }

    private String customer(String name, String username) throws Exception {
        return customer(name, username, "+267 71 000 001");
    }

    private String customer(String name, String username, String phone) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"" + phone + "\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(signedIn("admin", "password")))
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
        declare(username, policy);
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), username, "{\"method\":\"Orange Money\"}"))
                .andExpect(status().isOk());
        return policy;
    }

    private List<Map<String, Object>> messagesFor(String businessRef) throws Exception {
        String body = mockMvc.perform(get("/communications/messages").with(signedIn("service", "password")))
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

    /** Spec 01 BR-5: the OTP-verified declaration with all four consents, before the first premium. */
    private void declare(String user, String policy) throws Exception {
        String sent = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), user, "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(sent, "$.devCode");
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), user, "{\"code\":\"" + code
                        + "\",\"accuracy\":true,\"terms\":true,\"dataProtection\":true,\"falseInformation\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void providerCallbacksRepliesBulkNoticesAndRemindersFollowTheBlueprint() throws Exception {
        String ref = customer("Kagiso Rapula", "kagisor");
        String policy = buyAndPay("kagisor");
        outbox.dispatch();
        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
                .andExpect(status().isOk());
        Map<String, Object> issued = one(messagesFor(policy), "POLICY_ISSUED_EMAIL");
        assertThat(issued.get("status")).isEqualTo("ACCEPTED");
        String providerId = (String) issued.get("providerMessageId");

        // Status callbacks: signed only; states move forward; late or repeated callbacks change nothing.
        String read = "{\"providerMessageId\":\"" + providerId + "\",\"status\":\"READ\"}";
        mockMvc.perform(post("/integration/messaging/status").contentType(MediaType.APPLICATION_JSON).content(read))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/integration/messaging/status").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Nsure-Signature", "sha256=" + "0".repeat(64)).content(read))
                .andExpect(status().isForbidden());
        callback("status", read).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READ"));
        callback("status", "{\"providerMessageId\":\"" + providerId + "\",\"status\":\"DELIVERED\"}")
                .andExpect(jsonPath("$.status").value("READ"));
        callback("status", "{\"providerMessageId\":\"" + providerId + "\",\"status\":\"FAILED\"}")
                .andExpect(jsonPath("$.status").value("READ"));
        callback("status", "{\"providerMessageId\":\"EML-UNKNOWN\",\"status\":\"READ\"}")
                .andExpect(jsonPath("$.status").value("IGNORED"));
        assertThat((String) one(messagesFor(policy), "POLICY_ISSUED_EMAIL").get("readAt")).isNotBlank();

        // Inbound replies: a known sender opens a case; a reply naming that case joins it; a repeat is stored once.
        String first = callback("inbound", "{\"providerMessageId\":\"em-in-1\",\"channel\":\"EMAIL\","
                        + "\"from\":\"KagisoR@example.com\",\"text\":\"Please change my beneficiary.\"}")
                .andExpect(jsonPath("$.status").value("LINKED"))
                .andReturn().getResponse().getContentAsString();
        String caseRef = JsonPath.read(first, "$.caseRef");
        callback("inbound", "{\"providerMessageId\":\"em-in-2\",\"channel\":\"EMAIL\",\"from\":\"kagisor@example.com\","
                        + "\"text\":\"Re: " + caseRef + " here is the new name.\"}")
                .andExpect(jsonPath("$.caseRef").value(caseRef));
        callback("inbound", "{\"providerMessageId\":\"em-in-2\",\"channel\":\"EMAIL\",\"from\":\"kagisor@example.com\","
                        + "\"text\":\"Re: " + caseRef + " here is the new name.\"}")
                .andExpect(jsonPath("$.caseRef").value(caseRef));

        // An unknown sender waits in triage until a person says who it is.
        String unknown = callback("inbound", "{\"providerMessageId\":\"em-in-3\",\"channel\":\"EMAIL\","
                        + "\"from\":\"someone@elsewhere.example\",\"text\":\"Is this the claims line?\"}")
                .andExpect(jsonPath("$.status").value("TRIAGE"))
                .andReturn().getResponse().getContentAsString();
        // The unfiltered list ("All" in the UI) returns every inbound message.
        mockMvc.perform(get("/communications/inbound").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
        String triage = mockMvc.perform(get("/communications/inbound?status=TRIAGE").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Integer triageId = JsonPath.<List<Integer>>read(triage, "$[*].id").get(0);
        assertThat(JsonPath.read(unknown, "$.caseRef") == null).isTrue();
        mockMvc.perform(json(post("/communications/inbound/" + triageId + "/assign"), "ops", "{\"customerRef\":\"" + ref + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/communications/inbound/" + triageId + "/assign"), "service", "{\"customerRef\":\"" + ref + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LINKED"))
                .andExpect(jsonPath("$.handledBy").value("service"));
        mockMvc.perform(json(post("/communications/inbound/" + triageId + "/assign"), "service", "{\"customerRef\":\"" + ref + "\"}"))
                .andExpect(status().isConflict());

        // A bulk operational notice is requested by one person and released by another.
        String templates = mockMvc.perform(get("/communications/templates").with(signedIn("ops", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer notice = JsonPath.<List<Integer>>read(templates, "$[?(@.code == 'SERVICE_NOTICE_EMAIL')].id").get(0);
        String requested = mockMvc.perform(json(post("/communications/bulk-notices"), "admin", "{\"templateId\":" + notice
                        + ",\"policyStatus\":\"Active\",\"product\":\"Term Life\",\"reason\":\"Branch closed for the public holiday\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        String noticeRef = JsonPath.read(requested, "$.ref");
        assertThat((Integer) JsonPath.read(requested, "$.recipients")).isPositive();
        mockMvc.perform(post("/communications/bulk-notices/" + noticeRef + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/communications/bulk-notices/" + noticeRef + "/approve").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.decidedBy").value("ops"));
        assertThat(messagesFor(noticeRef)).isNotEmpty();
        assertThat(one(messagesFor(noticeRef).stream().filter(m -> ref.equals(m.get("customerRef"))).toList(),
                "SERVICE_NOTICE_EMAIL").get("body").toString()).contains("Dear Kagiso Rapula");

        // Scheduled premium reminders: one per policy and due date, however often the sweep runs.
        String due = java.time.LocalDate.now().plusDays(3).toString();
        jdbc.update("update ins_policy set next_premium_due = ? where record_ref = ?", java.sql.Date.valueOf(due), policy);
        mockMvc.perform(post("/communications/reminders/run").with(signedIn("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(post("/communications/reminders/run").with(signedIn("service", "password")))
                .andExpect(jsonPath("$.published").value(0));
        outbox.dispatch();
        assertThat(one(messagesFor(policy + "@" + due), "PREMIUM_DUE_EMAIL").get("subject").toString())
                .contains(due, policy);
    }

    /** A provider callback signed with this installation's webhook secret. */
    private org.springframework.test.web.servlet.ResultActions callback(String path, String body) throws Exception {
        return mockMvc.perform(post("/integration/messaging/" + path).contentType(MediaType.APPLICATION_JSON)
                .header("X-Nsure-Signature", "sha256=" + webhooks.hmac(body)).content(body));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = List.of("admin", "service", "ops", "customer").contains(user) ? "password" : "password1";
        return request.with(signedIn(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
