package com.nsure.portfolio;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Customer self sign-up in a dev installation, where the demo code stands in for an SMS. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-signup;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CustomerSignupApiTest {

    static final String PHONE = "+267 71 555 123";

    @Autowired
    private MockMvc mockMvc;

    /** A 9-digit Omang number of the username's own, so each sign-up is a new identity (NS-SH01-002). */
    static String omang(String username) {
        return String.format("%09d", Math.floorMod(username.hashCode(), 1_000_000_000));
    }

    static String details(String username, String phone, String challenge, String otp) {
        return """
                {"name":"Neo Kgosi","email":"neo@example.com","phone":"%s",
                 "poiType":"Omang","poiNumber":"%s","dateOfBirth":"1990-04-12",
                 "username":"%s","password":"password123","pep":false%s%s}
                """.formatted(phone, omang(username), username,
                challenge == null ? "" : ",\"challenge\":\"" + challenge + "\"",
                otp == null ? "" : ",\"otp\":\"" + otp + "\"");
    }

    private String requestCode(String username, String phone) throws Exception {
        String body = mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(details(username, phone, null, null)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.challenge");
    }

    @Test
    void customerSignsUpWithTheDemoCodeAndCanSignIn() throws Exception {
        String body = mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.signup", PHONE, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentTo").value("•••• 123"))
                .andExpect(jsonPath("$.attemptsAllowed").value(5))
                .andExpect(jsonPath("$.demoCode").value("123456"))
                .andReturn().getResponse().getContentAsString();
        String challenge = JsonPath.read(body, "$.challenge");

        mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.signup", PHONE, challenge, "123456")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginUsername").value("neo.signup"))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.poiNumber").value(omang("neo.signup")));

        mockMvc.perform(get("/me").with(signedIn("neo.signup", "password123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerPortal").value(true));

        // One code, one sign-up.
        mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.second", PHONE, challenge, "123456")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This code has expired or was already used. Request a new code."));
    }

    @Test
    void wrongCodeCreatesNothing() throws Exception {
        String challenge = requestCode("neo.wrong", PHONE);
        mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.wrong", PHONE, challenge, "000000")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("That code is not right. You have 4 attempt(s) left."));

        mockMvc.perform(get("/me").with(signedIn("neo.wrong", "password123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theFixedCodeAloneIsNotEnough() throws Exception {
        // No challenge: the code has to belong to a request made for this sign-up.
        mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.nochallenge", PHONE, null, "123456")))
                .andExpect(status().isUnprocessableEntity());
        // A code sent to one phone does not sign up another.
        String challenge = requestCode("neo.otherphone", PHONE);
        mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(details("neo.otherphone", "+267 72 000 999", challenge, "123456")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This code was sent for different details. Request a new code."));

        mockMvc.perform(get("/me").with(signedIn("neo.nochallenge", "password123")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/me").with(signedIn("neo.otherphone", "password123")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupNeedsEveryAddCustomerField() throws Exception {
        mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Neo Kgosi\",\"email\":\"neo@example.com\",\"phone\":\"71555123\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void takenUsernameIsRejectedBeforeTheCodeIsSent() throws Exception {
        mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                        .content(details("customer", PHONE, null, null)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Username customer is already in use."));
    }
}
