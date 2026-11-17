package com.nsure.security.otp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Runs the application with the prod profile, as UAT and production do. Only what an in-memory test database needs is
 * overridden (credentials, migrating on start, binding the identity); nothing about one-time codes is.
 */
@SpringBootTest(properties = {
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.liquibase.enabled=true",
        "nsure.insurer.code=test-insurer",
        "nsure.insurer.environment=test",
        "nsure.insurer.display-name=Test Insurer",
        "nsure.insurer.bind-on-first-start=true",
        "nsure.payments.public-base-url=http://127.0.0.1/api",
        "nsure.payments.ui-base-url=http://127.0.0.1"})
@ActiveProfiles("prod")
@AutoConfigureMockMvc
abstract class ProductionProfileSupport {

    static final String PHONE = "+267 71 555 321";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected OneTimeCodes codes;

    static String details(String username, String challenge, String otp) {
        return """
                {"name":"Kabo Prod","email":"kabo@example.com","phone":"%s",
                 "poiType":"Omang","poiNumber":"770101404","dateOfBirth":"1985-01-01",
                 "username":"%s","password":"password123","pep":false%s%s}
                """.formatted(PHONE, username,
                challenge == null ? "" : ",\"challenge\":\"" + challenge + "\"",
                otp == null ? "" : ",\"otp\":\"" + otp + "\"");
    }

    ResultActions requestSignupCode(String username) throws Exception {
        return mockMvc.perform(post("/public/signup/otp").contentType(MediaType.APPLICATION_JSON)
                .content(details(username, null, null)));
    }

    ResultActions signUp(String username, String challenge, String otp) throws Exception {
        return mockMvc.perform(post("/public/signup").contentType(MediaType.APPLICATION_JSON)
                .content(details(username, challenge, otp)));
    }
}
