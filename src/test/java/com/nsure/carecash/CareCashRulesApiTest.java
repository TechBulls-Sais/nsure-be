package com.nsure.carecash;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
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

/**
 * Specification section 07 required calculation tests on the approved Care Cash rule set (demo defaults, D04):
 * boundaries (7/8 and 21/22 nights, ICU 15/16), day case with surgery, maternity per live birth and on its waiting
 * end, accidental death once per life for daily tiers from 1,000, termination age, pre-existing exclusion, insured
 * lives only, a stay over the cover anniversary, a readmission sharing its event's limits, opening days, a premium
 * waiver once per window and its reversal, cover judged at the admission date, and a rule change under maker-checker.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-care-cash-rules;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CareCashRulesApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void boundariesFollowTheRuleSetAndTheTraceNamesIt() throws Exception {
        String policy = policy("Kago Boundary", "Bronze", 34, 24);
        String base = "/care-cash/policies/" + policy + "/assessment";
        // 7 nights: no uplift; 8 nights: the 3x uplift. 21 nights: one uplift; 22: both.
        mockMvc.perform(json(post(base), "admin", stay(40, 33, 0)))
                .andExpect(jsonPath("$.amount").value(7 * 500.0))
                .andExpect(jsonPath("$.ruleVersion").value(1))
                .andExpect(jsonPath("$.trace", hasItem(containsString("Care Cash rule set v1"))));
        mockMvc.perform(json(post(base), "admin", stay(40, 32, 0)))
                .andExpect(jsonPath("$.amount").value(8 * 500.0 + 3 * 500.0));
        mockMvc.perform(json(post(base), "admin", stay(60, 39, 0)))
                .andExpect(jsonPath("$.amount").value(21 * 500.0 + 3 * 500.0));
        mockMvc.perform(json(post(base), "admin", stay(60, 38, 0)))
                .andExpect(jsonPath("$.amount").value(22 * 500.0 + 3 * 500.0 + 7 * 500.0));
        // ICU 15 is paid at 150%; a 16th ICU day is paid at the ward rate.
        mockMvc.perform(json(post(base), "admin", stay(60, 44, 15)))
                .andExpect(jsonPath("$.amount").value(1 * 500.0 + 15 * 750.0 + 3 * 500.0));
        mockMvc.perform(json(post(base), "admin", stay(60, 44, 16)))
                .andExpect(jsonPath("$.amount").value(1 * 500.0 + 15 * 750.0 + 3 * 500.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("ICU days capped at 15"))));
        mockMvc.perform(json(post(base), "admin", stay(60, 55, 9)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("icuDays"));
        // Day case with surgery: 50% plus 100% of the daily benefit; the procedure is named.
        mockMvc.perform(json(post(base), "admin", "{\"eventType\":\"DAY_CASE\",\"admitDate\":\"" + LocalDate.now().minusDays(3)
                        + "\",\"surgery\":true,\"procedure\":\"Arthroscopy\"}"))
                .andExpect(jsonPath("$.amount").value(250.0 + 500.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("Arthroscopy"))));
        // Only an insured life of the policy can claim.
        mockMvc.perform(json(post(base), "admin", "{\"eventType\":\"ADMISSION\",\"lifeName\":\"Somebody Else\",\"admitDate\":\""
                        + LocalDate.now().minusDays(5) + "\",\"dischargeDate\":\"" + LocalDate.now().minusDays(2) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("lifeName"));
        // A pre-existing condition is excluded for 12 months from the life's waiting start.
        String recent = policy("Neo Recent", "Bronze", 40, 6);
        mockMvc.perform(json(post("/care-cash/policies/" + recent + "/assessment"), "admin",
                        "{\"eventType\":\"ADMISSION\",\"preExisting\":true,\"admitDate\":\"" + LocalDate.now().minusDays(5)
                                + "\",\"dischargeDate\":\"" + LocalDate.now().minusDays(2) + "\"}"))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("pre-existing condition is excluded for 12 months"))));
    }

    @Test
    void maternityAccidentalDeathAndTerminationAge() throws Exception {
        String policy = policy("Lorato Twins", "Silver", 30, 12);
        String base = "/care-cash/policies/" + policy;
        // Maternity on its waiting end (12 months to the day) pays per live birth: twins are 2 × 5 × daily.
        jdbc.update("update ins_policy set cover_start_date = ? where record_ref = ?", LocalDate.now().minusDays(1).minusMonths(12), policy);
        mockMvc.perform(json(post(base + "/assessment"), "admin", "{\"eventType\":\"MATERNITY\",\"births\":2,\"admitDate\":\""
                        + LocalDate.now().minusDays(1) + "\"}"))
                .andExpect(jsonPath("$.amount").value(2 * 5 * 1000.0));
        mockMvc.perform(json(post(base + "/assessment"), "admin", "{\"eventType\":\"MATERNITY\",\"admitDate\":\""
                        + LocalDate.now().minusDays(2) + "\"}"))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("12-month waiting period"))));
        // Accidental death: 25,000 for a 1,000 daily tier, once per life; approval ends the policy.
        String death = mockMvc.perform(json(post(base + "/events"), "admin", "{\"eventType\":\"ACCIDENTAL_DEATH\",\"cause\":\"ACCIDENT\","
                        + "\"admitDate\":\"" + LocalDate.now().minusDays(3) + "\",\"deathEvidence\":\"Death certificate, police report\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(25000.0))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post(base + "/assessment"), "admin", "{\"eventType\":\"ACCIDENTAL_DEATH\",\"cause\":\"ACCIDENT\","
                        + "\"admitDate\":\"" + LocalDate.now().minusDays(3) + "\"}"))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("once per life"))));
        mockMvc.perform(json(post("/care-cash/events/" + JsonPath.read(death, "$.ref") + "/decision"), "assessor",
                        "{\"approve\":true,\"note\":\"Police report checked\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from ins_policy where record_ref = ?", String.class, policy))
                .isEqualTo("Terminated");
        // Below the 1,000 tier there is no accidental death benefit; at 70 cover for the life has ended.
        String bronze = policy("Mpho Bronze", "Bronze", 30, 12);
        mockMvc.perform(json(post("/care-cash/policies/" + bronze + "/assessment"), "admin", "{\"eventType\":\"ACCIDENTAL_DEATH\","
                        + "\"cause\":\"ACCIDENT\",\"admitDate\":\"" + LocalDate.now().minusDays(3) + "\"}"))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("daily tiers of BWP 1,000"))));
        String old = policy("Rra Seventy", "Bronze", 70, 72);
        mockMvc.perform(json(post("/care-cash/policies/" + old + "/assessment"), "admin", stay(10, 5, 0)))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("ended at age 70"))));
    }

    @Test
    void anniversaryReadmissionOpeningDaysWaiverAndReversal() throws Exception {
        String policy = policy("Tumelo Limits", "Bronze", 40, 0);
        // Cover started 370 days ago: a stay from 10 days before the anniversary to 4 days after splits 10 + 4.
        LocalDate start = LocalDate.now().minusDays(370);
        jdbc.update("update ins_policy set cover_start_date = ? where record_ref = ?", start, policy);
        String base = "/care-cash/policies/" + policy;
        LocalDate anniversary = start.plusYears(1);
        mockMvc.perform(json(post(base + "/assessment"), "admin", "{\"eventType\":\"ADMISSION\",\"admitDate\":\""
                        + anniversary.minusDays(10) + "\",\"dischargeDate\":\"" + anniversary.plusDays(4) + "\"}"))
                .andExpect(jsonPath("$.daysNextYear").value(4))
                .andExpect(jsonPath("$.trace", hasItem(containsString("crosses the cover anniversary"))));
        // Opening days migrated from a previous system count against the year limit: 178 used, 2 left.
        mockMvc.perform(json(post(base + "/opening-days"), "assessor", "{\"lifetimeDays\":178,\"yearDays\":178,"
                        + "\"note\":\"Migrated from the previous administration system\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lives[0].openingDays").value(178));
        mockMvc.perform(json(post(base + "/assessment"), "admin", stay(4, 1, 0)))
                .andExpect(jsonPath("$.paidDays").value(2))
                .andExpect(jsonPath("$.trace", hasItem(containsString("180 days per life per policy year"))));

        // A readmission within 14 days shares the first stay's event limit and its surgical benefit.
        String other = policy("Kefilwe Readmit", "Bronze", 45, 24);
        String first = mockMvc.perform(json(post("/care-cash/policies/" + other + "/events"), "admin",
                        "{\"eventType\":\"ADMISSION\",\"admitDate\":\"" + LocalDate.now().minusDays(80) + "\",\"dischargeDate\":\""
                                + LocalDate.now().minusDays(25) + "\",\"surgery\":true,\"procedure\":\"Hip replacement\"}"))
                .andExpect(jsonPath("$.paidDays").value(55))
                .andExpect(jsonPath("$.waiverMonths").value(3))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/care-cash/policies/" + other + "/assessment"), "admin",
                        "{\"eventType\":\"ADMISSION\",\"admitDate\":\"" + LocalDate.now().minusDays(20) + "\",\"dischargeDate\":\""
                                + LocalDate.now().minusDays(10) + "\",\"surgery\":true,\"procedure\":\"Revision\"}"))
                .andExpect(jsonPath("$.paidDays").value(5))
                .andExpect(jsonPath("$.linkedEventRef").value((String) JsonPath.read(first, "$.ref")))
                .andExpect(jsonPath("$.trace", hasItem(containsString("already paid on the linked stay"))));

        // The waiver applies once per window, is published, and is undone when the event is reversed.
        String due = jdbc.queryForObject("select next_premium_due from ins_policy where record_ref = ?", String.class, other);
        String ref = JsonPath.read(first, "$.ref");
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/decision"), "assessor", "{\"approve\":true,\"note\":\"Invoices checked\"}"))
                .andExpect(jsonPath("$.waiverFrom").value(due.substring(0, 10)));
        assertThat(jdbc.queryForObject("select count(*) from int_outbox_event where event_type = 'PremiumWaived'"
                + " and aggregate_ref = ?", Integer.class, other)).isEqualTo(1);
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/reverse"), "assessor", "{\"reason\":\"Duplicate of an earlier claim\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
        assertThat(jdbc.queryForObject("select next_premium_due from ins_policy where record_ref = ?", String.class, other))
                .startsWith(due.substring(0, 10));
        mockMvc.perform(get("/care-cash/policies/" + other).with(signedIn("admin", "password")))
                .andExpect(jsonPath("$.policyDaysUsed").value(0));
    }

    @Test
    void aRuleChangeIsApprovedBySomeoneElseAndAppliesFromItsDate() throws Exception {
        String policy = policy("Dikeledi Rules", "Bronze", 40, 24);
        mockMvc.perform(json(post("/care-cash/rules"), "configurator", "{\"effectiveFrom\":\"" + LocalDate.now()
                        + "\",\"reason\":\"Insurer decision D04: day case at 60%\",\"rules\":{\"dayCasePercent\":60}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.rules.dayCasePercent").value(60))
                .andExpect(jsonPath("$.rules.icuPercent").value(150));
        mockMvc.perform(json(post("/care-cash/rules"), "configurator", "{\"reason\":\"Another change\",\"rules\":{\"icuPercent\":5}}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/care-cash/rules/2/approve").with(signedIn("configurator", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/care-cash/rules/2/approve").with(signedIn("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        String dayCase = "{\"eventType\":\"DAY_CASE\",\"admitDate\":\"%s\",\"procedure\":\"Endoscopy\"}";
        // Today's day case is assessed under v2; one before today keeps v1.
        mockMvc.perform(json(post("/care-cash/policies/" + policy + "/assessment"), "admin", dayCase.formatted(LocalDate.now())))
                .andExpect(jsonPath("$.amount").value(300.0))
                .andExpect(jsonPath("$.ruleVersion").value(2));
        mockMvc.perform(json(post("/care-cash/policies/" + policy + "/assessment"), "admin", dayCase.formatted(LocalDate.now().minusDays(1))))
                .andExpect(jsonPath("$.amount").value(250.0))
                .andExpect(jsonPath("$.ruleVersion").value(1));
        mockMvc.perform(json(post("/care-cash/rules"), "configurator", "{\"reason\":\"Bad values\",\"rules\":{\"icuPercent\":5}}"))
                .andExpect(status().isUnprocessableEntity());
        // Cover is judged on the admission date: a policy cancelled today still covers last month's stay.
        jdbc.update("update ins_policy set status = 'Cancelled' where record_ref = ?", policy);
        mockMvc.perform(json(post("/care-cash/policies/" + policy + "/assessment"), "admin", stay(30, 25, 0)))
                .andExpect(jsonPath("$.amount").value(5 * 500.0));
    }

    private static String stay(int admittedDaysAgo, int dischargedDaysAgo, int icu) {
        return "{\"eventType\":\"ADMISSION\",\"cause\":\"ILLNESS\",\"admitDate\":\"" + LocalDate.now().minusDays(admittedDaysAgo)
                + "\",\"dischargeDate\":\"" + LocalDate.now().minusDays(dischargedDaysAgo) + "\",\"icuDays\":" + icu + "}";
    }

    /** An active Care Cash policy on a tier, for a life of the given age, whose cover started months ago. */
    private String policy(String name, String tier, int age, int monthsAgo) throws Exception {
        String customer = JsonPath.read(mockMvc.perform(json(post("/portfolio/customers"), "admin", "{\"name\":\"" + name
                                + "\",\"email\":\"ccr" + Math.abs(name.hashCode()) + "@example.com\",\"phone\":\"+267 71 960 002\","
                                + "\"dateOfBirth\":\"" + LocalDate.now().minusYears(Math.min(age, 50)).minusDays(10) + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(post("/portfolio/customers/" + customer + "/kyc").with(signedIn("admin", "password"))).andExpect(status().isOk());
        String policy = JsonPath.read(mockMvc.perform(json(post("/portfolio/policies"), "admin", "{\"customerRef\":\"" + customer
                                + "\",\"product\":\"Care Cash\",\"tier\":\"" + tier + "\"}"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mockMvc.perform(json(post("/portfolio/policies/" + policy + "/payments"), "admin", "{\"method\":\"Bank EFT\"}"))
                .andExpect(status().isOk());
        jdbc.update("update ins_policy set cover_start_date = ? where record_ref = ?", LocalDate.now().minusMonths(monthsAgo), policy);
        // An older life entered years ago: issued within the entry ages today, then aged as the scenario needs.
        jdbc.update("update ins_customer set date_of_birth = ? where record_ref = ?", LocalDate.now().minusYears(age).minusDays(10),
                customer);
        return policy;
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
