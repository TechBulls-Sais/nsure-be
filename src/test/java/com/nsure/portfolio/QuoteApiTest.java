package com.nsure.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/** Stored, versioned quotes (NS-IL01-001/004, AT-IL01-01, NS-SH03-003/005). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-quotes;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class QuoteApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Test
    void aQuoteKeepsItsInputsRateVersionAndTraceAndReplaysToTheSameAmount() throws Exception {
        String customer = customer("Kagiso Quote", 32, null);
        String family = "\"lives\":[{\"relationship\":\"Spouse\",\"fullName\":\"Mpho Quote\",\"dateOfBirth\":\""
                + birthday(31) + "\"},{\"relationship\":\"Child\",\"fullName\":\"Tumo Quote\",\"dateOfBirth\":\""
                + birthday(3) + "\"}]";
        String quote = mockMvc.perform(json(post("/portfolio/quotes"), "admin", "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"," + family + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.pricingMethod").value("RATE_TABLE"))
                .andExpect(jsonPath("$.rateVersion").value("RT-00001-v1"))
                .andExpect(jsonPath("$.premium").value(34.13))
                .andExpect(jsonPath("$.validUntil").value(LocalDate.now().plusDays(30).toString()))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.trace.lines.length()").value(3))
                .andExpect(jsonPath("$.trace.lines[0].age").value(32))
                .andExpect(jsonPath("$.trace.lines[0].monthlyPremium").value("17.50"))
                .andExpect(jsonPath("$.trace.rounding").exists())
                .andExpect(jsonPath("$.inputHash").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(quote, "$.ref");

        // NS-SH03-005: priced again from the stored inputs under the stored version, the amount is the same.
        mockMvc.perform(get("/portfolio/quotes/" + ref + "/replay").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.reproduced").value(true))
                .andExpect(jsonPath("$.replayed").value(34.13));
        // A quote is not cover, and a customer cannot make staff quotes.
        mockMvc.perform(get("/portfolio/policies").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.name == 'Kagiso Quote')]").isEmpty());
        mockMvc.perform(json(post("/portfolio/quotes"), "customer", "{\"customerRef\":\"" + customer
                        + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anExpiredQuoteCannotBeIssuedUntilTheRevisedOfferIsAcceptedAndTheOldOneStaysReadable() throws Exception {
        String customer = customer("Lesego Expiry", 34, null);
        String application = mockMvc.perform(json(post("/portfolio/applications"), "admin", "{\"declarationAccepted\":true,\"disclosures\":{\"TOBACCO\":\"NO\",\"TREATMENT\":\"NO\"},\"customerRef\":\""
                        + customer + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quoteVersion").value(1))
                .andReturn().getResponse().getContentAsString();
        String app = JsonPath.read(application, "$.id");
        String ref = JsonPath.read(application, "$.quoteRef");
        mockMvc.perform(get("/portfolio/quotes/" + ref).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$[0].applicationRef").value(app));

        // The offer lapses, and meanwhile the customer turns 35: a different rate band.
        tx.executeWithoutResult(s -> {
            em.createQuery("update Quote q set q.validUntil = :past where q.ref = :ref")
                    .setParameter("past", LocalDate.now().minusDays(1)).setParameter("ref", ref).executeUpdate();
            em.createQuery("update CustomerAccount c set c.dateOfBirth = :dob where c.ref = :ref")
                    .setParameter("dob", birthday(35)).setParameter("ref", customer).executeUpdate();
        });
        String issue = "{\"applicationRef\":\"" + app + "\",\"customerRef\":\"" + customer
                + "\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}";
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("expired")));

        String revised = mockMvc.perform(post("/portfolio/quotes/" + ref + "/requote").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.status").value("QUOTED"))
                .andExpect(jsonPath("$.differences").value(org.hamcrest.Matchers.containsString("age 34 → 35")))
                .andReturn().getResponse().getContentAsString();
        double revisedPremium = ((Number) JsonPath.read(revised, "$.premium")).doubleValue();

        // Not until the customer accepts the revised offer; the replaced version cannot be accepted instead.
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("version 2")));
        mockMvc.perform(json(post("/portfolio/quotes/" + ref + "/accept"), "admin", "{\"version\":1}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/portfolio/quotes/" + ref + "/accept"), "admin",
                        "{\"version\":2,\"note\":\"Customer accepted the new premium by phone\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        mockMvc.perform(get("/portfolio/applications").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + app + "')].quoteVersion").value(2))
                .andExpect(jsonPath("$[?(@.id == '" + app + "')].premium").value(revisedPremium));

        // Issue uses the accepted revised offer as it stands.
        mockMvc.perform(json(post("/portfolio/policies"), "admin", issue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Pending Payment"))
                .andExpect(jsonPath("$.quoteRef").value(ref))
                .andExpect(jsonPath("$.quoteVersion").value(2))
                .andExpect(jsonPath("$.premium").value(revisedPremium));
        String versions = mockMvc.perform(get("/portfolio/quotes/" + ref).with(httpBasic("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<Integer>) JsonPath.read(versions, "$[*].version")).containsExactly(2, 1);
        assertThat((List<Boolean>) JsonPath.read(versions, "$[*].expired")).containsExactly(false, true);
        Long accepted = tx.execute(s -> em.createQuery("select count(e) from OutboxEvent e where e.eventType ="
                + " 'OfferAccepted' and e.aggregateRef = :ref", Long.class).setParameter("ref", ref).getSingleResult());
        assertThat(accepted).isEqualTo(2L);
    }

    @Test
    void aCheckoutKeepsTheAcceptedQuoteWithThePolicy() throws Exception {
        String customer = customer("Neo Checkout", 40, "neocheckout");
        String shop = mockMvc.perform(json(post("/portfolio/shop"), "neocheckout",
                        "{\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(shop, "$.requests[0].id");
        String quotes = mockMvc.perform(get("/portfolio/quotes").param("customerRef", customer)
                        .with(httpBasic("admin", "password")))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(quotes, "$[*].status")).containsExactly("ACCEPTED");
        assertThat((List<String>) JsonPath.read(quotes, "$[*].policyRef")).containsExactly(policy);
        assertThat((List<String>) JsonPath.read(quotes, "$[*].acceptedBy")).containsExactly("neocheckout");
    }

    private String customer(String name, int age, String username) throws Exception {
        String login = username == null ? "" : ",\"username\":\"" + username + "\",\"password\":\"password1\"";
        String body = mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                        + "\",\"email\":\"" + name.replace(" ", "").toLowerCase() + "@example.com\",\"phone\":\"+267 71 600 0"
                        + age + "\",\"dateOfBirth\":\"" + birthday(age) + "\"" + login + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private static LocalDate birthday(int age) {
        return LocalDate.now().minusYears(age).minusDays(10);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = "admin".equals(user) || "customer".equals(user) ? "password" : "password1";
        return request.with(httpBasic(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
