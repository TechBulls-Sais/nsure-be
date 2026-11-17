package com.nsure.security.otp;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The prod profile with no messaging provider: sign-up and staff sign-in are refused, and the demo code 123456 is
 * never accepted in their place.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:nsure-prod-no-provider;DB_CLOSE_DELAY=-1")
class ProductionWithoutProviderTest extends ProductionProfileSupport {

    @Test
    void theProdProfileDoesNotAcceptTheDemoCode() {
        assertThat(codes.demoCodeAccepted()).isFalse();
    }

    @Test
    void signUpIsRefusedRatherThanAcceptingTheFixedCode() throws Exception {
        requestSignupCode("kabo.noprovider")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Online sign-up is not available yet: no SMS or e-mail provider"
                        + " is connected. Please contact us to open an account."))
                .andExpect(jsonPath("$.challenge").doesNotExist());

        signUp("kabo.noprovider", null, "123456")
                .andExpect(status().isUnprocessableEntity());
        signUp("kabo.noprovider", "any-challenge", "123456")
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/me").with(signedIn("kabo.noprovider", "password123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void staffSignInIsRefusedRatherThanAcceptingTheFixedCode() throws Exception {
        mockMvc.perform(post("/auth/session").with(httpBasic("admin", "password")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.challenge").doesNotExist());
        mockMvc.perform(post("/auth/session/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challenge\":\"guess\",\"code\":\"123456\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/me").with(httpBasic("admin", "password")))
                .andExpect(status().isUnauthorized());
    }
}
