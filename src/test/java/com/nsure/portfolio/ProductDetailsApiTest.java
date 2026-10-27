package com.nsure.portfolio;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:nsure-product-details;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ProductDetailsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void productsCarryTheirCustomerFacingContent() throws Exception {
        mockMvc.perform(get("/portfolio/products").with(httpBasic("admin", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].need").value("family"))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].needLabel").value("Family income"))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].image").value("/products/term-life.jpg"))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].benefits[*]")
                        .value(Matchers.contains(
                                "Fixed-term life cover", "Supports income and school costs", "Clear monthly premium")))
                .andExpect(jsonPath("$[?(@.name == 'Care Cash')].coverUnit").value("day"))
                .andExpect(jsonPath("$[?(@.name == 'Care Cash')].fits")
                        .value("When medical aid helps with treatment, but living costs still continue."))
                .andExpect(jsonPath("$[?(@.name == 'Term Life')].coverUnit").value(""));
    }

    @Test
    void customerShopReturnsTheSameProductContent() throws Exception {
        mockMvc.perform(get("/portfolio/shop").with(httpBasic("customer", "password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products[?(@.name == 'Individual Credit')].needIcon").value("Landmark"))
                .andExpect(jsonPath("$.products[?(@.name == 'Individual Credit')].summary")
                        .value("Helps cover an outstanding loan or credit balance."));
    }
}
