package com.nsure.collections;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nsure.integration.OutboxDispatcher;
import com.nsure.support.ModuleTestUsers;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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

/** Collections (SH04): due schedule, reversals with a cover review, split suspense and statement reconciliation. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-collections;DB_CLOSE_DELAY=-1",
        "nsure.integration.dispatch-delay-ms=3600000"})
@AutoConfigureMockMvc
class CollectionsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private OutboxDispatcher outbox;

    @BeforeEach
    void users() {
        ModuleTestUsers.provision(jdbc);
    }

    @Test
    void aCollectionSettlesItsInstalmentAndTheBillingRunIssuesTheNextOneOnce() throws Exception {
        String policy = paidPolicy("Thato Schedule", "+267 71 500 001");
        String account = account(policy);
        BigDecimal premium = amount(account, "$.instalments[0].billed");
        assertThat((String) JsonPath.read(account, "$.instalments[0].status")).isEqualTo("PAID");
        assertThat(amount(account, "$.instalments[0].allocated")).isEqualByComparingTo(premium);
        assertThat(amount(account, "$.instalments[0].outstanding")).isZero();
        assertThat((String) JsonPath.read(account, "$.paidTo")).isEqualTo(JsonPath.read(account, "$.instalments[0].periodEnd"));
        assertThat(amount(account, "$.arrears")).isZero();
        assertThat((String) JsonPath.read(account, "$.receipts[0].status")).isEqualTo("SUCCESSFUL");

        // The next premium fell due five days ago: the run issues it once, and it shows as arrears.
        LocalDate due = LocalDate.now().minusDays(5);
        tx.executeWithoutResult(s -> em.createQuery("update InsurancePolicy p set p.nextPremiumDue = :due"
                + " where p.ref = :ref").setParameter("due", due).setParameter("ref", policy).executeUpdate());
        mockMvc.perform(post("/collections/billing-run").with(signedIn("ops", "password")))
                .andExpect(status().isForbidden());
        String run = mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(run, "$.issued")).isGreaterThanOrEqualTo(1);
        mockMvc.perform(post("/collections/billing-run").with(signedIn("finance", "password")))
                .andExpect(jsonPath("$.instalments").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem((String) JsonPath.read(run, "$.instalments[0]")))));
        account = account(policy);
        assertThat((List<?>) JsonPath.read(account, "$.instalments")).hasSize(2);
        assertThat((List<?>) JsonPath.read(account, "$.instalments[?(@.status == 'DUE')]")).hasSize(1);
        assertThat(amount(account, "$.arrears")).isEqualByComparingTo(premium);
        assertThat((Integer) JsonPath.read(account, "$.overdueInstalments")).isEqualTo(1);
    }

    @Test
    void aReturnedReceiptReopensThePeriodTriggersACoverReviewAndReversesTheIncome() throws Exception {
        String policy = paidPolicy("Lesedi Returned", "+267 71 500 002");
        String account = account(policy);
        String receipt = JsonPath.read(account, "$.receipts[0].receiptRef");
        BigDecimal premium = amount(account, "$.receipts[0].amount");
        // The premium paid a period that began 40 days ago, so its grace is already over when it bounces.
        LocalDate start = LocalDate.now().minusDays(40);
        tx.executeWithoutResult(s -> {
            em.createQuery("update Instalment i set i.periodStart = :start, i.dueDate = :start where i.policyRef = :ref")
                    .setParameter("start", start).setParameter("ref", policy).executeUpdate();
            em.createQuery("update PremiumPayment p set p.periodStart = :start where p.policyRef = :ref")
                    .setParameter("start", start).setParameter("ref", policy).executeUpdate();
        });

        mockMvc.perform(json(post("/collections/receipts/" + receipt + "/reverse"), "finance",
                        "{\"kind\":\"RETURNED\",\"reason\":\"short\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/collections/receipts/" + receipt + "/reverse"), "ops",
                        "{\"kind\":\"RETURNED\",\"reason\":\"Debit returned: insufficient funds\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/collections/receipts/" + receipt + "/reverse"), "finance",
                        "{\"kind\":\"RETURNED\",\"reason\":\"Debit returned: insufficient funds\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyStatus").value("Active - No Cover"))
                .andExpect(jsonPath("$.ref").value(org.hamcrest.Matchers.startsWith("REV-")));
        mockMvc.perform(json(post("/collections/receipts/" + receipt + "/reverse"), "finance",
                        "{\"kind\":\"RETURNED\",\"reason\":\"Debit returned: insufficient funds\"}"))
                .andExpect(status().isConflict());

        account = account(policy);
        assertThat((String) JsonPath.read(account, "$.receipts[0].status")).isEqualTo("REVERSED");
        assertThat((String) JsonPath.read(account, "$.receipts[0].reversalKind")).isEqualTo("RETURNED");
        assertThat((String) JsonPath.read(account, "$.instalments[0].status")).isEqualTo("DUE");
        assertThat(amount(account, "$.instalments[0].outstanding")).isEqualByComparingTo(premium);
        mockMvc.perform(get("/portfolio/history/policy/" + policy).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.action == 'Cover Review')].details").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("cover ended"))));

        // The subledger reverses the premium income: Dr premium income, Cr bank.
        outbox.dispatch();
        String journals = mockMvc.perform(get("/finance/journals").param("eventType", "PaymentReturned")
                        .with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        String own = "$[?(@.sourceRef == '" + policy + "')]";
        assertThat(new BigDecimal(((List<?>) JsonPath.read(journals, own + ".amount")).get(0).toString()))
                .isEqualByComparingTo(premium);
        assertThat((List<String>) JsonPath.read(journals, own + ".lines[*].accountCode")).containsExactly("4000", "1000");

        // Paying again resumes cover; the period without cover is waived, not owed.
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        account = account(policy);
        assertThat((String) JsonPath.read(account, "$.instalments[0].status")).isEqualTo("WAIVED");
        assertThat((String) JsonPath.read(account, "$.instalments[1].status")).isEqualTo("PAID");
        assertThat(amount(account, "$.arrears")).isZero();
    }

    @Test
    void cashInSuspenseCanBeSplitAcrossPoliciesAndPartPayAnInstalment() throws Exception {
        String first = pendingPolicy("Kago Split", "+267 71 500 003");
        String second = pendingPolicy("Neo Split", "+267 71 500 004");
        BigDecimal premium = premium(first);
        BigDecimal part = new BigDecimal("20.00");
        Integer receipt = suspense("BF-SPLIT-1", premium.add(part));

        // More than the second policy needs is refused; so is more than the receipt holds.
        mockMvc.perform(json(post("/finance/suspense/" + receipt + "/clearance"), "finance", "{\"action\":\"ALLOCATE\","
                        + "\"reason\":\"Payer paid two policies at once\",\"allocations\":[{\"policyRef\":\"" + second
                        + "\",\"amount\":\"" + premium.add(BigDecimal.ONE) + "\"}]}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/finance/suspense/" + receipt + "/clearance"), "finance", "{\"action\":\"ALLOCATE\","
                        + "\"reason\":\"Payer paid two policies at once\",\"allocations\":[{\"policyRef\":\"" + first
                        + "\",\"amount\":\"" + premium + "\"},{\"policyRef\":\"" + second + "\",\"amount\":\"" + part + "\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/finance/suspense/" + receipt + "/clearance/approve").with(signedIn("secadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.unapplied").value(0));
        assertThat(policyStatus(first)).isEqualTo("Active");
        assertThat(policyStatus(second)).isEqualTo("Pending Payment");
        String account = account(second);
        assertThat((String) JsonPath.read(account, "$.instalments[0].status")).isEqualTo("PART_PAID");
        assertThat(amount(account, "$.instalments[0].outstanding")).isEqualByComparingTo(premium.subtract(part));

        // A later receipt for exactly the rest settles the instalment and starts cover.
        Integer rest = suspense("BF-SPLIT-2", premium.subtract(part));
        mockMvc.perform(json(post("/finance/suspense/" + rest + "/clearance"), "finance", "{\"action\":\"ALLOCATE\","
                        + "\"policyRef\":\"" + second + "\",\"reason\":\"The rest of the first premium\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/finance/suspense/" + rest + "/clearance/approve").with(signedIn("secadmin", "password")))
                .andExpect(jsonPath("$.status").value("APPLIED"));
        assertThat(policyStatus(second)).isEqualTo("Active");
        assertThat((String) JsonPath.read(account(second), "$.instalments[0].status")).isEqualTo("PAID");

        // Part of a receipt applied: the rest stays in suspense, unapplied, for another decision.
        String third = pendingPolicy("Mpho Split", "+267 71 500 005");
        Integer big = suspense("BF-SPLIT-3", new BigDecimal("500.00"));
        mockMvc.perform(json(post("/finance/suspense/" + big + "/clearance"), "finance", "{\"action\":\"ALLOCATE\","
                        + "\"reason\":\"Part belongs to this policy\",\"allocations\":[{\"policyRef\":\"" + third
                        + "\",\"amount\":\"10.00\"}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/finance/suspense/" + big + "/clearance/approve").with(signedIn("secadmin", "password")))
                .andExpect(jsonPath("$.status").value("SUSPENSE"))
                .andExpect(jsonPath("$.unapplied").value(490.0))
                .andExpect(jsonPath("$.clearanceAction").doesNotExist());
    }

    @Test
    void aStatementIsReconciledAndItsExceptionsStayOpenWithAnOwnerUntilResolved() throws Exception {
        suspense("ST-1", new BigDecimal("50.00"));
        suspense("ST-2", new BigDecimal("70.00"));
        suspense("ST-3", new BigDecimal("15.00"));
        String today = LocalDate.now().toString();
        String statement = mockMvc.perform(json(post("/collections/statements"), "finance", "{\"provider\":\"StatementBank\","
                        + "\"periodFrom\":\"" + today + "\",\"periodTo\":\"" + today + "\",\"lines\":["
                        + "{\"reference\":\"ST-1\",\"amount\":\"50.00\"},"
                        + "{\"reference\":\"ST-2\",\"amount\":\"75.00\"},"
                        + "{\"reference\":\"ST-9\",\"amount\":\"10.00\"},"
                        + "{\"reference\":\"ST-1\",\"amount\":\"50.00\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines").value(4))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.openExceptions").value(4))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(statement, "$.ref");
        String items = mockMvc.perform(get("/collections/statements/" + ref + "/items").with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(items, "$[*].status")).containsExactlyInAnyOrder("MATCHED",
                "AMOUNT_MISMATCH", "MISSING_RECEIPT", "DUPLICATE", "NOT_ON_STATEMENT");
        assertThat((List<String>) JsonPath.read(items, "$[?(@.status == 'NOT_ON_STATEMENT')].providerRef"))
                .containsExactly("ST-3");
        assertThat((List<Object>) JsonPath.read(items, "$[?(@.status == 'AMOUNT_MISMATCH')].expectedAmount"))
                .hasSize(1);

        String open = mockMvc.perform(get("/collections/reconciliation").with(signedIn("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer mismatch = JsonPath.<List<Integer>>read(open, "$[?(@.status == 'AMOUNT_MISMATCH')].id").get(0);
        assertThat((List<String>) JsonPath.read(open, "$[?(@.id == " + mismatch + ")].owner")).containsExactly("finance");
        mockMvc.perform(json(post("/collections/reconciliation/" + mismatch + "/assign"), "finance", "{\"owner\":\"admin\"}"))
                .andExpect(jsonPath("$.owner").value("admin"));
        mockMvc.perform(json(post("/collections/reconciliation/" + mismatch + "/resolve"), "finance",
                        "{\"resolution\":\"Bank charged a 5.00 fee; journal raised\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedBy").value("finance"));
        mockMvc.perform(json(post("/collections/reconciliation/" + mismatch + "/resolve"), "finance",
                        "{\"resolution\":\"Bank charged a 5.00 fee; journal raised\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/collections/reconciliation").with(signedIn("finance", "password")))
                .andExpect(jsonPath("$[?(@.id == " + mismatch + ")]").isEmpty());
        mockMvc.perform(get("/collections/reconciliation").with(signedIn("customer", "password")))
                .andExpect(status().isForbidden());
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private String paidPolicy(String name, String phone) throws Exception {
        String policy = pendingPolicy(name, phone);
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
        return policy;
    }

    private String pendingPolicy(String name, String phone) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin",
                                "{\"name\":\"" + name + "\",\"email\":\"" + phone.replaceAll("\\D", "") + "@example.com\","
                                        + "\"phone\":\"" + phone + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        return JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + customer
                                + "\",\"product\":\"Term Life\",\"amount\":20000,\"premium\":50}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("Pending Payment"))
                        .andReturn().getResponse().getContentAsString(), "$.id");
    }

    /** A bank receipt the system could not apply, so it waits in suspense; returns its id. */
    private Integer suspense(String providerRef, BigDecimal amount) throws Exception {
        String body = mockMvc.perform(json(post("/integration/payment-notifications"), "ops",
                        "{\"provider\":\"StatementBank\",\"providerReference\":\"" + providerRef
                                + "\",\"policyRef\":\"POL-UNKNOWN\",\"amount\":\"" + amount + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENSE"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String account(String policy) throws Exception {
        return mockMvc.perform(get("/collections/policies/" + policy).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private BigDecimal premium(String policy) {
        return tx.execute(s -> em.createQuery("select p.premium from InsurancePolicy p where p.ref = :ref",
                BigDecimal.class).setParameter("ref", policy).getSingleResult());
    }

    private String policyStatus(String policy) {
        return tx.execute(s -> em.createQuery("select p.status from InsurancePolicy p where p.ref = :ref",
                String.class).setParameter("ref", policy).getSingleResult());
    }

    private static BigDecimal amount(String json, String path) {
        return new BigDecimal(JsonPath.read(json, path).toString());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
