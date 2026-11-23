package com.nsure.portfolio;

import static com.nsure.portfolio.PolicyWorkflowApiTest.json;
import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Spec 01 BR-5 (OTP-verified declaration, immutable consent) and BR-6 (lock after wrong codes). */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-declaration;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class DeclarationApiTest {

    private static final String CONSENTS = "\"accuracy\":true,\"terms\":true,\"dataProtection\":true,\"falseInformation\":true";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allFourConsentsAndTheRightCodeRecordTheDeclaration() throws Exception {
        String policy = pending("Bontle Kebaabetswe", "bontle");
        String code = sendCode("bontle", policy);
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), "bontle",
                        "{\"code\":\"" + code + "\",\"accuracy\":true,\"terms\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Please confirm the data protection notice, "
                        + "that false information may void the policy."));
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), "bontle",
                        "{\"code\":\"" + code + "\"," + CONSENTS + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accuracy").value(true))
                .andExpect(jsonPath("$.falseInformation").value(true));
        // A later sign-in (another device) is told the purchase is already declared.
        mockMvc.perform(get("/portfolio/shop/declaration").with(signedIn("bontle", "password1")))
                .andExpect(jsonPath("$.declaredPolicies[0]").value(policy));
        // The consent log is evidence: audit.read only (spec 04 §7).
        mockMvc.perform(get("/portfolio/policies/" + policy + "/declarations").with(signedIn("compliance", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].policyRef").value(policy))
                .andExpect(jsonPath("$[0].recordedBy").value("bontle"));
        mockMvc.perform(get("/portfolio/policies/" + policy + "/declarations").with(signedIn("support", "password")))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "bontle", "{\"method\":\"MyZaka\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policies[0].status").value("Active"));
    }

    @Test
    void threeWrongCodesLockPaymentForThirtyMinutes() throws Exception {
        String policy = pending("Oratile Sello", "oratile");
        sendCode("oratile", policy);
        for (int attempt = 1; attempt <= 2; attempt++) {
            mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), "oratile",
                            "{\"code\":\"000000\"," + CONSENTS + "}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value("That code is not right. You have " + (3 - attempt)
                            + " attempt(s) left."));
        }
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), "oratile",
                        "{\"code\":\"000000\"," + CONSENTS + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Too many wrong codes. For your security, payment is locked for 30 minutes."));
        // BR-6: the lock is checked on sending a code and on paying.
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), "oratile", "{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/pay"), "oratile", "{\"method\":\"MyZaka\"}"))
                .andExpect(status().isForbidden());
        String lock = mockMvc.perform(get("/portfolio/shop/declaration").with(signedIn("oratile", "password1")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<Boolean>read(lock, "$.locked")).isTrue();
    }

    private String sendCode(String user, String policy) throws Exception {
        String sent = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), user, "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentTo").value(org.hamcrest.Matchers.endsWith("@example.com")))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(sent, "$.devCode");
    }

    private String pending(String name, String username) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 010\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(json(post("/portfolio/shop"), username, "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(shop, "$.requests[0].id");
    }
}
