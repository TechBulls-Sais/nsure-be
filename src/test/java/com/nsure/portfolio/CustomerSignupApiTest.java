package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-signup;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CustomerSignupApiTest {

    @Autowired
    private MockMvc mockMvc;

    private static String details(String username, String otp) {
        return """
                {"name":"Neo Kgosi","email":"neo@example.com","phone":"+267 71 555 123",
                 "poiType":"Omang","poiNumber":"880618404","dateOfBirth":"1990-04-12",
                 "username":"%s","password":"password123","pep":false%s}
                """.formatted(username, otp == null ? "" : ",\"otp\":\"" + otp + "\"");
    }

    @Test
    void customerSignsUpWithTheFixedCodeAndCanSignIn() throws Exception {
        mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.signup", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentTo").value("•••• 123"));

        mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.signup", "123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginUsername").value("neo.signup"))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.poiNumber").value("880618404"));

        mockMvc.perform(get("/me").with(httpBasic("neo.signup", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerPortal").value(true));
    }

    @Test
    void wrongCodeCreatesNothing() throws Exception {
        mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.wrong", "000000")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The verification code is incorrect."));

        mockMvc.perform(get("/me").with(httpBasic("neo.wrong", "password123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupNeedsEveryAddCustomerField() throws Exception {
        mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neo Kgosi\",\"email\":\"neo@example.com\",\"phone\":\"71555123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void takenUsernameIsRejectedBeforeTheCodeIsSent() throws Exception {
        mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(details("customer", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Username customer is already in use."));
    }
}
