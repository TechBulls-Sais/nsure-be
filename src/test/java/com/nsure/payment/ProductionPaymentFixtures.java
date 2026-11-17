package com.nsure.payment;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared by the prod-profile payment tests: what application-prod.yml requires from the environment (database and
 * identity, nothing payment-related), and the steps that bring a customer's policy to Pending Payment. Requests are
 * signed in with {@link com.nsure.testsupport.SignIn}, which stands in for the staff one-time-code step.
 */
final class ProductionPaymentFixtures {

    static final String DB_USER = "spring.datasource.username=sa";
    static final String DB_PASSWORD = "spring.datasource.password=";
    /** The prod profile leaves migration to the migrate job; the test database needs its schema. */
    static final String MIGRATE = "spring.liquibase.enabled=true";
    static final String INSURER_CODE = "nsure.insurer.code=test-insurer";
    static final String INSURER_ENVIRONMENT = "nsure.insurer.environment=test";
    static final String INSURER_NAME = "nsure.insurer.display-name=Test Insurer";
    static final String BIND_IDENTITY = "nsure.insurer.bind-on-first-start=true";
    static final String PUBLIC_BASE_URL = "nsure.payments.public-base-url=http://localhost/api";
    static final String UI_BASE_URL = "nsure.payments.ui-base-url=http://localhost/#";
    /** The tests read the declaration code from the API to reach Pending Payment; it plays no part in payment. */
    static final String EXPOSE_OTP = "nsure.declaration.expose-otp=true";

    private final MockMvc mockMvc;
    private final EntityManager em;
    private final TransactionTemplate tx;

    ProductionPaymentFixtures(MockMvc mockMvc, EntityManager em, TransactionTemplate tx) {
        this.mockMvc = mockMvc;
        this.em = em;
        this.tx = tx;
    }

    /** A new customer (password {@code password1}) with a declared Term Life policy awaiting its first premium. */
    String pendingPolicy(String name, String username) throws Exception {
        String customer = mockMvc.perform(json(post("/portfolio/customers"), "admin",
                        "{\"name\":\"" + name + "\",\"email\":\"" + username + "@example.com\",\"phone\":\"+267 71 000 019\","
                                + "\"username\":\"" + username + "\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/portfolio/customers/" + JsonPath.read(customer, "$.id") + "/kyc")
                        .with(signedIn("admin", "password")))
                .andExpect(status().isOk());
        String shop = mockMvc.perform(json(post("/portfolio/shop"), username, "{\"product\":\"Term Life\",\"tier\":\"Silver\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String policy = JsonPath.read(shop, "$.requests[0].id");
        String sent = mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration/otp"), username, "{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(post("/portfolio/shop/policies/" + policy + "/declaration"), username,
                        "{\"code\":\"" + JsonPath.read(sent, "$.devCode") + "\",\"accuracy\":true,\"terms\":true,"
                                + "\"dataProtection\":true,\"falseInformation\":true}"))
                .andExpect(status().isOk());
        assertThat(policyStatus(policy)).isEqualTo("Pending Payment");
        return policy;
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        String password = user.equals("admin") ? "password" : "password1";
        return request.with(signedIn(user, password)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String policyStatus(String ref) {
        return tx.execute(s -> em.createQuery("select p.status from InsurancePolicy p where p.ref = :ref", String.class)
                .setParameter("ref", ref).getSingleResult());
    }

    long count(String jpql) {
        return tx.execute(s -> em.createQuery(jpql, Long.class).getSingleResult());
    }
}
