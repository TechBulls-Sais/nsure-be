package com.nsure.communication;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
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
import com.nsure.support.ModuleTestUsers;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * SH09B delivery rules (work package e): transient errors are retried with a bounded backoff and permanent ones go
 * straight to the failed queue (NS-SH09B-005); a customer not reached gets a fallback only on a channel the purpose
 * allows, with consent and a verified contact, and every decision is logged (AT-SH09B-02); the event-to-recipient
 * matrix decides who an event messages (NS-SH09B-001); SMS, purpose-level choices with consent evidence and the
 * WhatsApp provider's template approval (BP-COMMS). Its own database, because it changes shared templates and rules.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-communication-rules;DB_CLOSE_DELAY=-1",
        "nsure.communications.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class CommunicationDeliveryRulesApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxDispatcher outbox;

    @Autowired
    private CommunicationEventHandler handler;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void provisionModuleUsers() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aTransientErrorIsRetriedWithABoundedBackoffThenFallsBack() throws Exception {
        customer("Sello Busy", "sellobusy", "+267 71 200 301");
        String policy = buyAndPay("sellobusy");
        outbox.dispatch();
        Map<String, Object> email = template(messagesFor(policy), "POLICY_ISSUED_EMAIL");
        String ref = (String) email.get("ref");

        dispatch();
        email = byRef(messagesFor(policy), ref);
        assertThat(email.get("status")).isEqualTo("QUEUED");
        assertThat(email.get("attempts")).isEqualTo(1);
        assertThat(email.get("failureKind")).isEqualTo("TRANSIENT");
        LocalDateTime first = LocalDateTime.parse((String) email.get("nextAttemptAt"));
        assertThat(first).isAfter(LocalDateTime.now().plusSeconds(20));
        // Waiting out its backoff, the message is not tried again on the next run.
        dispatch();
        assertThat(byRef(messagesFor(policy), ref).get("attempts")).isEqualTo(1);

        due(ref);
        dispatch();
        email = byRef(messagesFor(policy), ref);
        assertThat(email.get("attempts")).isEqualTo(2);
        LocalDateTime second = LocalDateTime.parse((String) email.get("nextAttemptAt"));
        // The wait doubles: about 60 s after the second attempt against 30 s after the first.
        assertThat(second).isAfter(LocalDateTime.now().plusSeconds(50));

        due(ref);
        dispatch();
        email = byRef(messagesFor(policy), ref);
        assertThat(email.get("status")).isEqualTo("FAILED");
        assertThat((String) email.get("lastError")).contains("Transient", "attempt 3 of 3");
        // Policy issuance is contractual: the rule allows SMS to the verified mobile once Email has failed.
        String fallbackRef = (String) email.get("fallbackRef");
        assertThat(fallbackRef).startsWith("MSG-");
        Map<String, Object> sms = byRef(messagesFor(policy), fallbackRef);
        assertThat(sms.get("channel")).isEqualTo("SMS");
        assertThat(sms.get("status")).isEqualTo("QUEUED");
        assertThat(sms.get("fallbackOf")).isEqualTo(ref);
        assertThat(sms.get("contact")).isEqualTo("+*** ** *** 301");
        assertThat((String) sms.get("recipientBasis")).contains("Fallback for " + ref);
        mockMvc.perform(get("/communications/messages/" + email.get("id")).with(signedIn("ops", "password")))
                .andExpect(jsonPath("$.trail[*].detail").value(org.hamcrest.Matchers.hasItem(
                        containsString("Fallback to SMS (" + fallbackRef + ")"))));
        dispatch();
        assertThat(byRef(messagesFor(policy), fallbackRef).get("status")).isEqualTo("ACCEPTED");
        assertThat((String) byRef(messagesFor(policy), fallbackRef).get("providerMessageId")).startsWith("SMS-");

        // A timeout on the first attempt only: the second attempt is accepted.
        customer("Neo Slow", "neoslow", "+267 71 200 302");
        String slow = buyAndPay("neoslow");
        outbox.dispatch();
        String slowRef = (String) template(messagesFor(slow), "POLICY_ISSUED_EMAIL").get("ref");
        dispatch();
        assertThat(byRef(messagesFor(slow), slowRef).get("status")).isEqualTo("QUEUED");
        due(slowRef);
        dispatch();
        assertThat(byRef(messagesFor(slow), slowRef).get("status")).isEqualTo("ACCEPTED");
        assertThat(byRef(messagesFor(slow), slowRef).get("fallbackRef")).isEqualTo("");
    }

    @Test
    void anOptOutFallsBackOnlyWhereThePurposeAllowsAndEveryDecisionIsLogged() throws Exception {
        String ref = customer("Thato Optout", "thatooptout", "+267 71 200 303");
        mockMvc.perform(json(put("/communications/preferences/" + ref), "service",
                        "{\"channel\":\"EMAIL\",\"status\":\"OPTED_OUT\",\"consentSource\":\"PHONE\","
                                + "\"evidence\":\"Call 2026-11-20, customer asked for no email\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[2].channel").value("SMS"))
                .andExpect(jsonPath("$.channels[2].deliverable").value(true));
        String policy = buyAndPay("thatooptout");
        outbox.dispatch();
        List<Map<String, Object>> messages = messagesFor(policy);
        Map<String, Object> email = template(messages, "POLICY_ISSUED_EMAIL");
        Map<String, Object> whatsapp = template(messages, "POLICY_ISSUED_WHATSAPP");
        assertThat(email.get("status")).isEqualTo("SUPPRESSED");
        assertThat(whatsapp.get("status")).isEqualTo("SUPPRESSED");
        // Neither template reaches the customer, so the contractual issue notice falls back to SMS once, logged.
        List<Map<String, Object>> fallbacks = messages.stream().filter(m -> !"".equals(m.get("fallbackOf"))).toList();
        assertThat(fallbacks).hasSize(1);
        assertThat(fallbacks.get(0).get("channel")).isEqualTo("SMS");
        assertThat(fallbacks.get(0).get("fallbackOf")).isEqualTo(email.get("ref"));
        assertThat(trail(whatsapp)).anyMatch(d -> d.startsWith("No fallback needed: the customer is reached by SMS"));
        // A payment confirmation is not contractual: its rule allows no fallback, and the trail says so.
        Map<String, Object> receipt = template(messages, "PREMIUM_RECEIVED_EMAIL");
        assertThat(receipt.get("status")).isEqualTo("SUPPRESSED");
        assertThat(trail(receipt)).anyMatch(d -> d.contains("No fallback: the rule for Payment confirmation"));
        // Replaying the events creates no second fallback.
        int count = messagesFor(policy).size();
        replay(policy);
        assertThat(messagesFor(policy)).hasSize(count);

        // Purpose choices: a contractual notice cannot be stopped; reminders can; marketing needs consent evidence.
        mockMvc.perform(json(put("/communications/preferences/" + ref + "/purposes"), "service",
                        "{\"purpose\":\"POLICY_ISSUANCE\",\"channel\":\"SMS\",\"status\":\"OPTED_OUT\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("not stop them")));
        mockMvc.perform(json(put("/communications/preferences/" + ref + "/purposes"), "service",
                        "{\"purpose\":\"PREMIUM_REMINDER\",\"channel\":\"ALL\",\"status\":\"OPTED_OUT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[?(@.purpose == 'PREMIUM_REMINDER')].status").value("OPTED_OUT"));
        mockMvc.perform(json(put("/communications/preferences/" + ref + "/purposes"), "service",
                        "{\"purpose\":\"MARKETING\",\"channel\":\"EMAIL\",\"status\":\"OPTED_IN\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/communications/preferences/" + ref + "/purposes"), "service",
                        "{\"purpose\":\"MARKETING\",\"channel\":\"EMAIL\",\"status\":\"OPTED_IN\","
                                + "\"consentSource\":\"SIGNED_FORM\",\"evidence\":\"Consent form CF-0042 signed in branch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[?(@.purpose == 'MARKETING')].status").value("OPTED_IN"))
                .andExpect(jsonPath("$.purposes[?(@.purpose == 'MARKETING')].choices[0].consentSource")
                        .value("SIGNED_FORM"));
        mockMvc.perform(json(put("/communications/preferences/" + ref + "/purposes"), "ops",
                        "{\"purpose\":\"PREMIUM_REMINDER\",\"channel\":\"ALL\",\"status\":\"OPTED_IN\"}"))
                .andExpect(status().isForbidden());
        // The reminder opt-out applies to a reminder event: suppressed for that purpose, and its rule's SMS fallback
        // is refused for the same reason.
        handle(Events.PREMIUM_DUE_REMINDER, policy, Map.of("policyRef", policy, "customerRef", ref,
                "customerName", "Thato Optout", "product", "Term Life", "amount", "BWP 150.00", "dueDate", "2026-12-01"));
        Map<String, Object> reminder = template(messagesFor(policy), "PREMIUM_DUE_EMAIL");
        assertThat(reminder.get("status")).isEqualTo("SUPPRESSED");
        assertThat(trail(reminder)).anyMatch(d -> d.startsWith("No fallback possible: SMS: Customer opted out of premium"
                + " reminder messages."));
        // Every choice is history with its consent evidence (NS-SH01-005, NS-SH01-006).
        mockMvc.perform(get("/communications/preferences/" + ref + "/history").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.purpose == 'MARKETING')].evidence")
                        .value(org.hamcrest.Matchers.hasItem("Consent form CF-0042 signed in branch")))
                .andExpect(jsonPath("$[?(@.channel == 'EMAIL' && @.purpose == '')].consentSource")
                        .value(org.hamcrest.Matchers.hasItem("PHONE")));
    }

    @Test
    void theRulesAreConfigurationAndTheMatrixDecidesWhoAnEventMessages() throws Exception {
        mockMvc.perform(get("/communications/rules").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[?(@.purpose == 'CLAIM_PROGRESS')].fallbackChannels[0]").value("EMAIL"))
                .andExpect(jsonPath("$.recipients[?(@.eventType == 'EvidenceRequested' && @.recipientRole == "
                        + "'INTERMEDIARY')].enabled").value(false));
        mockMvc.perform(json(put("/communications/rules/purposes/SERVICE_UPDATE"), "service",
                        "{\"fallbackChannels\":[\"WHATSAPP\"],\"reason\":\"Try WhatsApp\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Email or SMS")));
        mockMvc.perform(json(put("/communications/rules/purposes/SERVICE_UPDATE"), "ops",
                        "{\"fallbackChannels\":[\"SMS\"],\"reason\":\"Reach customers by SMS\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(put("/communications/rules/purposes/SERVICE_UPDATE"), "service",
                        "{\"fallbackChannels\":[\"EMAIL\",\"SMS\"],\"reason\":\"Reach customers by SMS too\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackChannels[1]").value("SMS"))
                .andExpect(jsonPath("$.updatedBy").value("service"));

        // CommissionPaid messages the intermediary the statement is for, with its own statement amount.
        agent("INT-COM01", "Mmegi Brokers", "mmegi@example.com");
        handle(Events.COMMISSION_PAID, "CST-00091", Map.of("statementRef", "CST-00091", "intermediaryRef", "INT-COM01",
                "amount", "BWP 1,250.00"));
        Map<String, Object> statement = template(messagesFor("CST-00091"), "COMMISSION_STATEMENT_EMAIL");
        assertThat(statement.get("recipientRole")).isEqualTo("INTERMEDIARY");
        assertThat(statement.get("status")).isEqualTo("QUEUED");
        assertThat((String) statement.get("body")).contains("Mmegi Brokers", "CST-00091", "BWP 1,250.00");
        assertThat((String) statement.get("recipientBasis")).contains("Intermediary named on CommissionPaid");

        // LoanDisbursed messages the lender with references only: never the borrower's name.
        handle(Events.LOAN_DISBURSED, "LN-000777", Map.of("loanRef", "LN-000777", "lenderRef", "LND-0001",
                "lenderLoanId", "LET-55-01", "policyRef", "", "customerName", "Borrower Hidden",
                "disbursementDate", "2026-11-01"));
        Map<String, Object> lender = template(messagesFor("LN-000777"), "LOAN_COVERED_LENDER_EMAIL");
        assertThat(lender.get("recipientRole")).isEqualTo("LENDER");
        assertThat(lender.get("contact")).isEqualTo("in***@letshego.example");
        assertThat((String) lender.get("body")).contains("Letshego", "LET-55-01", "LN-000777")
                .doesNotContain("Borrower Hidden");

        // EvidenceRequested tells the applicant evidence is needed, never what it is (it may be medical).
        String ref = customer("Kopano Evidence", "kopanoev", "+267 71 200 304");
        handle(Events.EVIDENCE_REQUESTED, "UWC-0099", Map.of("caseRef", "UWC-0099", "applicationRef", "APP-0099",
                "customerRef", ref, "requirement", "HIV test", "dueDate", "2026-12-15"));
        Map<String, Object> evidence = template(messagesFor("UWC-0099"), "EVIDENCE_REQUESTED_EMAIL");
        assertThat(evidence.get("purpose")).isEqualTo("EVIDENCE_REQUEST");
        assertThat((String) evidence.get("body")).contains("Kopano Evidence", "APP-0099", "2026-12-15")
                .doesNotContain("HIV");

        // Switching the intermediary off for CommissionPaid: the next statement messages nobody.
        mockMvc.perform(json(put("/communications/rules/recipients"), "service",
                        "{\"eventType\":\"CommissionPaid\",\"recipientRole\":\"INTERMEDIARY\",\"enabled\":false,"
                                + "\"reason\":\"Statements go by portal only\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        handle(Events.COMMISSION_PAID, "CST-00092", Map.of("statementRef", "CST-00092", "intermediaryRef", "INT-COM01",
                "amount", "BWP 10.00"));
        assertThat(messagesFor("CST-00092")).isEmpty();
    }

    @Test
    void aWhatsAppTemplateIsUsedOnlyOnceTheWhatsAppProviderApprovesIt() throws Exception {
        String body = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"CLAIM_RECEIVED_WHATSAPP\",\"eventType\":\"ClaimReceived\",\"channel\":\"WHATSAPP\","
                                + "\"body\":\"Hi {{customerName}}, we received claim {{claimRef}}. Follow it at {{portalLink}}\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerStatus").value("NOT_SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        Number id = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/communications/templates/" + id + "/approve").with(signedIn("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        String ref = customer("Wame Whatsapp", "wamewa", "+267 71 200 305");
        mockMvc.perform(json(put("/communications/preferences/" + ref), "service",
                        "{\"channel\":\"WHATSAPP\",\"status\":\"OPTED_IN\",\"contact\":\"+267 71 200 305\"}"))
                .andExpect(status().isOk());
        handle(Events.CLAIM_RECEIVED, "CLM-WA-1", Map.of("claimRef", "CLM-WA-1", "customerRef", ref,
                "customerName", "Wame Whatsapp", "policyRef", "POL-X", "product", "Term Life", "amount", "BWP 10"));
        Map<String, Object> first = template(messagesFor("CLM-WA-1"), "CLAIM_RECEIVED_WHATSAPP");
        assertThat(first.get("status")).isEqualTo("SUPPRESSED");
        assertThat((String) first.get("lastError")).contains("not approved by the WhatsApp provider");

        mockMvc.perform(post("/communications/templates/" + id + "/provider-submission").with(signedIn("ops", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/communications/templates/" + id + "/provider-submission")
                        .with(signedIn("service", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerStatus").value("SUBMITTED"));
        mockMvc.perform(post("/communications/templates/" + id + "/provider-submission")
                        .with(signedIn("service", "password")))
                .andExpect(status().isUnprocessableEntity());
        dispatch();
        mockMvc.perform(get("/communications/templates").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].providerStatus").value("APPROVED"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].providerNote")
                        .value(org.hamcrest.Matchers.hasItem(containsString("simulated WhatsApp provider"))));
        handle(Events.CLAIM_RECEIVED, "CLM-WA-2", Map.of("claimRef", "CLM-WA-2", "customerRef", ref,
                "customerName", "Wame Whatsapp", "policyRef", "POL-X", "product", "Term Life", "amount", "BWP 10"));
        assertThat(template(messagesFor("CLM-WA-2"), "CLAIM_RECEIVED_WHATSAPP").get("status")).isEqualTo("QUEUED");

        // The simulated provider refuses a plain http link.
        String refused = mockMvc.perform(json(post("/communications/templates"), "service",
                        "{\"code\":\"CLAIM_PAID_WHATSAPP\",\"eventType\":\"ClaimPaid\",\"channel\":\"WHATSAPP\","
                                + "\"body\":\"Claim {{claimRef}} paid, see http://example.test\"}"))
                .andReturn().getResponse().getContentAsString();
        Number refusedId = JsonPath.read(refused, "$.id");
        mockMvc.perform(post("/communications/templates/" + refusedId + "/provider-submission")
                .with(signedIn("service", "password"))).andExpect(status().isOk());
        dispatch();
        mockMvc.perform(get("/communications/templates").with(signedIn("ops", "password")))
                .andExpect(jsonPath("$[?(@.id == " + refusedId + ")].providerStatus").value("REJECTED"));

        // SMS is a channel of its own: a manual SMS goes to the verified mobile; an SMS keeps to 480 characters.
        mockMvc.perform(json(post("/communications/messages"), "service",
                        "{\"customerRef\":\"" + ref + "\",\"channel\":\"SMS\",\"body\":\"Your documents are ready.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.channel").value("SMS"));
        mockMvc.perform(json(post("/communications/messages"), "service",
                        "{\"customerRef\":\"" + ref + "\",\"channel\":\"SMS\",\"body\":\"" + "x".repeat(481) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aCustomerManagesTheirOwnChoicesInThePortal() throws Exception {
        String ref = customer("Pako Portal", "pakoportal", "+267 71 200 306");
        mockMvc.perform(get("/portfolio/shop/preferences").with(signedIn("pakoportal", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerRef").value(ref));
        mockMvc.perform(json(put("/portfolio/shop/preferences"), "pakoportal",
                        "{\"channel\":\"WHATSAPP\",\"status\":\"OPTED_IN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.channels[1].status").value("OPTED_IN"))
                .andExpect(jsonPath("$.channels[1].verified").value("Y"));
        mockMvc.perform(json(put("/portfolio/shop/preferences/purposes"), "pakoportal",
                        "{\"purpose\":\"MARKETING\",\"channel\":\"EMAIL\",\"status\":\"OPTED_IN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purposes[?(@.purpose == 'MARKETING')].choices[0].consentSource")
                        .value(org.hamcrest.Matchers.hasItem("PORTAL")));
        mockMvc.perform(json(put("/portfolio/shop/preferences/purposes"), "pakoportal",
                        "{\"purpose\":\"CLAIM_PROGRESS\",\"channel\":\"ALL\",\"status\":\"OPTED_OUT\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/portfolio/shop/preferences").with(signedIn("service", "password")))
                .andExpect(status().isForbidden());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private void dispatch() throws Exception {
        mockMvc.perform(post("/communications/dispatch").with(signedIn("service", "password")))
                .andExpect(status().isOk());
    }

    /** Ends the message's backoff wait, as if the time had passed. */
    private void due(String messageRef) {
        jdbc.update("update com_message set next_attempt_at = ? where record_ref = ?",
                Timestamp.valueOf(LocalDateTime.now().minusSeconds(1)), messageRef);
    }

    private void handle(String type, String aggregate, Map<String, Object> payload) {
        DomainEvent event = new DomainEvent(UUID.randomUUID().toString(), type, aggregate, UUID.randomUUID().toString(),
                LocalDateTime.now(), mapper.valueToTree(payload));
        tx.executeWithoutResult(status -> handler.handle(event));
    }

    private void replay(String policy) throws Exception {
        for (Map<String, Object> row : jdbc.queryForList("select event_id, event_type, aggregate_ref, correlation_id,"
                + " payload from int_outbox_event where aggregate_ref = ?", policy)) {
            String type = (String) row.get("event_type");
            if (handler.eventTypes().contains(type)) {
                DomainEvent event = new DomainEvent((String) row.get("event_id"), type, policy,
                        (String) row.get("correlation_id"), LocalDateTime.now(), mapper.readTree((String) row.get("payload")));
                tx.executeWithoutResult(status -> handler.handle(event));
            }
        }
    }

    private List<String> trail(Map<String, Object> message) throws Exception {
        String body = mockMvc.perform(get("/communications/messages/" + message.get("id"))
                        .with(signedIn("ops", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.trail[*].detail");
    }

    private void agent(String ref, String name, String email) {
        jdbc.update("insert into dist_intermediary (record_ref, name, intermediary_type, licence_number, status,"
                + " appointment_expiry, email, mobile, created_by, created_at, updated_by, updated_at)"
                + " values (?, ?, 'BROKER', ?, 'ACTIVE', ?, ?, '+267 72 555 100', 'test', ?, 'test', ?)", ref, name,
                "LIC-" + ref, java.sql.Date.valueOf(java.time.LocalDate.now().plusYears(1)), email,
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
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

    private String buyAndPay(String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/shop"), username,
                        "{\"product\":\"Term Life\",\"tier\":\"Silver\",\"beneficiaries\":[{\"fullName\":\"Lesego Moremi\","
                                + "\"relationship\":\"Spouse\",\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(body, "$.requests[0].id");
        String sent = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), username, "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(sent, "$.devCode");
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), username, "{\"code\":\"" + code
                        + "\",\"accuracy\":true,\"terms\":true,\"dataProtection\":true,\"falseInformation\":true}"))
                .andExpect(status().isOk());
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

    private static Map<String, Object> template(List<Map<String, Object>> messages, String code) {
        List<Map<String, Object>> matching = messages.stream()
                .filter(m -> code.equals(m.get("templateCode")) && "".equals(m.get("fallbackOf"))).toList();
        assertThat(matching).as("messages for " + code).hasSize(1);
        return matching.get(0);
    }

    private static Map<String, Object> byRef(List<Map<String, Object>> messages, String ref) {
        return messages.stream().filter(m -> ref.equals(m.get("ref"))).findFirst().orElseThrow();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = List.of("admin", "service", "ops", "customer").contains(user) ? "password" : "password1";
        return request.with(signedIn(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
