package com.nsure.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NS-SH11-001: the browser holds an expiring session token, never the password. Staff and partners get that token
 * only after the server has verified their one-time code; their password alone opens nothing.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-sessions;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SessionTokenApiTest {

    private static final String DEMO_CODE = "123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager em;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private PasswordEncoder passwords;

    @Test
    void aTokenSignsInUntilSignOutAndOnlyItsHashIsStored() throws Exception {
        String token = open("admin");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin"));
        Long stored = tx.execute(s -> em.createQuery("select count(a) from AuthSession a where a.tokenHash = :t",
                Long.class).setParameter("t", token).getSingleResult());
        assertThat(stored).isZero();

        mockMvc.perform(get("/me").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        // A token cannot open another session: only the password can.
        mockMvc.perform(post("/auth/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/auth/session").with(httpBasic("admin", "wrong")))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/auth/session").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has ended. Please sign in again."));
    }

    @Test
    void aSessionEndsWhenIdleAtItsAbsoluteExpiryAndWhenThePasswordChanges() throws Exception {
        String idle = open("secadmin");
        age(idle, "lastSeenAt", LocalDateTime.now().minusMinutes(31));
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + idle)).andExpect(status().isUnauthorized());

        String expired = open("secadmin");
        age(expired, "expiresAt", LocalDateTime.now().minusSeconds(1));
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + expired)).andExpect(status().isUnauthorized());

        String live = open("customer");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + live)).andExpect(status().isOk());
        String changed = passwords.encode("a-new-password");
        tx.executeWithoutResult(s -> em.createQuery("update AppUser u set u.password = :p where u.username = 'customer'")
                .setParameter("p", changed).executeUpdate());
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + live)).andExpect(status().isUnauthorized());
    }

    @Test
    void aStaffPasswordAloneOpensNoSessionAndCannotBeUsedOnTheApi() throws Exception {
        long sessionsBefore = sessionsOf("actuary");
        String body = mockMvc.perform(post("/auth/session").with(httpBasic("actuary", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondFactorRequired").value(true))
                .andExpect(jsonPath("$.username").value("actuary"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.attemptsAllowed").value(5))
                // Test installations are dev installations: the demo code is announced so the screen can show it.
                .andExpect(jsonPath("$.demoCode").value(DEMO_CODE))
                .andReturn().getResponse().getContentAsString();
        String challenge = JsonPath.read(body, "$.challenge");
        assertThat(sessionsOf("actuary")).isEqualTo(sessionsBefore);

        // The challenge is not a session token.
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + challenge))
                .andExpect(status().isUnauthorized());
        // An API client cannot skip the code by sending the password with every request.
        mockMvc.perform(get("/me").with(httpBasic("actuary", "password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/portfolio/claims").with(httpBasic("admin", "password")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/portfolio/customers").with(httpBasic("admin", "password"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/auth/session").with(httpBasic("admin", "password")))
                .andExpect(status().isUnauthorized());
        // Nor by sending a code without a challenge.
        verify(null, DEMO_CODE).andExpect(status().isUnprocessableEntity());
        verify("made-up-challenge", DEMO_CODE).andExpect(status().isUnprocessableEntity());
        assertThat(sessionsOf("actuary")).isEqualTo(sessionsBefore);

        // Only the verified code turns the challenge into a session.
        String token = JsonPath.read(verify(challenge, DEMO_CODE).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("actuary"));
        assertThat(sessionsOf("actuary")).isEqualTo(sessionsBefore + 1);
    }

    @Test
    void wrongCodesAreCountedAndTheLastAllowedOneCancelsTheChallenge() throws Exception {
        String challenge = challenge("assessor");
        for (int left = 4; left >= 1; left--) {
            verify(challenge, "000000")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.message").value("That code is not right. You have " + left
                            + " attempt(s) left."));
        }
        verify(challenge, "000000")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(
                        "That code is not right, and it has been cancelled after too many attempts. Request a new code."));
        // Even the right code no longer works: the person signs in again with their password.
        verify(challenge, DEMO_CODE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This code has expired or was already used. Request a new code."));
    }

    @Test
    void aCodeOpensOneSessionOnlyAndNotAfterItExpires() throws Exception {
        String challenge = challenge("onboarding");
        verify(challenge, " 123 456 ").andExpect(status().isOk()).andExpect(jsonPath("$.username").value("onboarding"));
        verify(challenge, DEMO_CODE).andExpect(status().isUnprocessableEntity());

        String late = challenge("onboarding");
        tx.executeWithoutResult(s -> em.createQuery("update OneTimeCode o set o.expiresAt = :past"
                        + " where o.subject = 'onboarding' and o.usedAt is null")
                .setParameter("past", LocalDateTime.now().minusSeconds(1)).executeUpdate());
        verify(late, DEMO_CODE)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("This code has expired or was already used. Request a new code."));
    }

    @Test
    void anAccountDisabledBetweenPasswordAndCodeGetsNoSession() throws Exception {
        String challenge = challenge("configurator");
        setEnabled("configurator", false);
        try {
            verify(challenge, DEMO_CODE).andExpect(status().isUnauthorized());
        } finally {
            setEnabled("configurator", true);
        }
    }

    @Test
    void partnersNeedTheCodeTooButPortalCustomersDoNot() throws Exception {
        mockMvc.perform(post("/auth/session").with(httpBasic("agent", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondFactorRequired").value(true))
                .andExpect(jsonPath("$.token").doesNotExist());
        mockMvc.perform(get("/ops/portal").with(httpBasic("agent", "password")))
                .andExpect(status().isUnauthorized());

        // A portal customer of its own: another test here changes the seeded customer's password.
        mockMvc.perform(post("/portfolio/customers").header("Authorization", "Bearer " + open("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Lesedi Portal","email":"lesedi@example.com","phone":"+267 71 222 333",
                                 "username":"lesedi.portal","password":"password1"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/auth/session").with(httpBasic("lesedi.portal", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.secondFactorRequired").doesNotExist());
        mockMvc.perform(get("/me").with(httpBasic("lesedi.portal", "password1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerPortal").value(true));
    }

    /** Signs in fully: password, then (for staff and partners) the one-time code. */
    private String open(String username) throws Exception {
        String body = mockMvc.perform(post("/auth/session").with(httpBasic(username, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andReturn().getResponse().getContentAsString();
        if (!body.contains("\"secondFactorRequired\":true")) {
            assertThat((Integer) JsonPath.read(body, "$.idleMinutes")).isEqualTo(30);
            return JsonPath.read(body, "$.token");
        }
        body = verify(JsonPath.read(body, "$.challenge"), DEMO_CODE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.idleMinutes").value(30))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private String challenge(String username) throws Exception {
        String body = mockMvc.perform(post("/auth/session").with(httpBasic(username, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secondFactorRequired").value(true))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.challenge");
    }

    private ResultActions verify(String challenge, String code) throws Exception {
        String body = "{\"challenge\":" + (challenge == null ? "null" : "\"" + challenge + "\"")
                + ",\"code\":\"" + code + "\"}";
        return mockMvc.perform(post("/auth/session/verify").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long sessionsOf(String username) {
        return tx.execute(s -> em.createQuery("select count(a) from AuthSession a, AppUser u where a.userId = u.id"
                + " and u.username = :u", Long.class).setParameter("u", username).getSingleResult());
    }

    private void setEnabled(String username, boolean enabled) {
        tx.executeWithoutResult(s -> em.createQuery("update AppUser u set u.enabled = :e where u.username = :u")
                .setParameter("e", enabled).setParameter("u", username).executeUpdate());
    }

    private void age(String token, String field, LocalDateTime when) {
        tx.executeWithoutResult(s -> em.createQuery("update AuthSession a set a." + field + " = :when"
                        + " where a.tokenHash = :hash")
                .setParameter("when", when).setParameter("hash", SessionTokenService.sha256(token)).executeUpdate());
    }
}
