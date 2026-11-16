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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** NS-SH11-001: the browser holds an expiring session token, never the password. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-sessions;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class SessionTokenApiTest {

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

    private String open(String username) throws Exception {
        String body = mockMvc.perform(post("/auth/session").with(httpBasic(username, "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.idleMinutes").value(30))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private void age(String token, String field, LocalDateTime when) {
        tx.executeWithoutResult(s -> em.createQuery("update AuthSession a set a." + field + " = :when"
                        + " where a.tokenHash = :hash")
                .setParameter("when", when).setParameter("hash", SessionTokenService.sha256(token)).executeUpdate());
    }
}
