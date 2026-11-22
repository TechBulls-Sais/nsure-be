package com.nsure.collections;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.support.ModuleTestUsers;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
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
 * NS-SH04-002: a debit-order collection run is prepared by one person, approved by another and sent; submission is
 * not settlement. The simulated bank's answer collects (receipt, allocation, ReceiptConfirmed), fails (instalment
 * stays owed) or leaves it pending; a later bank reversal reverses the receipt and the transaction is REVERSED.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-collection-runs;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class CollectionRunsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void anApprovedDebitOrderRunCollectsFailsOrHoldsAndABankReversalReversesTheReceipt() throws Exception {
        String paid = dueSoon(paidPolicy("Neo Debit", "+267 71 600 001"));
        String unpaid = dueSoon(paidPolicy("Kago Bounce", "+267 71 600 002"));
        String raced = dueSoon(paidPolicy("Mpho Raced", "+267 71 600 003"));
        mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password"))).andExpect(status().isOk());

        mandate(paid, "{\"channel\":\"DEBIT_ORDER\",\"institution\":\"First National Bank Botswana\","
                + "\"accountNumber\":\"62001234567\",\"debitDay\":5}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountMasked").value("****4567"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        mandate(unpaid, "{\"channel\":\"DEBIT_ORDER\",\"institution\":\"Absa Botswana\",\"accountNumber\":\"1000222299\"}")
                .andExpect(status().isUnprocessableEntity());
        mandate(unpaid, "{\"channel\":\"DEBIT_ORDER\",\"institution\":\"Absa Botswana\",\"accountNumber\":\"1000222299\","
                + "\"debitDay\":25}").andExpect(status().isOk());
        mandate(raced, "{\"channel\":\"MOBILE_WALLET\",\"institution\":\"orange money\",\"accountNumber\":\"+26771600003\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institution").value("Orange Money"));

        // Prepared by Finance; nothing goes to the bank until a second person approves.
        String batch = mockMvc.perform(json(post("/collections/batches"), "finance", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andReturn().getResponse().getContentAsString();
        String batchRef = JsonPath.read(batch, "$.ref");
        String paidInstruction = instruction(batch, paid);
        String unpaidInstruction = instruction(batch, unpaid);
        String racedInstruction = instruction(batch, raced);
        mockMvc.perform(post("/collections/batches/" + batchRef + "/submit").with(signedIn("finance", "password")))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/collections/batches/" + batchRef + "/approve").with(signedIn("finance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/collections/batches/" + batchRef + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("admin"));

        // Submitted is not settlement: the instalment is still owed and the payment shows as unresolved.
        mockMvc.perform(post("/collections/batches/" + batchRef + "/submit").with(signedIn("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.fileRef").value("SIMFILE-" + batchRef));
        String account = account(paid);
        assertThat((String) JsonPath.read(account, "$.instalments[1].status")).isEqualTo("DUE");
        assertThat((List<String>) JsonPath.read(account, "$.unresolvedPayments"))
                .anyMatch(u -> u.contains(paidInstruction) && u.contains("submitted"));

        // Meanwhile the third policy is paid another way, so its collection cannot be applied.
        mockMvc.perform(json(post("/portfolio/policies/" + raced + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());

        String answered = mockMvc.perform(json(post("/collections/batches/" + batchRef + "/bank-response"), "finance",
                        "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(field(answered, paidInstruction, "status")).isEqualTo("CONFIRMED");
        String receipt = field(answered, paidInstruction, "receiptRef");
        assertThat(receipt).startsWith("INV-");
        assertThat(field(answered, unpaidInstruction, "status")).isEqualTo("FAILED");
        assertThat(field(answered, unpaidInstruction, "resultText")).contains("Insufficient funds");
        assertThat(field(answered, racedInstruction, "status")).isEqualTo("CONFIRMED");
        assertThat(field(answered, racedInstruction, "resultText")).contains("held in suspense");

        account = account(paid);
        assertThat((String) JsonPath.read(account, "$.instalments[1].status")).isEqualTo("PAID");
        assertThat((String) JsonPath.read(account, "$.receipts[0].receiptRef")).isEqualTo(receipt);
        assertThat((String) JsonPath.read(account, "$.instructions[0].status")).isEqualTo("CONFIRMED");
        assertThat((String) JsonPath.read(account(unpaid), "$.instalments[1].status")).isEqualTo("DUE");
        assertThat(transactionStatus(paid, "DEBIT_ORDER_SIM")).isEqualTo("SETTLED");
        assertThat(eventCount("ReceiptConfirmed", "%DEBIT_ORDER_SIM%\"policyRef\":\"" + paid + "\"%")).isEqualTo(1L);
        String suspense = mockMvc.perform(get("/finance/suspense").with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(suspense, "$[?(@.policyRef == '" + raced + "')].status"))
                .containsExactly("SUSPENSE");

        // The bank reverses the confirmed debit: the receipt is reversed and the transaction is REVERSED.
        mockMvc.perform(json(post("/collections/instructions/" + unpaidInstruction + "/bank-return"), "finance",
                        "{\"kind\":\"REVERSED\",\"reason\":\"Payer disputed the debit\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/collections/instructions/" + paidInstruction + "/bank-return"), "finance",
                        "{\"kind\":\"REVERSED\",\"reason\":\"Payer disputed the debit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.reversalRef").value(org.hamcrest.Matchers.startsWith("REV-")));
        account = account(paid);
        assertThat((String) JsonPath.read(account, "$.receipts[0].status")).isEqualTo("REVERSED");
        assertThat((String) JsonPath.read(account, "$.instalments[1].status")).isEqualTo("DUE");
        assertThat(transactionStatus(paid, "DEBIT_ORDER_SIM")).isEqualTo("REVERSED");
        assertThat(eventCount("PaymentReturned", "%\"receiptRef\":\"" + receipt + "\"%")).isEqualTo(1L);

        // A failed collection is tried again under a new instruction; references are never reused.
        String retry = mockMvc.perform(json(post("/collections/batches"), "admin", "{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String again = instruction(retry, unpaid);
        assertThat(again).isNotEqualTo(unpaidInstruction);
        // Whoever prepared a batch cannot approve it, whatever roles they hold.
        mockMvc.perform(post("/collections/batches/" + JsonPath.read(retry, "$.ref") + "/approve")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aPendingCollectionWaitsForTheNextResponseAndAMandateNeedsFinance() throws Exception {
        String policy = dueSoon(paidPolicy("Lorato Pending", "+267 71 600 004"));
        mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password"))).andExpect(status().isOk());
        mandate(policy, "{\"channel\":\"DEBIT_ORDER\",\"institution\":\"Stanbic\",\"accountNumber\":\"5550001198\","
                + "\"debitDay\":1}").andExpect(status().isOk());
        mockMvc.perform(json(post("/collections/mandates"), "distribution", "{\"policyRef\":\"" + policy + "\"}"))
                .andExpect(status().isForbidden());
        String batch = mockMvc.perform(json(post("/collections/batches"), "finance", "{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(batch, "$.ref");
        String instruction = instruction(batch, policy);
        mockMvc.perform(post("/collections/batches/" + ref + "/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/collections/batches/" + ref + "/submit").with(signedIn("finance", "password")))
                .andExpect(status().isOk());
        String first = mockMvc.perform(json(post("/collections/batches/" + ref + "/bank-response"), "finance", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(field(first, instruction, "status")).isEqualTo("PENDING");
        // The operator records the bank's later answer (a simulated override).
        String later = mockMvc.perform(json(post("/collections/batches/" + ref + "/bank-response"), "finance",
                        "{\"outcomes\":[{\"instructionRef\":\"" + instruction + "\",\"outcome\":\"CONFIRMED\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(field(later, instruction, "status")).isEqualTo("CONFIRMED");
        assertThat((String) JsonPath.read(account(policy), "$.instalments[1].status")).isEqualTo("PAID");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions mandate(String policy, String body) throws Exception {
        return mockMvc.perform(json(post("/collections/mandates"), "finance",
                "{\"policyRef\":\"" + policy + "\"," + body.substring(1)));
    }

    private static String instruction(String batch, String policy) {
        List<String> refs = JsonPath.read(batch, "$.instructions[?(@.policyRef == '" + policy + "')].ref");
        assertThat(refs).hasSize(1);
        return refs.get(0);
    }

    private static String field(String batch, String instruction, String field) {
        List<Object> values = JsonPath.read(batch, "$.instructions[?(@.ref == '" + instruction + "')]." + field);
        return values.isEmpty() || values.get(0) == null ? null : values.get(0).toString();
    }

    /** Moves the next premium to three days from now, so the billing run issues it and a run collects it. */
    private String dueSoon(String policy) {
        LocalDate due = LocalDate.now().plusDays(3);
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.nextPremiumDue = :due,"
                + " p.gracePeriodEnd = :grace where p.ref = :ref").setParameter("due", due)
                .setParameter("grace", due.plusDays(30)).setParameter("ref", policy).executeUpdate());
        return policy;
    }

    private String transactionStatus(String policy, String provider) {
        return tx.execute(s -> em.createQuery("select t.status from PaymentTransaction t where t.policyRef = :ref"
                        + " and t.provider = :provider", String.class)
                .setParameter("ref", policy).setParameter("provider", provider).getSingleResult());
    }

    private long eventCount(String type, String like) {
        return tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType = :type"
                + " and e.payload like :like", Long.class).setParameter("type", type).setParameter("like", like)
                .getSingleResult());
    }

    private String paidPolicy(String name, String phone) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                                "{\"name\":\"" + name + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\","
                                        + "\"phone\":\"" + phone + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\""
                                + customer + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        return policy;
    }

    private String account(String policy) throws Exception {
        return mockMvc.perform(get("/collections/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
