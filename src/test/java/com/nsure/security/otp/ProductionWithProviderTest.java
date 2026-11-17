package com.nsure.security.otp;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

/** The prod profile with a messaging provider: every sign-up gets its own random code, and 123456 is refused. */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:nsure-prod-provider;DB_CLOSE_DELAY=-1")
class ProductionWithProviderTest extends ProductionProfileSupport {

    record Sent(OneTimeCodes.Purpose purpose, String destination, String code) {
    }

    /** Stands in for an SMS provider and keeps what it was asked to send. */
    static class RecordingSender implements OneTimeCodeSender {
        final List<Sent> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(OneTimeCodes.Purpose purpose, String destination, String code) {
            sent.add(new Sent(purpose, destination, code));
        }
    }

    @TestConfiguration
    static class Provider {
        @Bean
        RecordingSender recordingSender() {
            return new RecordingSender();
        }
    }

    @Autowired
    private RecordingSender sender;

    @Test
    void signUpRejects123456AndAcceptsOnlyTheCodeSentForThisRequest() throws Exception {
        String body = requestSignupCode("kabo.prod")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentTo").value("•••• 321"))
                .andExpect(jsonPath("$.demoCode").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String challenge = JsonPath.read(body, "$.challenge");
        Sent sent = sender.sent.get(sender.sent.size() - 1);
        assertThat(sent.purpose()).isEqualTo(OneTimeCodes.Purpose.CUSTOMER_SIGNUP);
        assertThat(sent.destination()).isEqualTo(PHONE);
        assertThat(sent.code()).matches("\\d{6}").isNotEqualTo("123456");

        signUp("kabo.prod", challenge, "123456")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("That code is not right. You have 4 attempt(s) left."));
        mockMvc.perform(get("/me").with(signedIn("kabo.prod", "password123")))
                .andExpect(status().isUnauthorized());

        signUp("kabo.prod", challenge, sent.code())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginUsername").value("kabo.prod"));
        mockMvc.perform(get("/me").with(signedIn("kabo.prod", "password123")))
                .andExpect(status().isOk());
    }

    @Test
    void eachRequestGetsItsOwnCode() throws Exception {
        String first = JsonPath.read(requestSignupCode("kabo.twice").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.challenge");
        String firstCode = sender.sent.get(sender.sent.size() - 1).code();
        String second = JsonPath.read(requestSignupCode("kabo.twice").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.challenge");
        assertThat(second).isNotEqualTo(first);
        // The first code answers only the first challenge (unless both random codes happen to be equal).
        if (!firstCode.equals(sender.sent.get(sender.sent.size() - 1).code())) {
            signUp("kabo.twice", second, firstCode).andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void staffSignInStillNeedsADeliveredCodeAndNeverTakesTheFixedOne() throws Exception {
        // Staff accounts hold no phone or e-mail yet, so no code can be delivered and sign-in is refused.
        mockMvc.perform(post("/auth/session").with(httpBasic("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Your account has no phone number or e-mail address for"
                        + " sign-in codes. Ask your administrator."))
                .andExpect(jsonPath("$.token").doesNotExist());
        mockMvc.perform(get("/me").with(httpBasic("admin", "password")))
                .andExpect(status().isUnauthorized());
    }
}
