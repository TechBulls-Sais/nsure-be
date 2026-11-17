package com.nsure.portfolio;

import static com.nsure.testsupport.SignIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * NS-SH02-003 (test C-11): a product configuration goes draft → in review → active. Nobody activates a draft that
 * was never submitted for review, the maker never activates their own content, and activation and withdrawal are
 * published (ProductActivated, ProductWithdrawn).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-product-config-review;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProductConfigReviewApiTest {

    private static final String CONFIG = """
            {"name":"Review Life","code":"RV-01","status":"%s","benefits":["Death benefit"],"channels":["Broker portal"]}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aDraftIsActivatedOnlyAfterReviewAndNeverByItsMaker() throws Exception {
        String created = mockMvc.perform(json(post("/portfolio/product-configs"), "configurator", CONFIG.formatted("Draft")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.madeBy").value("configurator"))
                .andReturn().getResponse().getContentAsString();
        String ref = JsonPath.read(created, "$.id");

        // C-11: another user may not take a draft that never went to review straight to Active.
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "admin", CONFIG.formatted("Active")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("in review")));
        mockMvc.perform(get("/portfolio/product-configs").with(signedIn("admin", "password")))
                .andExpect(jsonPath("$[?(@.id == '" + ref + "')].status").value("Draft"));

        // The maker submits it for review; they still cannot activate it themselves.
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "configurator", CONFIG.formatted("In review")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("In review"))
                .andExpect(jsonPath("$.madeBy").value("configurator"));
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "configurator", CONFIG.formatted("Active")))
                .andExpect(status().isForbidden());
        assertThat(events("ProductActivated", ref)).isZero();

        // A second person activates it as reviewed: published once.
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "admin", CONFIG.formatted("Active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"))
                .andExpect(jsonPath("$.madeBy").value("configurator"));
        assertThat(events("ProductActivated", ref)).isEqualTo(1);

        // Withdrawing it from sale is product.activate too, and is published.
        mockMvc.perform(json(put("/portfolio/product-configs/" + ref), "admin", CONFIG.formatted("Withdrawn")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Withdrawn"));
        assertThat(events("ProductWithdrawn", ref)).isEqualTo(1);
        assertThat(events("ProductActivated", ref)).isEqualTo(1);
    }

    private long events(String type, String aggregate) {
        return jdbc.queryForObject("select count(*) from int_outbox_event where event_type = ? and aggregate_ref = ?",
                Long.class, type, aggregate);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String user, String body) {
        return request.with(signedIn(user, "password")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
