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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Test
    void aConfirmedPaymentActivatesThePolicyAndPostsOnceHoweverOftenItIsDelivered() throws Exception {
        String[] policy = pendingPolicy("Naledi Sebina", "naledi");
        String ref = policy[0];
        String premium = policy[1];

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
                                + "\"status\":\"INACTIVE\",\"description\":\"Individual premium collected\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        String[] policy = pendingPolicy("Boipelo Rre", "boipelo");
        mockMvc.perform(json(post("/portfolio/policies/" + policy[0] + "/payments"), "admin", "{\"method\":\"Card\"}"))
                .andExpect(status().isOk());
        dispatch();
        String exceptions = mockMvc.perform(get("/finance/exceptions").with(httpBasic("finance", "password")))
                .andReturn().getResponse().getContentAsString();
        Integer id = ((List<Integer>) JsonPath.read(exceptions, "$[?(@.sourceRef == '" + policy[0] + "')].id")).get(0);
        mockMvc.perform(json(put("/finance/posting-rules/PremiumReceived"), "finance",
                        "{\"debitAccount\":\"1000\",\"creditAccount\":\"4000\",\"domain\":\"INDIVIDUAL\","
                                + "\"status\":\"ACTIVE\",\"description\":\"Individual premium collected\"}"))
                .andExpect(jsonPath("$.version").value(3));
        mockMvc.perform(post("/finance/exceptions/" + id + "/retry").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.journalRef").isNotEmpty());
        mockMvc.perform(post("/finance/exceptions/" + id + "/retry").with(httpBasic("finance", "password")))
                .andExpect(status().isConflict());
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
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/finance/journals/" + journal + "/reverse"), "finance",
                        "{\"reason\":\"Payment recalled by bank\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversesRef").value(journal))
                .andExpect(jsonPath("$.lines[0].accountCode").value("1000"))
                .andExpect(jsonPath("$.lines[0].credit").value(new BigDecimal(policy[1]).doubleValue()));
        mockMvc.perform(json(post("/finance/journals/" + journal + "/reverse"), "finance", "{\"reason\":\"again\"}"))
                .andExpect(status().isBadRequest());

        String period = YearMonth.now().toString();
        mockMvc.perform(json(post("/finance/periods/" + period + "/soft-close"), "finance", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SOFT_CLOSED"));
        // Journals not yet in the GL block the close (AT-SH08-03).
        mockMvc.perform(json(post("/finance/periods/" + period + "/close"), "finance", "{}"))
                .andExpect(status().isConflict());

        String batch = mockMvc.perform(post("/finance/gl-batches").with(httpBasic("finance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andReturn().getResponse().getContentAsString();
        String batchRef = JsonPath.read(batch, "$.ref");
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
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/finance/periods/" + period + "/reopen"), "finance", "{\"reason\":\"Audit fix\"}"))
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(json(post("/finance/periods/" + period + "/close"), "grpadmin", "{}"))
                .andExpect(status().isForbidden());
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
