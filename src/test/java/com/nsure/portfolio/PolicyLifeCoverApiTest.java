package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Rate-table plans price every covered life (spec 03 sections 4.2-4.3) and store a Policy Life Cover row per
 * life (R-16). Family Funeral Bronze is BWP 25,000: 2.5 times the demo's per-BWP-10,000 Boikanyo Standard rates.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-life-cover;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class PolicyLifeCoverApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aFamilyFuneralPolicySumsTheRateOfEveryLife() throws Exception {
        verifiedCustomer("Kagiso Sebina", null, 32);
        String family = lives(life("Spouse", "Mpho Sebina", 31), life("Child", "Tumo Sebina", 3));

        // Main insured 30-34: 7.00 x 2.5; spouse 30-34: 5.60 x 2.5; child 0-4: 1.05 x 2.5 on BWP 5,000 cover.
        mockMvc.perform(post("/portfolio/quote")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kagiso Sebina\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"," + family + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rated").value(true))
                .andExpect(jsonPath("$.rateTableVersion").value("RT-00001-v1"))
                .andExpect(jsonPath("$.lives.length()").value(3))
                .andExpect(jsonPath("$.lives[0].relationship").value("Main Insured"))
                .andExpect(jsonPath("$.lives[0].fullName").value("Kagiso Sebina"))
                .andExpect(jsonPath("$.lives[0].age").value(32))
                .andExpect(jsonPath("$.lives[0].monthlyPremium").value(17.5))
                .andExpect(jsonPath("$.lives[1].monthlyPremium").value(14.0))
                .andExpect(jsonPath("$.lives[2].age").value(3))
                .andExpect(jsonPath("$.lives[2].coverAmount").value(5000.0))
                .andExpect(jsonPath("$.lives[2].monthlyPremium").value(2.63))
                .andExpect(jsonPath("$.premium").value(34.13));

        // Saving re-prices on the server (R-18): the client's premium of 1 is ignored.
        mockMvc.perform(post("/portfolio/policies")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kagiso Sebina\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\","
                                + "\"amount\":1,\"premium\":1," + family + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(34.13))
                .andExpect(jsonPath("$.amount").value(25000))
                .andExpect(jsonPath("$.rateTableVersion").value("RT-00001-v1"))
                .andExpect(jsonPath("$.lives[*].relationship").value(Matchers.contains("Main Insured", "Spouse", "Child")))
                .andExpect(jsonPath("$.lives[1].fullName").value("Mpho Sebina"))
                .andExpect(jsonPath("$.lives[1].age").value(31))
                .andExpect(jsonPath("$.lives[1].dateOfBirth").value(birthday(31).toString()));

        // The lives are stored with the policy, not recomputed: they come back on the list too.
        mockMvc.perform(get("/portfolio/policies").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Kagiso Sebina')].lives.length()").value(3))
                .andExpect(jsonPath("$[?(@.name == 'Kagiso Sebina')].premium").value(34.13));
    }

    @Test
    void livesOutsideThePlanRulesAreRefusedWithEveryReason() throws Exception {
        verifiedCustomer("Lesego Tau", null, 40);
        String lives = lives(
                life("Sibling", "Onalenna Tau", 35),
                life("Child", "Neo Tau", 19),
                life("Child", "Kabo Tau", 2),
                life("Child", "Lorato Tau", 5),
                life("Child", "Thato Tau", 8),
                "{\"relationship\":\"Spouse\",\"fullName\":\"Boitumelo Tau\"}");
        mockMvc.perform(post("/portfolio/applications")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Lesego Tau\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"," + lives + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString(
                        "'Sibling' is not covered on the Bronze plan. Allowed: Main Insured, Spouse, Child, Parents")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(
                        "Neo Tau (Child) is 19; the Bronze plan covers Child lives aged 0–17.")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(
                        "Date of birth is required to calculate the premium for Boitumelo Tau (Spouse).")))
                .andExpect(jsonPath("$.message").value(Matchers.containsString(
                        "The Bronze plan covers at most 3 Child lives; 4 were added.")));

        // Nothing was saved by the refused request.
        mockMvc.perform(get("/portfolio/applications").with(httpBasic("admin", "password")))
                .andExpect(jsonPath("$[?(@.name == 'Lesego Tau')]").isEmpty());
    }

    @Test
    void theMainInsuredIsTheCustomerAndFlatPlansTakeNoLives() throws Exception {
        verifiedCustomer("Masego Pule", null, 45);
        mockMvc.perform(post("/portfolio/applications")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Masego Pule\",\"product\":\"Family Funeral\",\"tier\":\"Silver\","
                                + lives(life("Main Insured", "Someone Else", 20)) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The customer is the Main Insured; add only the other lives to cover."));

        mockMvc.perform(post("/portfolio/applications")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Masego Pule\",\"product\":\"Term Life\",\"tier\":\"Silver\","
                                + lives(life("Spouse", "Kitso Pule", 44)) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("has a flat premium")));

        // Credit life rates the borrower only: a spouse is not in the plan's life assured rules.
        mockMvc.perform(post("/portfolio/quote")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Masego Pule\",\"product\":\"Individual Credit\",\"tier\":\"Bronze\","
                                + lives(life("Spouse", "Kitso Pule", 44)) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "'Spouse' is not covered on the Bronze plan. Allowed: Main Insured"));

        // No lives on a rated plan still prices the main insured alone (45-49 on Silver: 18.10 x 5).
        mockMvc.perform(post("/portfolio/applications")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Masego Pule\",\"product\":\"Family Funeral\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.premium").value(90.5))
                .andExpect(jsonPath("$.lives.length()").value(1));

        // A flat tier quotes its fixed premium and stores no lives.
        mockMvc.perform(post("/portfolio/quote")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Masego Pule\",\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rated").value(false))
                .andExpect(jsonPath("$.lives").isEmpty());
    }

    @Test
    void customersQuoteAndBuyCoverForTheirFamily() throws Exception {
        verifiedCustomer("Goitse Ramotswa", "goitse", 28);
        String family = lives(life("Spouse", "Wame Ramotswa", 27), life("Child", "Oratile Ramotswa", 6));

        // Gold is BWP 100,000 (x10): main 25-29 60.00, spouse 25-29 31.00, child 5-9 12.00.
        mockMvc.perform(post("/portfolio/shop/quote")
                        .with(httpBasic("goitse", "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Family Funeral\",\"tier\":\"Gold\"," + family + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lives[0].fullName").value("Goitse Ramotswa"))
                .andExpect(jsonPath("$.premium").value(103.0));

        mockMvc.perform(post("/portfolio/shop")
                        .with(httpBasic("goitse", "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"Family Funeral\",\"tier\":\"Gold\"," + family + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests[0].premium").value(103.0))
                .andExpect(jsonPath("$.requests[0].rateTableVersion").value("RT-00003-v1"))
                .andExpect(jsonPath("$.requests[0].lives.length()").value(3));

        // The tier cards carry the rules that drive the "add a life" screen.
        mockMvc.perform(get("/portfolio/shop").with(httpBasic("goitse", "password1")))
                .andExpect(jsonPath("$.products[?(@.name == 'Family Funeral')].tiers[?(@.name == 'Gold')].lives[1].lifeType")
                        .value("Spouse"))
                .andExpect(jsonPath("$.products[?(@.name == 'Family Funeral')].tiers[?(@.name == 'Gold')].lives[2].maxAge")
                        .value(17));

        // A customer cannot quote for someone else through the staff endpoint.
        mockMvc.perform(post("/portfolio/quote")
                        .with(httpBasic("goitse", "password1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Kagiso Sebina\",\"product\":\"Family Funeral\",\"tier\":\"Bronze\"}"))
                .andExpect(status().isForbidden());
    }

    /** Creates a customer aged {@code age} (a little past their birthday) and marks KYC successful. */
    private String verifiedCustomer(String name, String username, int age) throws Exception {
        String login = username == null ? "" : ",\"username\":\"" + username + "\",\"password\":\"password1\"";
        String body = mockMvc.perform(post("/portfolio/customers")
                        .with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"email\":\"" + name.replace(' ', '.').toLowerCase()
                                + "@example.com\",\"phone\":\"+267 71 000 000\",\"dateOfBirth\":\"" + birthday(age) + "\""
                                + login + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/portfolio/customers/" + ref + "/kyc").with(httpBasic("admin", "password")))
                .andExpect(status().isOk());
        return ref;
    }

    private static LocalDate birthday(int age) {
        return LocalDate.now().minusYears(age).minusDays(20);
    }

    private static String life(String relationship, String fullName, int age) {
        return "{\"relationship\":\"" + relationship + "\",\"fullName\":\"" + fullName
                + "\",\"dateOfBirth\":\"" + birthday(age) + "\"}";
    }

    private static String lives(String... lives) {
        return "\"lives\":[" + String.join(",", lives) + "]";
    }
}
