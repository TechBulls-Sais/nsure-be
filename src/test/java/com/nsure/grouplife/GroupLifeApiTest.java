package com.nsure.grouplife;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Group lifecycle (NS-GL01-001..006, NS-GL02-002/003, NS-GL05-005/006, NS-GL06, AT-GL01-01/03, AT-GL02-01, AT-GL06-01/02). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-grouplife;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GroupLifeApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aSchemeGoesFromQuotationThroughApprovalToActivationRenewalAndClosure() throws Exception {
        String quote = "{\"sponsorName\":\"Kgalagadi Mining\",\"sector\":\"Mining\",\"lives\":120,\"salaryRoll\":14400000,"
                + "\"benefits\":[\"GLA\",\"FAMILY\",\"CREDIT\"],\"ratingBasis\":\"SALARY\",\"salaryMultiple\":2,\"ratePerMille\":3.2}";
        String ref = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes"), "admin", quote))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.annualPremium").value(92160.0))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        // A negotiated revision is a new version; version 1 stays as offered.
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/revision"), "admin", "{\"ratePerMille\":3.0}"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.annualPremium").value(86400.0));
        mockMvc.perform(get("/group-life/quotes").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.version == 1)].status").value("SUPERSEDED"));
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "admin", "{\"action\":\"SUBMIT\"}"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "admin", "{\"action\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "secadmin", "{\"action\":\"APPROVE\",\"note\":\"Rate agreed\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "admin", "{\"action\":\"ACCEPT\"}"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        // AT-GL01-01: no activation without the wording and a free-cover limit.
        String activate = "{\"inceptionDate\":\"" + LocalDate.now() + "\",\"wordingConfirmed\":true}";
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/activation"), "admin", activate))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("free-cover limit")));
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/revision"), "admin", "{\"freeCoverLimit\":500000}"))
                .andExpect(status().isOk());
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "admin", "{\"action\":\"SUBMIT\"}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "secadmin", "{\"action\":\"APPROVE\"}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/progress"), "admin", "{\"action\":\"ACCEPT\"}")).andExpect(status().isOk());
        mockMvc.perform(json(post("/group-life/quotes/" + ref + "/activation"), "admin", activate.replace("true", "false")))
                .andExpect(status().isUnprocessableEntity());
        String scheme = JsonPath.read(mockMvc.perform(json(post("/group-life/quotes/" + ref + "/activation"), "admin", activate))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVATED"))
                .andReturn().getResponse().getContentAsString(), "$.schemeRef");
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/benefits").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.length()").value(3));

        // AT-GL02-01: a scheme cannot go above the product maximum.
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefits"), "admin",
                        "{\"benefitType\":\"FAMILY\",\"basis\":\"FIXED\",\"fixedAmount\":60000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("product maximum")));
        mockMvc.perform(json(put("/group-life/schemes/" + scheme + "/benefits"), "admin",
                        "{\"benefitType\":\"INCOME_PROTECTION\",\"basis\":\"MONTHLY_INCOME_PERCENT\",\"multiple\":75,\"waitingMonths\":3}"))
                .andExpect(status().isOk());

        // AT-GL06-01/02: a repeated loan is not added twice; one above the cap is referred with the limit shown.
        String file = "{\"batchReference\":\"BDX-1\",\"rows\":[{\"lenderLoanId\":\"A1\",\"borrowerName\":\"Thabo M\",\"loanAmount\":200000,\"termMonths\":24},"
                + "{\"lenderLoanId\":\"A2\",\"borrowerName\":\"Lesego K\",\"loanAmount\":900000,\"termMonths\":36}]}";
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin", file))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.referred").value(1))
                .andExpect(jsonPath("$.results[1].note").value(containsString("above the scheme cap")));
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin", file))
                .andExpect(jsonPath("$.duplicates").value(2))
                .andExpect(jsonPath("$.accepted").value(0));
        java.util.List<Integer> ids = JsonPath.read(mockMvc.perform(get("/group-life/schemes/" + scheme + "/credit-loans")
                        .with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn().getResponse().getContentAsString(), "$[?(@.lenderLoanId == 'A1')].id");
        Integer loan = ids.get(0);
        mockMvc.perform(json(post("/group-life/credit-loans/" + loan + "/settlement"), "admin", "{}"))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.refund").value(3672.0));
        mockMvc.perform(json(post("/group-life/credit-loans/" + loan + "/settlement"), "admin", "{}"))
                .andExpect(jsonPath("$.refund").value(3672.0));

        // Renewal: previewed, requested, decided by someone else; the policy year moves on with no gap.
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/impact?eventType=RENEWAL").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.policyYear").value(1));
        String renewal = JsonPath.read(mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"RENEWAL\",\"rateChangePercent\":5,\"reason\":\"Annual renewal on updated census\"}"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/events/" + renewal + "/decision"), "admin", "{\"approve\":true,\"note\":\"ok\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/group-life/events/" + renewal + "/decision"), "secadmin", "{\"approve\":true,\"note\":\"Terms agreed\"}"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/group-life/schemes/" + scheme + "/impact?eventType=CLOSURE").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.policyYear").value(2))
                .andExpect(jsonPath("$.renewalDate").value(LocalDate.now().plusYears(2).toString()));

        // AT-GL01-03: closure reports what it affects before approval, and closes rather than deletes.
        String closure = JsonPath.read(mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/events"), "admin",
                        "{\"eventType\":\"CLOSURE\",\"reason\":\"Sponsor ceased trading\"}"))
                .andExpect(jsonPath("$.impact[0]").value(containsString("lose cover")))
                .andReturn().getResponse().getContentAsString(), "$.ref");
        mockMvc.perform(json(post("/group-life/events/" + closure + "/decision"), "secadmin", "{\"approve\":true,\"note\":\"Confirmed\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/group/schemes/" + scheme).with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$.scheme.status").value("CLOSED"));
        mockMvc.perform(json(post("/group-life/schemes/" + scheme + "/credit-loans"), "admin", file.replace("BDX-1", "BDX-2")))
                .andExpect(status().isUnprocessableEntity());
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(httpBasic(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
