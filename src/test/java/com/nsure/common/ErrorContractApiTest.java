package com.nsure.common;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nsure.testsupport.SignIn.signedIn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** NS-API-001: status codes by kind of failure; every error carries a code, message, fieldErrors and correlationId. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nsure-error-contract;DB_CLOSE_DELAY=-1",
        "nsure.rate-limit.sign-in-per-15-minutes=3"})
@AutoConfigureMockMvc
class ErrorContractApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void eachKindOfFailureHasItsStatusAndCodeAndTheRequestsCorrelationId() throws Exception {
        // 400: the request itself is malformed.
        MvcResult unreadable = mockMvc.perform(post("/portfolio/policies").with(signedIn("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andReturn();
        String correlation = JsonPath.read(unreadable.getResponse().getContentAsString(), "$.correlationId");
        assertThat(correlation).isNotBlank();
        assertThat(unreadable.getResponse().getHeader(CorrelationId.HEADER)).isEqualTo(correlation);

        mockMvc.perform(get("/audits/not-a-number").with(signedIn("admin", "password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("id"));

        // 422: a well-formed request that a business rule refuses; the caller's correlation id is kept.
        mockMvc.perform(post("/portfolio/policies").with(signedIn("admin", "password"))
                        .header(CorrelationId.HEADER, "partner-req-0001")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"product\":\"Term Life\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.correlationId").value("partner-req-0001"))
                .andExpect(header().string(CorrelationId.HEADER, "partner-req-0001"));

        // 401 and 403 from the security layer use the same body.
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        mockMvc.perform(get("/audits/1").with(signedIn("customer", "password")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        // 404 for a record that is not there.
        mockMvc.perform(get("/audits/99999999").with(signedIn("admin", "password")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void repeatedSignInAttemptsForOneAccountAreThrottled() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/auth/session").with(httpBasic("brute-target", "guess" + i)))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/auth/session").with(httpBasic("brute-target", "guess-again")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        // Another account from the same address is not locked out by it (a staff password answers with the
        // one-time-code challenge).
        mockMvc.perform(post("/auth/session").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondFactorRequired").value(true));
    }
}
