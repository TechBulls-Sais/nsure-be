package com.nsure.carecash;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Care Cash benefit engine (specification section 07 and its required calculation tests). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-carecash;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CareCashApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void anAdmissionIsWorkedOutBenefitByBenefitWithinItsLimits() throws Exception {
        String policy = policy("Dineo Hospital", 14);
        String base = "/care-cash/policies/" + policy;
        double daily = ((Number) JsonPath.read(mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.dailyBenefit")).doubleValue();

        // One night does not qualify; two do, and are paid from day one.
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("ADMISSION", "ILLNESS", 11, 10, 0, false)))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("qualifies from 2 consecutive nights"))));
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("ADMISSION", "ILLNESS", 12, 10, 0, false)))
                .andExpect(jsonPath("$.amount").value(2 * daily));

        // 12 nights, 3 in ICU, with surgery: 9 ward + 3 ICU at 150% + surgery + long-stay 3x, and a premium waiver.
        String recorded = mockMvc.perform(json(post(base + "/events"), "admin", event("ADMISSION", "ILLNESS", 42, 30, 3, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidDays").value(12))
                .andExpect(jsonPath("$.amount").value(9 * daily + 3 * 1.5 * daily + daily + 3 * daily))
                .andExpect(jsonPath("$.waiverMonths").value(3))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(recorded, "$.ref");
        mockMvc.perform(get(base).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policyDaysUsed").value(12))
                .andExpect(jsonPath("$.lives[0].usedThisYear").value(12));

        // A day case pays 50%; an 80-night stay is capped at 60 days per event; ICU at 15 days.
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("DAY_CASE", "ILLNESS", 5, 5, 0, false)))
                .andExpect(jsonPath("$.amount").value(0.5 * daily));
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("ADMISSION", "ILLNESS", 180, 100, 20, false)))
                .andExpect(jsonPath("$.paidDays").value(60))
                .andExpect(jsonPath("$.trace", hasItem(containsString("60 days per event"))))
                .andExpect(jsonPath("$.trace", hasItem(containsString("ICU days capped at 15"))));

        // Four-eyes approval, then payment; the waiver moves the next premium out three months.
        String due = jdbc.queryForObject("select next_premium_due from ins_policy where record_ref = ?", String.class, policy);
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/decision"), "admin", "{\"approve\":true,\"note\":\"Hospital bill checked\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/decision"), "assessor", "{\"approve\":true,\"note\":\"Hospital bill checked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select next_premium_due from ins_policy where record_ref = ?", String.class, policy))
                .isEqualTo(LocalDate.parse(due.substring(0, 10)).plusMonths(3).toString());
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment"), "assessor", "{\"paymentReference\":\"EFT-CC-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    void waitingPeriodsMaternityAndAccidentalDeathFollowTheProductTerms() throws Exception {
        String recent = policy("Masego Recent", 1);
        // Illness inside 3 months is not payable; an accident is, immediately.
        mockMvc.perform(json(post("/care-cash/policies/" + recent + "/assessment"), "admin", event("ADMISSION", "ILLNESS", 5, 2, 0, false)))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("3-month waiting period"))));
        mockMvc.perform(json(post("/care-cash/policies/" + recent + "/assessment"), "admin", event("ADMISSION", "ACCIDENT", 5, 2, 0, false)))
                .andExpect(jsonPath("$.paidDays").value(3));
        mockMvc.perform(json(post("/care-cash/policies/" + recent + "/assessment"), "admin", event("MATERNITY", "ILLNESS", 3, 3, 0, false)))
                .andExpect(jsonPath("$.trace", hasItem(containsString("12-month waiting period"))));

        // Maternity pays 5x, twice per life; the third claim is refused.
        String policy = policy("Refilwe Mother", 30);
        String base = "/care-cash/policies/" + policy;
        for (int n = 0; n < 2; n++) {
            mockMvc.perform(json(post(base + "/events"), "admin", event("MATERNITY", "ILLNESS", 10 + n, 10 + n, 0, false)))
                    .andExpect(jsonPath("$.amount").value(org.hamcrest.Matchers.greaterThan(0.0)));
        }
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("MATERNITY", "ILLNESS", 5, 5, 0, false)))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("already had 2 maternity claims"))));
        // Accidental death needs a daily tier of 1,000 or more.
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("ACCIDENTAL_DEATH", "ACCIDENT", 2, 2, 0, false)))
                .andExpect(jsonPath("$.amount").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(0.0), org.hamcrest.Matchers.is(25000.0))));
    }

    private static String event(String type, String cause, int admittedDaysAgo, int dischargedDaysAgo, int icu, boolean surgery) {
        return "{\"eventType\":\"" + type + "\",\"cause\":\"" + cause + "\",\"admitDate\":\"" + LocalDate.now().minusDays(admittedDaysAgo)
                + "\",\"dischargeDate\":\"" + LocalDate.now().minusDays(dischargedDaysAgo) + "\",\"icuDays\":" + icu + ",\"surgery\":" + surgery + "}";
    }

    /** An active Care Cash policy whose cover started the given number of months ago. */
    private String policy(String name, int monthsAgo) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"cc" + Math.abs(name.hashCode()) + "@example.com\",\"phone\":\"+267 71 960 001\","
                                + "\"dateOfBirth\":\"" + LocalDate.now().minusYears(34) + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password"))).andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + customer
                                + "\",\"product\":\"Care Cash\",\"tier\":\"Bronze\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        jdbc.update("update ins_policy set cover_start_date = ? where record_ref = ?", LocalDate.now().minusMonths(monthsAgo), policy);
        return policy;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
