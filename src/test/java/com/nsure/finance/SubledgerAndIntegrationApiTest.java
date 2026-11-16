package com.nsure.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import com.nsure.support.ModuleTestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The event bus, the insurance subledger and inbound payment confirmations together: a confirmed payment activates
 * the policy, the outbox posts one balanced journal per event however often it is dispatched, a mismatched payment
 * lands in suspense, a missing posting rule becomes a visible exception, and period close lists open items.
 */
@SuppressWarnings("unchecked")
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-subledger;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SubledgerAndIntegrationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate moduleJdbc;

    @BeforeEach
    void provisionModuleUsers() {
        ModuleTestUsers.provision(moduleJdbc);
    }

    @Test
    void aConfirmedPaymentActivatesThePolicyAndPostsOnceHoweverOftenItIsDelivered() throws Exception {
        String[] policy = pendingPolicy("Naledi Sebina", "naledi");
        String ref = policy[0];
        String premium = policy[1];

        // NS-SH04-004: a notification the provider cannot confirm collects nothing; it waits in suspense.
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops", "{\"provider\":\"OrangeMoney\","
                        + "\"providerReference\":\"OM-0999\",\"policyRef\":\"" + ref + "\",\"amount\":\"" + premium + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENSE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no record of receiving OM-0999")));
        mockMvc.perform(json(post("/integration/simulator/provider-receipts"), "ops",
                        "{\"provider\":\"OrangeMoney\",\"providerReference\":\"OM-0998\",\"amount\":\"1.00\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops", "{\"provider\":\"OrangeMoney\","
                        + "\"providerReference\":\"OM-0998\",\"policyRef\":\"" + ref + "\",\"amount\":\"" + premium + "\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENSE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("confirmed BWP 1.00")));
        mockMvc.perform(get("/portfolio/policies").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + ref + "')].status").value("Pending Payment"));
        mockMvc.perform(json(post("/integration/simulator/provider-receipts"), "finance",
                        "{\"provider\":\"OrangeMoney\",\"providerReference\":\"OM-1001\",\"amount\":\"" + premium + "\"}"))
                .andExpect(status().isForbidden());

        // The provider confirms it holds the premium, so the confirmation collects it.
        mockMvc.perform(json(post("/integration/simulator/provider-receipts"), "ops",
                        "{\"provider\":\"OrangeMoney\",\"providerReference\":\"OM-1001\",\"amount\":\"" + premium + "\"}"))
                .andExpect(status().isOk());
        String notification = "{\"provider\":\"OrangeMoney\",\"providerReference\":\"OM-1001\",\"policyRef\":\"" + ref
                + "\",\"amount\":\"" + premium + "\",\"method\":\"Orange Money\"}";
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops", notification))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.resultRef").value(ref));
        // The same callback again returns the first result and collects nothing more (AT-SH12-01).
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops", notification))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));
        // The same reference with different details is a conflict, not a second payment.
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops", notification.replace(premium, "1.00")))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/integration/inbound").with(httpBasic("ops", "password")))
                .andExpect(jsonPath("$[?(@.providerRef == 'OM-1001')]").value(org.hamcrest.Matchers.hasSize(1)));

        dispatch();
        dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=PremiumReceived")
                        .with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String own = "$[?(@.sourceRef == '" + ref + "')]";
        List<Object> amounts = JsonPath.read(journals, own + ".amount");
        assertThat(amounts).hasSize(1);
        assertThat(new BigDecimal(amounts.get(0).toString())).isEqualByComparingTo(premium);
        assertThat((List<String>) JsonPath.read(journals, own + ".lines[*].accountCode")).containsExactly("1000", "4000");

        mockMvc.perform(get("/finance/trial-balance").with(httpBasic("finance", "password")))
                .andExpect(jsonPath("$.balanced").value(true));
        mockMvc.perform(get("/integration/events?eventType=PremiumReceived").with(httpBasic("ops", "password")))
                .andExpect(jsonPath("$[0].aggregateRef").value(ref))
                .andExpect(jsonPath("$[0].status").value("DELIVERED"));
    }

    @Test
    void cashThatCannotBeAppliedGoesToSuspenseNotPremium() throws Exception {
        String ref = pendingPolicy("Kagiso Ntwa", "kagiso")[0];
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops",
                        "{\"provider\":\"Bank\",\"providerReference\":\"FNB-77\",\"policyRef\":\"" + ref
                                + "\",\"amount\":\"3.00\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENSE"));
        mockMvc.perform(get("/portfolio/policies").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + ref + "')].status").value("Pending Payment"));
        dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=UnmatchedReceipt")
                        .with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(journals, "$[0].lines[*].accountCode")).containsExactly("1000", "2000");

        // Finance-only users cannot inject payments; customers cannot read the ledger.
        mockMvc.perform(json(post("/integration/payment-notifications"), "finance", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/finance/journals").with(httpBasic("customer", "password")))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMissingRuleRaisesAnExceptionThatPostsOnceTheRuleIsFixed() throws Exception {
        mockMvc.perform(json(put("/finance/posting-rules/PremiumReceived"), "finance",
                        "{\"debitAccount\":\"1000\",\"creditAccount\":\"4000\",\"domain\":\"INDIVIDUAL\","
                                + "\"status\":\"INACTIVE\",\"description\":\"Individual premium collected\","
                                + "\"reason\":\"Suspend premium postings\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING_APPROVAL"));
        mockMvc.perform(post("/finance/posting-rules/PremiumReceived/versions/2/approve")
                        .with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inForce").value(true));
        String[] policy = pendingPolicy("Boipelo Rre", "boipelo");
        mockMvc.perform(json(post("/portfolio/policies/" + policy[0] + "/payments"), "admin", "{\"method\":\"Card\"}"))
                .andExpect(status().isOk());
        dispatch();
        String exceptions = mockMvc.perform(get("/finance/exceptions").with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer id = ((List<Integer>) JsonPath.read(exceptions, "$[?(@.sourceRef == '" + policy[0] + "')].id")).get(0);
        mockMvc.perform(json(put("/finance/posting-rules/PremiumReceived"), "finance",
                        "{\"debitAccount\":\"1000\",\"creditAccount\":\"4000\",\"domain\":\"INDIVIDUAL\","
                                + "\"status\":\"ACTIVE\",\"description\":\"Individual premium collected\","
                                + "\"reason\":\"Resume premium postings\"}"))
                .andExpect(jsonPath("$.version").value(3));
        // Proposed but not yet approved, the fix changes nothing: the retry still fails.
        mockMvc.perform(post("/finance/exceptions/" + id + "/retry").with(httpBasic("finance", "password")))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/finance/posting-rules/PremiumReceived/versions/3/approve")
                        .with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        String resolved = mockMvc.perform(post("/finance/exceptions/" + id + "/retry").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.journalRef").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/finance/journals/" + JsonPath.read(resolved, "$.journalRef")).with(httpBasic("finance", "password")))
                .andExpect(jsonPath("$.ruleVersion").value(3));
        mockMvc.perform(post("/finance/exceptions/" + id + "/retry").with(httpBasic("finance", "password")))
                .andExpect(status().isConflict());
    }

    /**
     * NS-SH08-001, Blueprint §7: a rule change is a new version that a second person approves and that applies from
     * its own date. Journals already posted keep the version they used, and that version stays readable.
     */
    @Test
    void aRuleChangeIsANewApprovedVersionAndPostedJournalsKeepTheirs() throws Exception {
        String rules = mockMvc.perform(get("/finance/posting-rules").with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        String inForce = "$[?(@.eventType == 'UnmatchedReceipt' && @.inForce == true)]";
        Integer before = ((List<Integer>) JsonPath.read(rules, inForce + ".version")).get(0);
        Integer beforeId = ((List<Integer>) JsonPath.read(rules, inForce + ".id")).get(0);

        String first = unmatchedReceipt("FNB-301");
        mockMvc.perform(get("/finance/journals/" + first).with(httpBasic("finance", "password")))
                .andExpect(jsonPath("$.postingRuleId").value(beforeId))
                .andExpect(jsonPath("$.lines[1].accountCode").value("2000"));

        // A backdated version may not reach a day already posted under an earlier one.
        mockMvc.perform(json(put("/finance/posting-rules/UnmatchedReceipt"), "finance",
                        "{\"debitAccount\":\"1000\",\"creditAccount\":\"2100\",\"reason\":\"Backdate\",\"effectiveFrom\":\""
                                + java.time.LocalDate.now().minusDays(1) + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("already posted")));

        String tomorrow = java.time.LocalDate.now().plusDays(1).toString();
        String change = "{\"debitAccount\":\"1000\",\"creditAccount\":\"2100\",\"domain\":\"SHARED\",\"reason\":\"Test move\","
                + "\"description\":\"Cash received that could not be applied\",\"effectiveFrom\":\"" + tomorrow + "\","
                + "\"baseVersion\":" + before + "}";
        // A reason is required, and a stale base version is a conflict rather than an overwrite.
        mockMvc.perform(json(put("/finance/posting-rules/UnmatchedReceipt"), "finance", change.replace("\"reason\":\"Test move\",", "")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put("/finance/posting-rules/UnmatchedReceipt"), "finance",
                        change.replace("\"baseVersion\":" + before, "\"baseVersion\":" + (before - 1))))
                .andExpect(status().isConflict());
        mockMvc.perform(json(put("/finance/posting-rules/UnmatchedReceipt"), "finance", change))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(before + 1))
                .andExpect(jsonPath("$.approvalStatus").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.inForce").value(false));
        // One change in flight at a time; the proposer cannot approve it.
        mockMvc.perform(json(put("/finance/posting-rules/UnmatchedReceipt"), "finance", change))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/finance/posting-rules/UnmatchedReceipt/versions/" + (before + 1) + "/approve")
                        .with(httpBasic("finance", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/finance/posting-rules/UnmatchedReceipt/versions/" + (before + 1) + "/approve")
                        .with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
                .andExpect(jsonPath("$.effectiveFrom").value(tomorrow))
                .andExpect(jsonPath("$.inForce").value(false));

        // Today's events still post under the version in force today; the old version is kept, ending today.
        String second = unmatchedReceipt("FNB-302");
        mockMvc.perform(get("/finance/journals/" + second).with(httpBasic("finance", "password")))
                .andExpect(jsonPath("$.ruleVersion").value(before))
                .andExpect(jsonPath("$.postingRuleId").value(beforeId))
                .andExpect(jsonPath("$.lines[1].accountCode").value("2000"));
        rules = mockMvc.perform(get("/finance/posting-rules").with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        String old = "$[?(@.eventType == 'UnmatchedReceipt' && @.version == " + before + ")]";
        assertThat((List<String>) JsonPath.read(rules, old + ".creditAccount")).containsExactly("2000");
        assertThat((List<String>) JsonPath.read(rules, old + ".effectiveTo")).containsExactly(java.time.LocalDate.now().toString());
        assertThat((List<Boolean>) JsonPath.read(rules, old + ".inForce")).containsExactly(true);

        // The proposal and approval are in the tamper-evident evidence, with the before and after mapping.
        String evidence = mockMvc.perform(get("/audits/evidence").param("entityType", "PostingRule")
                        .param("entityRef", "UnmatchedReceipt v" + (before + 1)).with(httpBasic("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(evidence, "$[*].action"))
                .contains("Proposed posting rule", "Approved posting rule");
        assertThat((List<String>) JsonPath.read(evidence, "$[?(@.action == 'Proposed posting rule')].reason"))
                .anySatisfy(reason -> assertThat(reason).contains("Cr 2000").contains("Cr 2100"));
    }

    /** A payment the provider has no record of: suspense, and an UnmatchedReceipt journal. Returns its ref. */
    private String unmatchedReceipt(String reference) throws Exception {
        String ref = pendingPolicy("Unmatched " + reference, reference.toLowerCase().replace("-", ""))[0];
        mockMvc.perform(json(post("/integration/payment-notifications"), "ops",
                        "{\"provider\":\"Bank\",\"providerReference\":\"" + reference + "\",\"policyRef\":\"" + ref
                                + "\",\"amount\":\"3.00\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENSE"));
        dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=UnmatchedReceipt")
                        .with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        return ((List<String>) JsonPath.read(journals, "$[?(@.sourceRef == '" + reference + "')].ref")).get(0);
    }

    @Test
    void reversalExportAndPeriodClose() throws Exception {
        String[] policy = pendingPolicy("Tumelo Kgosi", "tumelo");
        mockMvc.perform(json(post("/portfolio/policies/" + policy[0] + "/payments"), "admin", "{\"method\":\"Card\"}"))
                .andExpect(status().isOk());
        dispatch();
        String journals = mockMvc.perform(get("/finance/journals?eventType=PremiumReceived")
                        .with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        String journal = ((List<String>) JsonPath.read(journals, "$[?(@.sourceRef == '" + policy[0] + "')].ref")).get(0);

        mockMvc.perform(json(post("/finance/journals/" + journal + "/reverse"), "finance", "{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/finance/journals/" + journal + "/reverse"), "finance",
                        "{\"reason\":\"Payment recalled by bank\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversesRef").value(journal))
                .andExpect(jsonPath("$.lines[0].accountCode").value("1000"))
                .andExpect(jsonPath("$.lines[0].credit").value(new BigDecimal(policy[1]).doubleValue()));
        mockMvc.perform(json(post("/finance/journals/" + journal + "/reverse"), "finance", "{\"reason\":\"again\"}"))
                .andExpect(status().isUnprocessableEntity());

        String period = YearMonth.now().toString();
        mockMvc.perform(json(post("/finance/periods/" + period + "/soft-close"), "finance", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOFT_CLOSED"));
        // Journals not yet in the GL block the close (AT-SH08-03).
        mockMvc.perform(json(post("/finance/periods/" + period + "/close"), "finance", "{}"))
                .andExpect(status().isConflict());

        // Blueprint §7: prepared → submitted → accepted or rejected → reconciled.
        String batch = mockMvc.perform(post("/finance/gl-batches").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PREPARED"))
                .andReturn().getResponse().getContentAsString();
        String batchRef = JsonPath.read(batch, "$.ref");
        mockMvc.perform(json(post("/finance/gl-batches/" + batchRef + "/acknowledge"), "finance",
                        "{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/finance/gl-batches/" + batchRef + "/submit").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submittedBy").value("finance"));
        assertThat(new BigDecimal(JsonPath.read(batch, "$.debitTotal").toString()))
                .isEqualByComparingTo(new BigDecimal(JsonPath.read(batch, "$.creditTotal").toString()));
        mockMvc.perform(json(post("/finance/gl-batches/" + batchRef + "/acknowledge"), "finance",
                        "{\"status\":\"ACCEPTED\",\"glReference\":\"SAP-9001\"}"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        // The same acknowledgement again is harmless; the opposite answer is a conflict.
        mockMvc.perform(json(post("/finance/gl-batches/" + batchRef + "/acknowledge"), "finance",
                        "{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/finance/gl-batches/" + batchRef + "/acknowledge"), "finance",
                        "{\"status\":\"REJECTED\",\"note\":\"late\"}"))
                .andExpect(status().isConflict());
        // Reconciliation: the totals the GL reports must equal the batch control totals.
        String debitTotal = JsonPath.read(batch, "$.debitTotal").toString();
        mockMvc.perform(json(post("/finance/gl-batches/" + batchRef + "/reconcile"), "finance",
                        "{\"glDebitTotal\":\"1.00\",\"glCreditTotal\":\"1.00\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("do not match")));
        mockMvc.perform(json(post("/finance/gl-batches/" + batchRef + "/reconcile"), "finance",
                        "{\"glDebitTotal\":\"" + debitTotal + "\",\"glCreditTotal\":\"" + debitTotal + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILED"))
                .andExpect(jsonPath("$.reconciledBy").value("finance"));

        String closed = mockMvc.perform(json(post("/finance/periods/" + period + "/close"), "finance",
                        "{\"reason\":\"Month end\",\"acknowledgeOpenItems\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(closed, "$.code")).isEqualTo(period);

        // An event dated in the closed period posts to the next open one, keeping its event date.
        String[] later = pendingPolicy("Oratile Dube", "oratile");
        mockMvc.perform(json(post("/portfolio/policies/" + later[0] + "/payments"), "admin", "{\"method\":\"Card\"}"))
                .andExpect(status().isOk());
        dispatch();
        String posted = mockMvc.perform(get("/finance/journals?eventType=PremiumReceived")
                        .with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(posted, "$[?(@.sourceRef == '" + later[0] + "')].periodCode"))
                .containsExactly(YearMonth.now().plusMonths(1).toString());

        mockMvc.perform(json(post("/finance/periods/" + period + "/reopen"), "finance", "{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/finance/periods/" + period + "/reopen"), "finance", "{\"reason\":\"Audit fix\"}"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(json(post("/finance/periods/" + period + "/close"), "grpadmin", "{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void manualJournalsAndSuspenseClearanceNeedASecondPerson() throws Exception {
        // Blueprint §7: manual journals and suspense clearance have a named owner and maker-checker approval.
        mockMvc.perform(json(post("/finance/manual-journals"), "admin", "{\"description\":\"Bank charges\","
                        + "\"reason\":\"October bank charges not yet posted\",\"lines\":[{\"account\":\"5000\","
                        + "\"debit\":\"12.50\"},{\"account\":\"1000\",\"credit\":\"10.00\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("does not balance")));
        String manual = mockMvc.perform(json(post("/finance/manual-journals"), "admin", "{\"description\":\"Bank charges\","
                        + "\"reason\":\"October bank charges not yet posted\",\"lines\":[{\"account\":\"5000\","
                        + "\"debit\":\"12.50\"},{\"account\":\"1000\",\"credit\":\"12.50\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.journalRef").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String manualRef = JsonPath.read(manual, "$.ref");
        mockMvc.perform(post("/finance/manual-journals/" + manualRef + "/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isForbidden());
        String approved = mockMvc.perform(post("/finance/manual-journals/" + manualRef + "/approve")
                        .with(httpBasic("secadmin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.checkedBy").value("secadmin"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(get("/finance/journals/" + JsonPath.read(approved, "$.journalRef")).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.eventType").value("ManualJournal"))
                .andExpect(jsonPath("$.lines.length()").value(2));
        mockMvc.perform(post("/finance/manual-journals/" + manualRef + "/approve").with(httpBasic("secadmin", "password")))
                .andExpect(status().isConflict());

        // Suspense: a receipt that does not match goes to suspense; refunding it needs a second person.
        String[] policy = pendingPolicy("Lorato Kgosi", "loratok");
        mockMvc.perform(json(post("/integration/payment-notifications"), "admin", "{\"provider\":\"BankFeed\","
                        + "\"providerReference\":\"BF-77\",\"policyRef\":\"" + policy[0] + "\",\"amount\":\"1.00\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENSE"));
        Integer wrongAmount = suspenseId("BF-77");
        mockMvc.perform(json(post("/finance/suspense/" + wrongAmount + "/clearance"), "admin",
                        "{\"action\":\"ALLOCATE\",\"policyRef\":\"" + policy[0] + "\",\"reason\":\"Customer says it is theirs\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("refund it instead")));
        mockMvc.perform(json(post("/finance/suspense/" + wrongAmount + "/clearance"), "admin",
                        "{\"action\":\"REFUND\",\"reason\":\"Short payment returned to the payer\"}"))
                .andExpect(jsonPath("$.clearanceAction").value("REFUND"))
                .andExpect(jsonPath("$.requestedBy").value("admin"));
        mockMvc.perform(post("/finance/suspense/" + wrongAmount + "/clearance/approve").with(httpBasic("admin", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/finance/suspense/" + wrongAmount + "/clearance/approve").with(httpBasic("secadmin", "password")))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.clearedBy").value("secadmin"));

        // A receipt quoting an unknown policy is allocated to the right one once approved; the premium is collected.
        mockMvc.perform(json(post("/integration/payment-notifications"), "admin", "{\"provider\":\"BankFeed\","
                        + "\"providerReference\":\"BF-78\",\"policyRef\":\"POL-NOPE\",\"amount\":\"" + policy[1] + "\"}"))
                .andExpect(jsonPath("$.status").value("SUSPENSE"));
        Integer unknownPolicy = suspenseId("BF-78");
        mockMvc.perform(json(post("/finance/suspense/" + unknownPolicy + "/clearance"), "admin",
                        "{\"action\":\"ALLOCATE\",\"policyRef\":\"" + policy[0] + "\",\"reason\":\"Payer quoted an old reference\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/finance/suspense/" + unknownPolicy + "/clearance/approve").with(httpBasic("secadmin", "password")))
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.policyRef").value("POL-NOPE"));
        mockMvc.perform(get("/portfolio/policies").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + policy[0] + "')].status").value(org.hamcrest.Matchers.hasItem("Active")));

        // Clearing posts Dr suspense / Cr bank, so the cash is counted once.
        dispatch();
        String cleared = mockMvc.perform(get("/finance/journals?eventType=SuspenseCleared").with(httpBasic("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(cleared, "$[*].sourceRef")).contains("BF-77", "BF-78");
    }

    private Integer suspenseId(String providerRef) throws Exception {
        String suspense = mockMvc.perform(get("/finance/suspense").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.<List<Integer>>read(suspense, "$[?(@.providerRef == '" + providerRef + "')].id").get(0);
    }

    @Test
    void meListsModulePermissionsAndTheReportHasSections() throws Exception {
        mockMvc.perform(get("/me").with(httpBasic("finance", "password")))
                .andExpect(jsonPath("$.permissions[?(@ == 'READ_FINANCE')]").exists())
                .andExpect(jsonPath("$.permissions[?(@ == 'MANAGE_INTEGRATION')]").isEmpty());
        mockMvc.perform(get("/reports/management").with(httpBasic("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[?(@.key == 'individual')].metrics").exists())
                .andExpect(jsonPath("$.sections[?(@.key == 'finance')]").exists())
                // Every module reports, and none of them failed to load.
                .andExpect(jsonPath("$.sections.length()").value(8))
                .andExpect(jsonPath("$.sections[?(@.error != null)]").isEmpty());
        mockMvc.perform(get("/reports/management").with(httpBasic("service", "password")))
                .andExpect(status().isForbidden());
    }

    private void dispatch() throws Exception {
        mockMvc.perform(post("/integration/outbox/dispatch").with(httpBasic("ops", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failed").value(0));
    }

    /** A KYC-cleared customer's Term Life purchase awaiting payment: {policy ref, premium}. */
    private String[] pendingPolicy(String name, String username) throws Exception {
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 002\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\",\"pep\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String customer = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(post("/portfolio/shop").with(httpBasic(username, "password1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new String[] {JsonPath.read(shop, "$.requests[0].id"),
                JsonPath.read(shop, "$.requests[0].premium").toString()};
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
