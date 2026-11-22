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

        // NS-API-001: a malformed date is a 400 naming the field (it used to fail as a server error).
        mockMvc.perform(json(post(base + "/assessment"), "admin", "{\"eventType\":\"ADMISSION\",\"admitDate\":\"yesterday\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("admitDate"));
        mockMvc.perform(json(post(base + "/assessment"), "admin", "{\"eventType\":\"SPA\",\"admitDate\":\"2026-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("eventType"));

        // One night does not qualify; two do, and are paid from day one.
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("ADMISSION", "ILLNESS", 11, 10, 0, false)))
                .andExpect(jsonPath("$.amount").value(0.0))
                .andExpect(jsonPath("$.trace", hasItem(containsString("qualifies from 2 consecutive nights"))));
        // NS-UX-003 (B-26): the trace shows money as the workspace does, not as raw numbers.
        String formatted = new java.text.DecimalFormat("#,##0", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.ENGLISH))
                .format(2 * daily);
        mockMvc.perform(json(post(base + "/assessment"), "admin", event("ADMISSION", "ILLNESS", 12, 10, 0, false)))
                .andExpect(jsonPath("$.amount").value(2 * daily))
                .andExpect(jsonPath("$.trace", hasItem("Payable: BWP " + formatted + ".")));

        // 12 nights, 3 in ICU, with surgery: 9 ward + 3 ICU at 150% + surgery + long-stay 3x, and a premium waiver.
        String recorded = mockMvc.perform(json(post(base + "/events"), "admin", event("ADMISSION", "ILLNESS", 42, 30, 3, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidDays").value(12))
                .andExpect(jsonPath("$.amount").value(9 * daily + 3 * 1.5 * daily + daily + 3 * daily))
                .andExpect(jsonPath("$.waiverMonths").value(3))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(recorded, "$.ref");
        // SH05: recording an assessment consumes nothing yet; the approval does.
        org.assertj.core.api.Assertions.assertThat(events(ref, "BenefitLimitConsumed")).isZero();
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
        org.assertj.core.api.Assertions.assertThat(events(ref, "BenefitLimitConsumed")).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "select next_premium_due from ins_policy where record_ref = ?", String.class, policy))
                .isEqualTo(LocalDate.parse(due.substring(0, 10)).plusMonths(3).toString());
        // NS-SH05-007 (B-16): the payment is an instruction in flight until the provider confirms it settled.
        String sent = mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment"), "assessor",
                        "{\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_SENT"))
                .andExpect(jsonPath("$.paymentReference").value(org.hamcrest.Matchers.startsWith("PI-")))
                .andExpect(jsonPath("$.payment.payeeName").value("Dineo Hospital"))
                .andExpect(jsonPath("$.payment.sentBy").value("assessor"))
                .andExpect(jsonPath("$.payment.settledAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String instruction = JsonPath.read(sent, "$.paymentReference");
        org.assertj.core.api.Assertions.assertThat(benefitPaidEvents(ref)).isZero();
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment"), "assessor",
                        "{\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already in flight")));
        // AT-SH05-03: the bank returns it; it goes again only with corrected details, under a new instruction.
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment/failed"), "assessor",
                        "{\"reason\":\"Returned: account closed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.payment.failureReason").value("Returned: account closed"));
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment"), "assessor",
                        "{\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62001234\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("Correct the payout details")));
        String resent = mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment"), "assessor",
                        "{\"payeeName\":\"Dineo Hospital\",\"payoutMethod\":\"Mobile Wallet\",\"accountRef\":\"71960001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_SENT"))
                .andExpect(jsonPath("$.payment.failureReason").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String second = JsonPath.read(resent, "$.paymentReference");
        org.assertj.core.api.Assertions.assertThat(second).isNotEqualTo(instruction);
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment/confirm"), "assessor",
                        "{\"providerReference\":\"" + instruction + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment/confirm"), "assessor",
                        "{\"providerReference\":\"" + second + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.settledAt").isNotEmpty());
        org.assertj.core.api.Assertions.assertThat(benefitPaidEvents(ref)).isEqualTo(1);
        // B-28: paying it again is refused as already paid, not as "must be approved".
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment"), "assessor",
                        "{\"payoutMethod\":\"Bank Account\",\"accountRef\":\"62009999\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(ref + " is already paid (" + second + "); it cannot be paid again."));
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment/confirm"), "assessor", "{}"))
                .andExpect(status().isConflict());
        mockMvc.perform(json(post("/care-cash/events/" + ref + "/payment/failed"), "onboarding", "{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        // NS-SH05-007: both payments are persisted instructions on the payout rail; the failed one stays on record.
        mockMvc.perform(get("/payments/payouts?source=CARE_CASH&ref=" + ref).with(signedIn("assessor", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ref").value(second))
                .andExpect(jsonPath("$[0].status").value("SETTLED"))
                .andExpect(jsonPath("$[0].replacesRef").value(instruction))
                .andExpect(jsonPath("$[1].status").value("FAILED"));
    }

    private int benefitPaidEvents(String ref) {
        return events(ref, "ContractualBenefitPaid");
    }

    private int events(String ref, String type) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Integer.class, type, ref);
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
